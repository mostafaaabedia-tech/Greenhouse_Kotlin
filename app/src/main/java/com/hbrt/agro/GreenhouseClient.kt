package com.hbrt.agro

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
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

/** A greenhouse's private place in the Firebase database: https://<db>/hbrt/<key> */
data class CloudRef(val key: String, val db: String) {
    val base get() = "https://$db/hbrt/$key"
    fun code() = "$key@$db"
    fun shareLink() = "$WEB_APP#g=${code()}"

    companion object {
        const val WEB_APP = "https://mostafaaabedia-tech.github.io/HBRT-AGRO/"
        private val RX = Regex("([A-Za-z0-9]{16,})@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")

        /** Accepts a share link, "cloud:key@db" or just "key@db". */
        fun parse(s: String?): CloudRef? = s?.let { RX.find(it) }?.let { CloudRef(it.groupValues[1], it.groupValues[2]) }
    }
}

/**
 * One connection to the greenhouse, shared by the UI and the background service.
 *
 * Two ways to reach the board:
 *  - Wi-Fi: WebSocket on port 81. Fastest, works without internet.
 *  - Cloud: Firebase Realtime Database (live stream + command queue). Works from any network.
 * Wi-Fi is tried first. If the board can't be reached locally the app switches to the cloud on its
 * own, and switches back to Wi-Fi as soon as the board is reachable again.
 *
 * Stability: pings every 5 s, stale-data watchdog, back-off reconnects, instant reconnect when a
 * network comes back, and local IPs forced over Wi-Fi when that Wi-Fi has no internet.
 */
