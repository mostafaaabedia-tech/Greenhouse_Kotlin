package com.hbrt.agro

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

enum class Link { IDLE, CONNECTING, ONLINE, RECONNECTING, FAILED }

/** How we are talking to the greenhouse right now. */
enum class Via { NONE, WIFI, CLOUD }

/** A greenhouse's place in the Firebase database: https://<db>/hbrt/<key>. View-only keys start with "gv". */
data class CloudRef(val key: String, val db: String) {
    val base get() = "https://$db/hbrt/$key"
    val ro get() = key.startsWith("gv")
    fun code() = "$key@$db"
    fun shareLink() = "$WEB_APP#${if (ro) "v" else "g"}=${code()}"

    companion object {
        const val WEB_APP = "https://mostafaaabedia-tech.github.io/HBRT-AGRO/"
        private val RX = Regex("([A-Za-z0-9]{16,})@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
        fun parse(s: String?): CloudRef? = s?.let { RX.find(it) }?.let { CloudRef(it.groupValues[1], it.groupValues[2]) }
    }
}

/**
 * One connection to the greenhouse, shared by the screens, the background service and the widget.
 *
 *  - Wi-Fi: WebSocket on port 81. Fastest, works without internet.
 *  - Cloud: Firebase Realtime Database (live stream + command queue). Works from any network.
 * Wi-Fi is tried first; when the board can't be reached locally the app switches to the cloud on its
 * own, and back to Wi-Fi as soon as the board answers locally again.
 *
 * The board sends five parts, the same over Wi-Fi and through the cloud:
 *   s (live readings), cfg (settings), wx (weather), use (water/power), ev (timeline)
 */
object GreenhouseClient {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()
    val cloudHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val JSON_TYPE = "application/json".toMediaType()

    val link = MutableStateFlow(Link.IDLE)
    val linked = MutableStateFlow(false)
    val via = MutableStateFlow(Via.NONE)
    val readOnly = MutableStateFlow(false)
    /** In cloud mode: is the board itself still sending? (false = greenhouse offline) */
    val boardOnline = MutableStateFlow(true)
    val latest = MutableStateFlow<Reading?>(null)
    val cfg = MutableStateFlow<Cfg?>(null)
    val wx = MutableStateFlow<Wx?>(null)
    val use = MutableStateFlow<Use?>(null)
    val events = MutableStateFlow<List<Ev>>(emptyList())
    val live = MutableStateFlow<List<Reading>>(emptyList())
    val error = MutableStateFlow("")
    val attempt = MutableStateFlow(0)
    val activeAlerts = MutableStateFlow<List<String>>(emptyList())

    @Volatile var lastMsgAt = 0L
        private set
    @Volatile var url = ""
        private set
    @Volatile var address = ""
        private set
    @Volatile var cloud: CloudRef? = null
        private set

