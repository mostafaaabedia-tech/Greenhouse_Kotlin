package com.hbrt.agro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Found(val name: String, val ip: String, val id: String, val fw: String)

/**
 * Finds HBRT boards on every local network the phone is part of: the Wi-Fi it is connected to
 * AND its own hotspot (useful when the ESP32 joins the phone's hotspot).
 * Each address is asked for http://<ip>/info (firmware 4.0+); older firmware is recognised by its "/" page.
 */
object Discovery {
    private val skip = listOf("lo", "rmnet", "ccmni", "dummy", "tun", "v4-", "ppp", "ipsec", "clat")

    /** e.g. ["192.168.1.", "192.168.43."] with the phone's own address in each. */
    fun subnets(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && skip.none { s -> it.name.startsWith(s) } }
            .flatMap { ni -> ni.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { a ->
                val ip = a.hostAddress ?: ""
                ip.substringBeforeLast('.') + "." to ip
            }
            .distinctBy { it.first }
    } catch (e: Exception) {
        emptyList()
    }

    private fun probe(ip: String): Found? {
        val client = GreenhouseClient.httpFor(ip).newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(Request.Builder().url("http://$ip/info").build()).execute().use { r ->
                if (r.isSuccessful) {
                    val j = JSONObject(r.body?.string() ?: "")
                    if (j.optString("app") == "hbrt") {
                        return Found(j.optString("name", "Greenhouse"), ip, j.optString("id"), j.optString("fw"))
                    }
                }
            }
        } catch (e: Exception) {
            // not an HBRT board, or nothing there
        }
        return try {
            client.newCall(Request.Builder().url("http://$ip/").build()).execute().use { r ->
                if (r.isSuccessful && (r.body?.string() ?: "").contains("HBRT", true))
                    Found("HBRT Greenhouse (old firmware)", ip, "", "3.x") else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans all local subnets. [onProgress] gets 0..1, [onFound] is called for each board.
     * Takes about 5–10 seconds per network.
     */
    suspend fun scan(onProgress: (Float) -> Unit, onFound: (Found) -> Unit) = withContext(Dispatchers.IO) {
        val nets = subnets()
        if (nets.isEmpty()) { onProgress(1f); return@withContext }
        val targets = nets.flatMap { (prefix, mine) -> (1..254).map { "$prefix$it" }.filter { it != mine } }
        val done = AtomicInteger(0)
        val gate = Semaphore(48)
        coroutineScope {
            targets.forEach { ip ->
                launch {
                    gate.withPermit {
                        probe(ip)?.let { f -> withContext(Dispatchers.Main) { onFound(f) } }
                    }
                    val d = done.incrementAndGet()
                    if (d % 8 == 0 || d == targets.size) withContext(Dispatchers.Main) { onProgress(d.toFloat() / targets.size) }
                }
            }
        }
    }
}
