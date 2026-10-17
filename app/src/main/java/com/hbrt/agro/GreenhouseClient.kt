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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

enum class Link { IDLE, CONNECTING, ONLINE, RECONNECTING, FAILED }

/**
 * Single WebSocket connection to the greenhouse, shared by the UI and the background service.
 *
 * Stability features:
 *  - OkHttp WebSocket pings every 5 s; a missing pong closes the socket.
 *  - Stale-data watchdog: no packet for 8 s means the link is dead, even if the socket looks open.
 *  - Automatic reconnect with back-off (1 s -> 10 s max).
 *  - Reconnects immediately when Wi-Fi or any network comes back.
 *  - Local IPs are forced over Wi-Fi, so it still works when the greenhouse Wi-Fi has no internet
 *    and Android would otherwise route traffic over mobile data.
 */
object GreenhouseClient {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    val link = MutableStateFlow(Link.IDLE)
    val linked = MutableStateFlow(false)
    val latest = MutableStateFlow<Reading?>(null)
    val live = MutableStateFlow<List<Reading>>(emptyList())
    val error = MutableStateFlow("")
    val attempt = MutableStateFlow(0)
    val activeAlerts = MutableStateFlow<Map<String, String>>(emptyMap())

    @Volatile var lastMsgAt = 0L
        private set
    @Volatile var url = ""
        private set

    /** The address the user typed or picked (IP or URL). */
    @Volatile var address = ""
        private set

    @Volatile private var wifi: Network? = null
    @Volatile private var wifiProps: LinkProperties? = null
    @Volatile private var gen = 0
    @Volatile private var manual = true
    @Volatile private var keepTrying = false
    private var ws: WebSocket? = null
    private var retryJob: Job? = null
    private var lastLogged = 0L
    @Volatile private var namedAs = ""
    private var lastPrune = 0L

    fun init(c: Context) {
        app = c.applicationContext

        scope.launch {
            while (isActive) {
                delay(3000)
                if (link.value == Link.ONLINE && System.currentTimeMillis() - lastMsgAt > 8000) {
                    ws?.cancel() // triggers onFailure -> reconnect
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

    /** @param persistent keep retrying even if the first attempt fails (used by the background service). */
    fun connect(input: String, persistent: Boolean = false) {
        url = try {
            buildUrl(input)
        } catch (e: Exception) {
            error.value = "That address is not valid."
            link.value = Link.FAILED
            return
        }
        address = input.trim()
        Prefs.lastAddress = address
        manual = false
        keepTrying = persistent
        linked.value = false
        attempt.value = 0
        error.value = ""
        open()
    }

    @Synchronized
    private fun open() {
        retryJob?.cancel()
        val my = ++gen
        ws?.cancel()
        ws = null
        link.value = if (linked.value) Link.RECONNECTING else Link.CONNECTING
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
                attempt.value = 0
                lastMsgAt = System.currentTimeMillis()
                error.value = ""
                linked.value = true
                Prefs.autoConnect = true
                link.value = Link.ONLINE
                syncClock(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (my == gen) handle(text)
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

    @Synchronized
    private fun lost() {
        ws = null
        if (manual) return
        if (!linked.value && !keepTrying) {
            link.value = Link.FAILED
            error.value = "Could not connect. Check the IP address, Wi-Fi or tunnel."
            return
        }
        attempt.value = attempt.value + 1
        link.value = Link.RECONNECTING
        val wait = min(1000.0 * 1.6.pow(attempt.value - 1), 10_000.0).toLong()
        retryJob = scope.launch {
            delay(wait)
            open()
        }
    }

    /** Retry right away (network came back, app returned to foreground). */
    fun kick() {
        if (manual || url.isEmpty()) return
        if ((linked.value || keepTrying) && link.value != Link.ONLINE) {
            attempt.value = 0
            open()
        }
    }

    fun disconnect() {
        manual = true
        keepTrying = false
        Prefs.autoConnect = false
        retryJob?.cancel()
        gen++
        ws?.close(1000, "bye")
        ws = null
        linked.value = false
        link.value = Link.IDLE
        latest.value = null
        live.value = emptyList()
        activeAlerts.value = emptyMap()
    }

    /** Sends the phone's local time so the ESP32 can run the light schedule. */
    private fun syncClock(w: WebSocket) {
        val now = System.currentTimeMillis()
        val local = (now + TimeZone.getDefault().getOffset(now)) / 1000
        w.send(JSONObject().put("device", "time").put("value", local).toString())
    }

    fun send(json: String): Boolean = link.value == Link.ONLINE && ws?.send(json) == true

    fun command(device: String, value: Boolean): Boolean =
        send(JSONObject().put("device", device).put("value", value).toString())

    fun waterNow(seconds: Int): Boolean =
        send(JSONObject().put("device", "water").put("value", seconds).toString())

    fun rename(name: String): Boolean {
        val ok = send(JSONObject().put("device", "name").put("value", name).toString())
        if (ok) Prefs.saveGreenhouse(SavedGh(name, address, latest.value?.id ?: ""))
        return ok
    }

    private fun handle(text: String) {
        lastMsgAt = System.currentTimeMillis()
        val r = Reading.parse(text) ?: return
        val first = latest.value == null
        latest.value = r
        if (first || r.name != namedAs) {           // remember this greenhouse under its own name
            namedAs = r.name
            Prefs.saveGreenhouse(SavedGh(r.name, address, r.id))
        }
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
}
