package com.hbrt.agro

import android.content.Context
import android.net.ConnectivityManager
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
import java.net.URI
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

    @Volatile private var wifi: Network? = null
    @Volatile private var gen = 0
    @Volatile private var manual = true
    @Volatile private var keepTrying = false
    private var ws: WebSocket? = null
    private var retryJob: Job? = null
    private var lastLogged = 0L
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

            override fun onLost(network: Network) {
                if (wifi == network) wifi = null
            }
        })
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = kick()
        })
    }

    private fun isLocal(host: String) =
        host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)

    /** HTTP client that sends local-network traffic over Wi-Fi. Also used by the AI client. */
    fun httpFor(host: String): OkHttpClient {
        val w = wifi
        return if (w != null && isLocal(host)) http.newBuilder().socketFactory(w.socketFactory).build() else http
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
        Prefs.lastAddress = input.trim()
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

    fun send(json: String): Boolean = link.value == Link.ONLINE && ws?.send(json) == true

    fun command(device: String, value: Boolean): Boolean =
        send(JSONObject().put("device", device).put("value", value).toString())

    private fun handle(text: String) {
        lastMsgAt = System.currentTimeMillis()
        val r = Reading.parse(text) ?: return
        latest.value = r
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