object GreenhouseClient {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()
    private val cloudHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)      // Firebase sends keep-alive every 30 s
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val JSON = "application/json".toMediaType()

    val link = MutableStateFlow(Link.IDLE)
    val linked = MutableStateFlow(false)
    val via = MutableStateFlow(Via.NONE)
    /** In cloud mode: is the board itself still sending? (false = greenhouse offline) */
    val boardOnline = MutableStateFlow(true)
    val latest = MutableStateFlow<Reading?>(null)
    val live = MutableStateFlow<List<Reading>>(emptyList())
    val error = MutableStateFlow("")
    val attempt = MutableStateFlow(0)
    val activeAlerts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** When the board produced the newest reading (phone clock). */
    @Volatile var lastMsgAt = 0L
        private set
    @Volatile var url = ""
        private set
    /** The address the user typed or picked (IP, URL, or "cloud:key@db"). */
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
    @Volatile private var serverSkew = 0L          // Firebase server time minus phone time
    @Volatile private var lastStateT = 0L
    @Volatile private var lastProbe = 0L
    private var ws: WebSocket? = null
    private var es: EventSource? = null
    private var cloudState = JSONObject()
    private var retryJob: Job? = null
    private var beatJob: Job? = null
    private var lastLogged = 0L
    @Volatile private var namedAs = ""
    private var lastPrune = 0L
    private val historySynced = HashSet<String>()

    fun init(c: Context) {
        app = c.applicationContext

        scope.launch {
            while (isActive) {
                delay(3000)
                val now = System.currentTimeMillis()
                if (link.value == Link.ONLINE && via.value == Via.WIFI && now - lastMsgAt > 8000) {
                    ws?.cancel() // triggers onFailure -> reconnect
                }
                if (link.value == Link.ONLINE && via.value == Via.CLOUD) {
                    boardOnline.value = lastMsgAt > 0 && now - lastMsgAt < 35_000
                    if (now - lastProbe > 20_000) { lastProbe = now; tryUpgradeToWifi() }
                }
            }
        }

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifiReq = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(wifiReq, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi = network
                kick()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (wifi == network) wifiProps = lp
            }

            override fun onLost(network: Network) {
                if (wifi == network) { wifi = null; wifiProps = null }
            }
        })
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = kick()
        })
    }

    /** True when [host] is an IPv4 address inside the Wi-Fi network's subnet. */
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

    /**
     * HTTP client for [host]. Addresses on the Wi-Fi subnet are forced over Wi-Fi, so the app still
     * reaches the ESP32 when that Wi-Fi has no internet. Hotspot addresses use the normal route.
     */
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

    fun isCloudAddress(a: String) = a.startsWith("cloud:") || a.contains("#g=") || (CloudRef.parse(a) != null && !a.contains("://"))

    /**
     * @param input IP / URL for Wi-Fi, or a share link / "cloud:key@db" for cloud only.
     * @param persistent keep retrying even if the first attempt fails (background service).
     */
    fun connect(input: String, persistent: Boolean = false) {
        val a = input.trim()
        val cloudOnly = isCloudAddress(a)
        if (cloudOnly) {
            val ref = CloudRef.parse(a)
            if (ref == null) { error.value = "That share link is not valid."; link.value = Link.FAILED; return }
            cloud = ref
            url = ""
            address = "cloud:${ref.code()}"
        } else {
            url = try {
                buildUrl(a)
            } catch (e: Exception) {
                error.value = "That address is not valid."
                link.value = Link.FAILED
                return
            }
            address = a
            cloud = CloudRef.parse(Prefs.greenhouses.firstOrNull { it.addr.equals(a, true) }?.cloud)
        }
        Prefs.lastAddress = address
        manual = false
        keepTrying = persistent
        linked.value = false
        attempt.value = 0
        error.value = ""
        triedCloud = false
        lastStateT = 0L
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

    /* ---------------- Wi-Fi (WebSocket) ---------------- */

    private fun openWifi(my: Int) {
        val req = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            error.value = "That address is not valid."
            link.value = Link.FAILED
            return
        }
        ws = httpFor(req.url.host).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my != gen) return
                lastMsgAt = System.currentTimeMillis()
                boardOnline.value = true
                online(Via.WIFI)
                syncClock()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my == gen) handle(text, System.currentTimeMillis())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (my == gen) lost()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (my == gen) lost()
            }
        })
    }

    /* ---------------- Cloud (Firebase) ---------------- */

    private fun openCloud(my: Int) {
        val ref = cloud ?: run { lost(); return }
        triedCloud = true
        cloudState = JSONObject()
        val req = Request.Builder().url("${ref.base}/state.json").header("Accept", "text/event-stream").build()
        es = EventSources.createFactory(cloudHttp).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (my != gen) return
                online(Via.CLOUD)
                beatJob = scope.launch {
                    syncClock()
                    while (isActive) { heartbeat(ref); delay(20_000) }
                }
                if (historySynced.add(ref.key)) scope.launch { importCloudHistory(ref) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (my != gen) return
                when (type) {
                    "put", "patch" -> try {
                        val j = JSONObject(data)
                        applyCloud(type, j.optString("path", "/"), j.opt("data"))
                    } catch (e: Exception) { /* ignore malformed event */ }
                    "cancel", "auth_revoked" -> {
                        error.value = "The cloud refused access. Check the Firebase database rules."
                        eventSource.cancel()
                        lost()
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (my == gen) lost()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (my == gen) {
                    if (response?.code == 401 || response?.code == 403) error.value = "The cloud refused access. Check the Firebase database rules."
                    lost()
                }
            }
        })
    }

    /** Applies a Firebase stream event to our copy of /state, then hands new readings to [handle]. */
    private fun applyCloud(type: String, path: String, data: Any?) {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            if (type == "put") cloudState = (data as? JSONObject) ?: JSONObject()
            else (data as? JSONObject)?.let { d -> d.keys().forEach { k -> cloudState.put(k, d.get(k)) } }
        } else {
            var node = cloudState
            parts.dropLast(1).forEach { p -> node = node.optJSONObject(p) ?: JSONObject().also { node.put(p, it) } }
            val leaf = parts.last()
            if (data == null || data == JSONObject.NULL) node.remove(leaf)
            else if (type == "patch" && data is JSONObject) {
                val target = node.optJSONObject(leaf) ?: JSONObject().also { node.put(leaf, it) }
                data.keys().forEach { k -> target.put(k, data.get(k)) }
            } else node.put(leaf, data)
        }
        val t = cloudState.optLong("t", 0L)
        if (t == 0L || t == lastStateT) return
        lastStateT = t
        val producedAt = t - serverSkew                 // board time -> phone clock
        handle(cloudState.toString(), producedAt.coerceAtMost(System.currentTimeMillis()))
        boardOnline.value = System.currentTimeMillis() - producedAt < 35_000
    }

    /** Tells the board someone is watching (it switches to 2-second updates) and measures clock skew. */
    private fun heartbeat(ref: CloudRef) {
        val sent = System.currentTimeMillis()
        val req = Request.Builder().url("${ref.base}/in/seen.json")
            .put("{\".sv\":\"timestamp\"}".toRequestBody(JSON)).build()
        try {
            cloudHttp.newCall(req).execute().use { r ->
                val server = r.body?.string()?.trim()?.toLongOrNull()
                if (r.isSuccessful && server != null) {
                    val got = System.currentTimeMillis()
                    serverSkew = server - (sent + got) / 2
                }
            }
        } catch (e: IOException) { /* next beat */ }
    }

    private fun cloudSend(json: String): Boolean {
        val ref = cloud ?: return false
        val body = try { JSONObject(json).put("t", JSONObject().put(".sv", "timestamp")).toString() } catch (e: Exception) { return false }
        val req = Request.Builder().url("${ref.base}/in/cmd.json").post(body.toRequestBody(JSON)).build()
        cloudHttp.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { error.value = "Command not delivered. Check the internet." }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
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
                val ts = o.optLong("t") - serverSkew
                if (ts > 0) out += Sample(
                    ts, o.optDouble("T").toFloat(), o.optDouble("H").toFloat(), o.optInt("S"),
                    o.optDouble("ST").toFloat(), o.optInt("W"), false, false, false, false, false
                )
            }
            History.fillGaps(out.sortedBy { it.ts })
        } catch (e: Exception) { /* not important */ }
    }

    /** While on the cloud: if the board answers on Wi-Fi, switch to the faster local link. */
    private fun tryUpgradeToWifi() {
        val u = url
        if (u.isEmpty() || manual || wifi == null) return
        val host = try { URI(u).host } catch (e: Exception) { return } ?: return
        val client = httpFor(host).newBuilder().pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(900, TimeUnit.MILLISECONDS).callTimeout(1500, TimeUnit.MILLISECONDS).build()
        val ok = try {
            client.newCall(Request.Builder().url("http://$host/info").build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
        if (ok && via.value == Via.CLOUD && !manual) {
            mode = Via.WIFI
            open()
        }
    }

    /* ---------------- reconnect logic ---------------- */

    @Synchronized
    private fun lost() {
        closeTransports()
        if (manual) return
        // Wi-Fi failed but we know the cloud: switch right away, no waiting.
        if (mode == Via.WIFI && cloud != null && (!triedCloud || linked.value)) {
            mode = Via.CLOUD
            val my = ++gen
            link.value = if (linked.value) Link.RECONNECTING else Link.CONNECTING
            openCloud(my)
            return
        }
        if (!linked.value && !keepTrying) {
            link.value = Link.FAILED
            if (error.value.isEmpty()) error.value =
                if (cloud != null) "Could not reach the greenhouse on Wi-Fi or through the cloud. Is it powered and online?"
                else "Could not connect. Check the IP address and that you are on the greenhouse Wi-Fi."
            return
        }
        attempt.value = attempt.value + 1
        link.value = Link.RECONNECTING
        val wait = min(1000.0 * 1.6.pow(attempt.value - 1), 10_000.0).toLong()
        // Alternate: try Wi-Fi first each round when we have an IP, the cloud otherwise.
        mode = if (url.isNotEmpty()) Via.WIFI else Via.CLOUD
        triedCloud = false
        retryJob = scope.launch {
            delay(wait)
            open()
        }
    }

    /** Retry right away (network came back, app returned to foreground). */
    fun kick() {
        if (manual || address.isEmpty()) return
        if ((linked.value || keepTrying) && link.value != Link.ONLINE) {
            attempt.value = 0
            mode = if (url.isNotEmpty()) Via.WIFI else Via.CLOUD
            triedCloud = false
            open()
        } else if (link.value == Link.ONLINE && via.value == Via.CLOUD) {
            scope.launch { tryUpgradeToWifi() }
        }
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
        latest.value = null
        live.value = emptyList()
        activeAlerts.value = emptyMap()
        lastMsgAt = 0L
    }

    /* ---------------- commands ---------------- */

    /** Sends the phone's local time so the ESP32 learns the time zone for the light schedule. */
    private fun syncClock() {
        val now = System.currentTimeMillis()
        val local = (now + TimeZone.getDefault().getOffset(now)) / 1000
        send(JSONObject().put("device", "time").put("value", local).toString())
    }

    fun send(json: String): Boolean = when {
        link.value != Link.ONLINE -> false
        via.value == Via.CLOUD -> cloudSend(json)
        else -> ws?.send(json) == true
    }

    fun command(device: String, value: Boolean): Boolean {
        val ok = send(JSONObject().put("device", device).put("value", value).toString())
        if (ok && via.value == Via.CLOUD) optimistic(device, value) // instant feedback; the board confirms ~1 s later
        return ok
    }

    private fun optimistic(device: String, v: Boolean) {
        val r = latest.value ?: return
        latest.value = when (device) {
            "fan" -> r.copy(fan = v, auto = false)
            "pump" -> r.copy(pump = v, auto = false)
            "lights" -> r.copy(lights = v, auto = false)
            "heater" -> r.copy(heater = v, auto = false)
            "servo" -> r.copy(servo = v, auto = false)
            "auto" -> r.copy(auto = v)
            else -> r
        }
    }

    fun waterNow(seconds: Int): Boolean =
        send(JSONObject().put("device", "water").put("value", seconds).toString())

    fun allOff(): Boolean = send(JSONObject().put("device", "alloff").toString())

    /** New private cloud key on the board: old share links stop working. Wi-Fi only. */
    fun newCloudKey(): Boolean = via.value == Via.WIFI && send(JSONObject().put("device", "rekey").toString())

    fun rename(name: String): Boolean {
        val ok = send(JSONObject().put("device", "name").put("value", name).toString())
        if (ok) Prefs.saveGreenhouse(SavedGh(name, address, latest.value?.id ?: "", cloud?.code() ?: ""))
        return ok
    }

    /* ---------------- incoming readings ---------------- */

    private fun handle(text: String, producedAt: Long) {
        val r = Reading.parse(text)?.copy(ts = producedAt) ?: return
        lastMsgAt = producedAt
        val first = latest.value == null
        latest.value = r
        remember(r, first)
        live.value = (live.value + r).takeLast(90)

        val now = r.ts
        if (now - lastLogged >= Prefs.logEverySec * 1000L) {
            lastLogged = now
            History.add(r.toSample())
        }
        if (now - lastPrune > 3_600_000L) {
            lastPrune = now
            History.prune(now - 180L * 86_400_000L) // keep 6 months
        }

        val cur = r.alerts()
        val prev = activeAlerts.value
        cur.forEach { (key, msg) ->
            if (key !in prev) {
                History.logAlert(msg)
                Notifier.alert(app, msg)
            }
        }
        activeAlerts.value = cur
    }

    /**
     * Pairing: the board puts its cloud key in every packet, so the first Wi-Fi connection saves it
     * and from then on the greenhouse works from anywhere. Also follows the board to a new IP.
     */
    private fun remember(r: Reading, first: Boolean) {
        val ref = if (r.key.length >= 16 && r.db.isNotEmpty()) CloudRef(r.key, r.db) else null
        val keyChanged = ref != null && ref != cloud
        if (keyChanged && via.value == Via.WIFI) cloud = ref
        val curHost = try { URI(url).host ?: "" } catch (e: Exception) { "" }
        val ipChanged = via.value == Via.CLOUD && r.ip.isNotEmpty() && r.ip != "0.0.0.0" &&
            Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(curHost) && curHost != r.ip
        if (ipChanged) {                                   // DHCP gave the board a new address
            Prefs.greenhouses.firstOrNull { it.id == r.id && it.id.isNotEmpty() }?.let { Prefs.removeGreenhouse(it.addr) }
            url = buildUrl(r.ip); address = r.ip; Prefs.lastAddress = address
        }
        if (first || r.name != namedAs || keyChanged || ipChanged) {
            namedAs = r.name
            Prefs.saveGreenhouse(SavedGh(r.name, address, r.id, cloud?.code() ?: ""))
        }
    }
}