    @Volatile private var wifi: Network? = null
    @Volatile private var wifiProps: LinkProperties? = null
    @Volatile private var gen = 0
    @Volatile private var manual = true
    @Volatile private var keepTrying = false
    @Volatile private var mode = Via.WIFI
    @Volatile private var triedCloud = false
    @Volatile var serverSkew = 0L
        private set
    @Volatile private var lastStateT = 0L
    @Volatile private var lastProbe = 0L
    private var ws: WebSocket? = null
    private var es: EventSource? = null
    private var doc = JSONObject()
    private var retryJob: Job? = null
    private var beatJob: Job? = null
    private var lastLogged = 0L
    @Volatile private var namedAs = ""
    private var lastPrune = 0L
    private var lastWidget = 0L
    private val historySynced = HashSet<String>()

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            while (isActive) {
                delay(3000)
                val now = System.currentTimeMillis()
                if (link.value == Link.ONLINE && via.value == Via.WIFI && now - lastMsgAt > 8000) ws?.cancel()
                if (link.value == Link.ONLINE && via.value == Via.CLOUD) {
                    boardOnline.value = lastMsgAt > 0 && now - lastMsgAt < 35_000
                    if (now - lastProbe > 20_000) { lastProbe = now; tryUpgradeToWifi() }
                }
            }
        }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifiReq = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(wifiReq, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi = network; kick() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { if (wifi == network) wifiProps = lp }
            override fun onLost(network: Network) { if (wifi == network) { wifi = null; wifiProps = null } }
        })
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = kick()
        })
    }

    /* ---------------- addresses ---------------- */

    private fun onWifiSubnet(host: String): Boolean {
        val lp = wifiProps ?: return false
        if (!Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) return false
        val target = try { InetAddress.getByName(host).address } catch (e: Exception) { return false }
        return lp.linkAddresses.any { la ->
            val a = la.address
            if (a !is Inet4Address) return@any false
            val mine = a.address
            val bits = la.prefixLength
            (0 until 32).all { i -> i >= bits || ((mine[i / 8].toInt() shr (7 - i % 8)) and 1) == ((target[i / 8].toInt() shr (7 - i % 8)) and 1) }
        }
    }

    /** Addresses on the Wi-Fi subnet go over Wi-Fi even when that Wi-Fi has no internet. */
    fun httpFor(host: String): OkHttpClient {
        val w = wifi
        return if (w != null && onWifiSubnet(host)) http.newBuilder().socketFactory(w.socketFactory).build() else http
    }

    fun buildUrl(input: String): String {
        val v = input.trim()
        return when {
            v.startsWith("ws://", true) || v.startsWith("wss://", true) -> v
            v.startsWith("http://", true) || v.startsWith("https://", true) -> {
                val u = URI(v)
                (if (u.scheme.equals("https", true)) "wss://" else "ws://") + u.authority
            }
            v.contains(":") -> "ws://$v"
            else -> "ws://$v:81"
        }
    }

    fun isCloudAddress(a: String) = a.startsWith("cloud:") || a.contains("#g=") || a.contains("#v=") || (CloudRef.parse(a) != null && !a.contains("://"))

    /** The board's IP on the local network, when we know it. */
    fun localHost(): String? = try { URI(url).host } catch (e: Exception) { null } ?: latest.value?.ip?.takeIf { it.isNotBlank() && it != "0.0.0.0" }

    /* ---------------- connecting ---------------- */

    /**
     * @param input IP / URL for Wi-Fi, or a share link / "cloud:key@db" for the cloud.
     * @param persistent keep retrying even if the first attempt fails (background service).
     */
    fun connect(input: String, persistent: Boolean = false) {
        val a = input.trim()
        if (isCloudAddress(a)) {
            val ref = CloudRef.parse(a)
            if (ref == null) { error.value = app.getString(R.string.a_bad_link); link.value = Link.FAILED; return }
            cloud = ref; url = ""; address = "cloud:${ref.code()}"
        } else {
            url = try { buildUrl(a) } catch (e: Exception) {
                error.value = app.getString(R.string.a_bad_addr); link.value = Link.FAILED; return
            }
            address = a
            cloud = CloudRef.parse(Prefs.greenhouses.firstOrNull { it.addr.equals(a, true) }?.cloud)
        }
        readOnly.value = cloud?.ro == true && url.isEmpty()
        Prefs.lastAddress = address
        manual = false
        keepTrying = persistent
        linked.value = false
        attempt.value = 0
        error.value = ""
        triedCloud = false
        lastStateT = 0L
        doc = JSONObject()
        latest.value = null; cfg.value = null; wx.value = null; use.value = null; events.value = emptyList(); live.value = emptyList()
        mode = if (url.isNotEmpty()) Via.WIFI else Via.CLOUD
        open()
    }

    @Synchronized
    private fun open() {
        retryJob?.cancel()
        closeTransports()
        val my = ++gen
        link.value = if (linked.value) Link.RECONNECTING else Link.CONNECTING
        if (mode == Via.CLOUD) openCloud(my) else openWifi(my)
    }

    private fun closeTransports() {
        ws?.cancel(); ws = null
        es?.cancel(); es = null
        beatJob?.cancel(); beatJob = null
    }

    private fun online(newVia: Via) {
        attempt.value = 0
        error.value = ""
        linked.value = true
        Prefs.autoConnect = true
        via.value = newVia
        link.value = Link.ONLINE
    }

    private fun openWifi(my: Int) {
        val req = try { Request.Builder().url(url).build() } catch (e: Exception) {
            error.value = app.getString(R.string.a_bad_addr); link.value = Link.FAILED; return
        }
        ws = httpFor(req.url.host).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my != gen) return
                lastMsgAt = System.currentTimeMillis()
                boardOnline.value = true
                readOnly.value = false
                online(Via.WIFI)
                syncClock()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my != gen) return
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                if (j.has("pong")) return
                if (j.has("temperature")) {                               // firmware 4.x / 5.x: one flat packet
                    applyPart("put", listOf("s"), j)
                    j.optJSONObject("cfg")?.let { applyPart("put", listOf("cfg"), it) }
                } else for (k in j.keys()) applyPart(if (k == "ev") "patch" else "put", listOf(k), j.get(k))
                afterUpdate(System.currentTimeMillis())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (my == gen) lost() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (my == gen) lost() }
        })
    }

    private fun openCloud(my: Int) {
        val ref = cloud ?: run { lost(); return }
        triedCloud = true
        doc = JSONObject()
        readOnly.value = ref.ro
        val req = Request.Builder().url("${ref.base}/live.json").header("Accept", "text/event-stream").build()
        es = EventSources.createFactory(cloudHttp).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (my != gen) return
                online(Via.CLOUD)
                beatJob = scope.launch {
                    if (!ref.ro) syncClock()
                    while (isActive) { heartbeat(ref); delay(20_000) }
                }
                if (historySynced.add(ref.key)) scope.launch { importCloudHistory(ref) }
            }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (my != gen) return
                when (type) {
                    "put", "patch" -> try {
                        val j = JSONObject(data)
                        val parts = j.optString("path", "/").trim('/').split('/').filter { it.isNotEmpty() }
                        applyPart(type, parts, j.opt("data"))
                        val t = (doc.optJSONObject("s")?.optLong("t", 0L) ?: 0L)
                        val produced = if (t > 0) t - serverSkew else 0L
                        if (t == 0L || t != lastStateT) {
                            if (t != 0L) lastStateT = t
                            afterUpdate(if (produced > 0) produced.coerceAtMost(System.currentTimeMillis()) else 0L)
                        } else afterUpdate(-1L)
                    } catch (e: Exception) { /* ignore a malformed event */ }
                    "cancel", "auth_revoked" -> {
                        error.value = app.getString(R.string.a_cloud_refused)
                        eventSource.cancel(); lost()
                    }
                }
            }
            override fun onClosed(eventSource: EventSource) { if (my == gen) lost() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (my != gen) return
                if (response?.code == 401 || response?.code == 403) error.value = app.getString(R.string.a_cloud_refused)
                lost()
            }
        })
    }

    /** Merges one update into our copy of /live (Firebase stream path rules). */
    @Synchronized
    private fun applyPart(type: String, parts: List<String>, data: Any?) {
        if (parts.isEmpty()) {
            if (type == "put") doc = (data as? JSONObject) ?: JSONObject()
            else (data as? JSONObject)?.let { d -> d.keys().forEach { k -> doc.put(k, d.get(k)) } }
            return
        }
        var node = doc
        parts.dropLast(1).forEach { p -> node = node.optJSONObject(p) ?: JSONObject().also { node.put(p, it) } }
        val leaf = parts.last()
        if (data == null || data == JSONObject.NULL) node.remove(leaf)
        else if (type == "patch" && data is JSONObject) {
            val target = node.optJSONObject(leaf) ?: JSONObject().also { node.put(leaf, it) }
            data.keys().forEach { k -> target.put(k, data.get(k)) }
        } else node.put(leaf, data)
    }

    /** @param producedAt when the board made the newest reading (phone clock); 0 unknown, -1 = readings unchanged. */
    private fun afterUpdate(producedAt: Long) {
        val d = synchronized(this) { JSONObject(doc.toString()) }
        Cfg.parse(d.optJSONObject("cfg"))?.let { if (it != cfg.value) cfg.value = it }
        Wx.parse(d.optJSONObject("wx"))?.let { if (it != wx.value) wx.value = it }
        Use.parse(d.optJSONObject("use"))?.let { if (it != use.value) use.value = it }
        d.optJSONObject("ev")?.let { e ->
            val list = e.keys().asSequence().mapNotNull { k -> e.optJSONObject(k)?.let { Ev.parse(it) } }.sortedByDescending { it.n }.toList()
            if (list != events.value) { events.value = list; History.addEvents(latest.value?.id ?: "", list) }
        }
        if (producedAt == -1L) return
        val s = d.optJSONObject("s") ?: return
        val r = Reading.parse(s, if (producedAt > 0) producedAt else System.currentTimeMillis()) ?: return
        if (cfg.value == null && r.legacyCfg != null) cfg.value = r.legacyCfg
        handle(r)
        if (via.value == Via.CLOUD) boardOnline.value = System.currentTimeMillis() - r.ts < 35_000
    }

    /** Tells the board someone is watching (it switches to 2-second updates) and measures clock skew. */
    private fun heartbeat(ref: CloudRef) {
        val sent = System.currentTimeMillis()
        val req = Request.Builder().url("${ref.base}${if (ref.ro) "/seen" else "/in/seen"}.json")
            .put("{\".sv\":\"timestamp\"}".toRequestBody(JSON_TYPE)).build()
        try {
            cloudHttp.newCall(req).execute().use { r ->
                val server = r.body?.string()?.trim()?.toLongOrNull()
                if (r.isSuccessful && server != null) serverSkew = server - (sent + System.currentTimeMillis()) / 2
            }
        } catch (e: IOException) { /* next beat */ }
    }

    private fun cloudSend(json: JSONObject): Boolean {
        val ref = cloud ?: return false
        if (ref.ro) return false
        val body = json.put("t", JSONObject().put(".sv", "timestamp")).toString()
        val req = Request.Builder().url("${ref.base}/in/cmd.json").post(body.toRequestBody(JSON_TYPE)).build()
        cloudHttp.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { error.value = app.getString(R.string.a_not_delivered) }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
        scope.launch { heartbeat(ref) }
        return true
    }

    /** Fills gaps in the phone's history with the board's own 7-day cloud log (5-minute points). */
    private fun importCloudHistory(ref: CloudRef) {
        try {
            val req = Request.Builder().url("${ref.base}/log.json").build()
            val text = cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return
            if (text == "null") return
            val j = JSONObject(text)
            val out = ArrayList<Sample>()
            j.keys().forEach { k ->
                val o = j.optJSONObject(k) ?: return@forEach
                val ts = o.optLong("t")
                if (ts > 0) out += Sample(ts, o.optDouble("T").toFloat(), o.optDouble("H").toFloat(), o.optInt("S"),
                    o.optDouble("ST").toFloat(), o.optInt("W"), false, false, false, false, false)
            }
            History.fillGaps(out.sortedBy { it.ts })
        } catch (e: Exception) { /* not important */ }
    }

    private fun tryUpgradeToWifi() {
        val u = url
        if (u.isEmpty() || manual || wifi == null) return
        val host = try { URI(u).host } catch (e: Exception) { return } ?: return
        val client = httpFor(host).newBuilder().pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(900, TimeUnit.MILLISECONDS).callTimeout(1500, TimeUnit.MILLISECONDS).build()
        val ok = try { client.newCall(Request.Builder().url("http://$host/info").build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
        if (ok && via.value == Via.CLOUD && !manual) { mode = Via.WIFI; open() }
    }

    @Synchronized
    private fun lost() {
        closeTransports()
        if (manual) return
        if (mode == Via.WIFI && cloud != null && (!triedCloud || linked.value)) {
            mode = Via.CLOUD
            val my = ++gen
            link.value = if (linked.value) Link.RECONNECTING else Link.CONNECTING
            openCloud(my)
            return
        }
        if (!linked.value && !keepTrying) {
            link.value = Link.FAILED
            if (error.value.isEmpty()) error.value = app.getString(if (cloud != null) R.string.a_cant_any else R.string.a_cant_connect)
            return
        }
        attempt.value = attempt.value + 1
        link.value = Link.RECONNECTING
        val wait = min(1000.0 * 1.6.pow(attempt.value - 1), 10_000.0).toLong()
        mode = if (url.isNotEmpty()) Via.WIFI else Via.CLOUD
        triedCloud = false
        retryJob = scope.launch { delay(wait); open() }
    }

    /** Retry right away (network came back, app returned to foreground). */
    fun kick() {
        if (manual || address.isEmpty()) return
        if ((linked.value || keepTrying) && link.value != Link.ONLINE) {
            attempt.value = 0
            mode = if (url.isNotEmpty()) Via.WIFI else Via.CLOUD
            triedCloud = false
            open()
        } else if (link.value == Link.ONLINE && via.value == Via.CLOUD) scope.launch { tryUpgradeToWifi() }
    }

    fun disconnect() {
        manual = true
        keepTrying = false
        Prefs.autoConnect = false
        retryJob?.cancel()
        gen++
        ws?.close(1000, "bye"); ws = null
        es?.cancel(); es = null
        beatJob?.cancel()
        linked.value = false
        via.value = Via.NONE
        link.value = Link.IDLE
        latest.value = null; cfg.value = null; wx.value = null; use.value = null; events.value = emptyList()
        live.value = emptyList()
        activeAlerts.value = emptyList()
        lastMsgAt = 0L
    }

    /* ---------------- commands ---------------- */

    private fun syncClock() {
        val now = System.currentTimeMillis()
        send(JSONObject().put("device", "time").put("value", (now + TimeZone.getDefault().getOffset(now)) / 1000))
    }

    fun send(j: JSONObject): Boolean = when {
        readOnly.value -> false
        link.value != Link.ONLINE -> false
        via.value == Via.CLOUD -> cloudSend(j)
        else -> ws?.send(j.toString()) == true
    }

    private fun cmd(device: String, value: Any? = null) = JSONObject().put("device", device).also { if (value != null) it.put("value", value) }

    /** Switch a device. [mins]: minutes before automatic takes over again, -1 = until changed, null = old firmware (manual mode). */
    fun device(d: String, on: Boolean, mins: Int?): Boolean {
        val j = cmd(d, on)
        if (mins != null) j.put("mins", mins)
        val ok = send(j)
        if (ok) optimistic { r ->
            val ovr = if (mins != null && r.mode != "manual") r.ovr + (d to if (mins < 0) -1 else mins * 60) else r.ovr
            when (d) {
                "fan" -> r.copy(fan = on, ovr = ovr); "lights" -> r.copy(lights = on, ovr = ovr); "heater" -> r.copy(heater = on, ovr = ovr)
                "servo" -> r.copy(servo = on, ovr = ovr); "pump" -> r.copy(pump = on, ovr = ovr); else -> r
            }
        }
        return ok
    }
    fun setMode(m: String): Boolean = send(cmd("mode", m)).also { if (it) optimistic { r -> r.copy(mode = m, ovr = if (m == "manual") emptyMap() else r.ovr) } }
    fun clearOverride(d: String): Boolean = send(cmd("ovr_clear", d)).also { if (it) optimistic { r -> r.copy(ovr = if (d == "all") emptyMap() else r.ovr - d) } }
    fun waterNow(seconds: Int): Boolean = send(cmd("water", seconds)).also { if (it) optimistic { r -> r.copy(pump = true, watering = seconds) } }
    fun stopWater(): Boolean = send(cmd("stopwater")).also { if (it) optimistic { r -> r.copy(pump = false, watering = 0) } }
    fun allOff(): Boolean = send(cmd("alloff")).also { if (it) optimistic { r -> r.copy(fan = false, pump = false, lights = false, heater = false, servo = false, mode = "manual", ovr = emptyMap(), watering = 0) } }
    fun config(j: JSONObject): Boolean = send(j.put("device", "config"))
    fun calibrate(what: String): Boolean = send(cmd("cal", what))
    fun viewLink(on: Boolean): Boolean = send(cmd("view", on))
    fun telegramTest(): Boolean = send(cmd("tgtest"))
    fun updateFirmware(): Boolean = send(cmd("ota"))
    fun reboot(): Boolean = send(cmd("reboot"))
    /** New private cloud key on the board: old share links stop working. Wi-Fi only. */
    fun newCloudKey(): Boolean = via.value == Via.WIFI && send(cmd("rekey"))
    fun setWifi(ssid: String, pass: String): Boolean = via.value == Via.WIFI && send(cmd("wifi").put("ssid", ssid).put("pass", pass))

    fun rename(name: String): Boolean {
        val ok = send(cmd("name", name))
        if (ok) Prefs.saveGreenhouse(SavedGh(name, address, latest.value?.id ?: "", cloud?.code() ?: ""))
        return ok
    }

    private fun optimistic(f: (Reading) -> Reading) {
        val r = latest.value ?: return
        latest.value = f(r)
    }

    /** Sends a .bin file to the board over Wi-Fi (http://<ip>/update). [progress] gets 0..1. */
    suspend fun uploadFirmware(c: Context, uri: Uri, progress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val host = localHost() ?: return@withContext false
        val key = latest.value?.key ?: ""
        val size = c.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                c.contentResolver.openInputStream(uri)?.use { inp ->
                    val buf = ByteArray(8192)
                    var sent = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        if (size > 0) progress(sent.toFloat() / size)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("update", "firmware.bin", body).build()
        val req = Request.Builder().url("http://$host/update").header("X-Key", key).post(multipart).build()
        try {
            httpFor(host).newBuilder().pingInterval(0, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /* ---------------- incoming readings ---------------- */

    private fun handle(r: Reading) {
        lastMsgAt = r.ts
        val first = latest.value == null
        latest.value = r
        remember(r, first)
        live.value = (live.value + r).takeLast(90)

        val now = r.ts
        if (now - lastLogged >= Prefs.logEverySec * 1000L) { lastLogged = now; History.add(r.toSample()) }
        if (now - lastPrune > 3_600_000L) { lastPrune = now; History.prune(now - 180L * 86_400_000L) }

        val cur = r.alerts(cfg.value)
        val prev = activeAlerts.value
        cur.forEach { key ->
            if (key !in prev && !first) {
                val msg = Notifier.alertText(app, key, r, wx.value)
                History.logAlert(msg)
                Notifier.alert(app, r.name, msg)
            }
        }
        activeAlerts.value = cur
        if (System.currentTimeMillis() - lastWidget > 60_000) { lastWidget = System.currentTimeMillis(); Widget.update(app, r) }
    }

    /** Pairing: the board puts its cloud key in every packet; the first Wi-Fi connection saves it. */
    private fun remember(r: Reading, first: Boolean) {
        val ref = if (r.key.length >= 16 && r.db.isNotEmpty()) CloudRef(r.key, r.db) else null
        val keyChanged = ref != null && ref != cloud && via.value == Via.WIFI
        if (keyChanged) cloud = ref
        val curHost = try { URI(url).host ?: "" } catch (e: Exception) { "" }
        val ipChanged = via.value == Via.CLOUD && r.ip.isNotEmpty() && r.ip != "0.0.0.0" &&
            Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(curHost) && curHost != r.ip
        if (ipChanged) {
            Prefs.greenhouses.firstOrNull { it.id == r.id && it.id.isNotEmpty() && !it.viewOnly }?.let { Prefs.removeGreenhouse(it.addr) }
            url = buildUrl(r.ip); address = r.ip; Prefs.lastAddress = address
        }
        if (first || r.name != namedAs || keyChanged || ipChanged) {
            namedAs = r.name
            Prefs.saveGreenhouse(SavedGh(r.name, address, r.id, cloud?.code() ?: ""))
        }
    }

    /* ---------------- journal (stored in the cloud so the whole team sees it) ---------------- */

    data class Entry(val id: String, val t: Long, val k: String, val x: String, val g: Float, val thumb: String)

    suspend fun journal(): List<Entry>? = withContext(Dispatchers.IO) {
        val ref = cloud ?: return@withContext null
        try {
            val u = "${ref.base}/journal.json?orderBy=%22%24key%22&limitToLast=60"
            val text = cloudHttp.newCall(Request.Builder().url(u).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            if (text == "null") return@withContext emptyList()
            val j = JSONObject(text)
            j.keys().asSequence().mapNotNull { id ->
                j.optJSONObject(id)?.let { o -> Entry(id, o.optLong("t"), o.optString("k", "note"), o.optString("x"), o.optDouble("g", 0.0).toFloat(), o.optString("th")) }
            }.sortedByDescending { it.t }.toList()
        } catch (e: Exception) { null }
    }

    suspend fun addEntry(kind: String, text: String, grams: Float, thumb: String?, full: String?): Boolean = withContext(Dispatchers.IO) {
        val ref = cloud ?: return@withContext false
        if (ref.ro) return@withContext false
        val e = JSONObject().put("t", JSONObject().put(".sv", "timestamp")).put("k", kind).put("x", text)
        if (kind == "harvest") e.put("g", grams)
        if (thumb != null) e.put("th", thumb)
        try {
            val name = cloudHttp.newCall(Request.Builder().url("${ref.base}/journal.json").post(e.toString().toRequestBody(JSON_TYPE)).build())
                .execute().use { if (it.isSuccessful) JSONObject(it.body!!.string()).optString("name") else null } ?: return@withContext false
            if (full != null) put("${ref.base}/photos/$name.json", JSONObject.quote(full))
            latest.value?.view?.takeIf { it.isNotBlank() }?.let { vk ->
                val vb = "https://${ref.db}/hbrt/$vk"
                put("$vb/journal/$name.json", e.toString())
                if (full != null) put("$vb/photos/$name.json", JSONObject.quote(full))
            }
            true
        } catch (x: Exception) { false }
    }

    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        val ref = cloud ?: return@withContext
        val bases = listOf(ref.base) + (latest.value?.view?.takeIf { it.isNotBlank() }?.let { listOf("https://${ref.db}/hbrt/$it") } ?: emptyList())
        for (b in bases) for (p in listOf("journal", "photos")) try {
            cloudHttp.newCall(Request.Builder().url("$b/$p/$id.json").delete().build()).execute().close()
        } catch (e: Exception) { }
    }

    suspend fun photo(id: String): String? = withContext(Dispatchers.IO) {
        val ref = cloud ?: return@withContext null
        try {
            cloudHttp.newCall(Request.Builder().url("${ref.base}/photos/$id.json").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.let { if (it == "null") null else JSONArray("[$it]").getString(0) }
            }
        } catch (e: Exception) { null }
    }

    private fun put(url: String, body: String) {
        cloudHttp.newCall(Request.Builder().url(url).put(body.toRequestBody(JSON_TYPE)).build()).execute().close()
    }
}
