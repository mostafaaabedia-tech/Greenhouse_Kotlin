package com.hbrt.agro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Limits stored on the ESP32 and editable from the app. */
data class Limits(
    val tempMax: Float = 35f,
    val tempMin: Float = 15f,
    val humMax: Float = 60f,
    val soilMin: Int = 30,
    val waterMin: Int = 5,
    val pumpMax: Int = 60,
    val lightSched: Boolean = false,
    val lightOn: Int = 6 * 60,
    val lightOff: Int = 20 * 60,
) {
    fun toJson(): String = JSONObject()
        .put("device", "config")
        .put("tempMax", tempMax.toDouble())
        .put("tempMin", tempMin.toDouble())
        .put("humMax", humMax.toDouble())
        .put("soilMin", soilMin)
        .put("waterMin", waterMin)
        .put("pumpMax", pumpMax)
        .put("lightSched", lightSched)
        .put("lightOn", lightOn)
        .put("lightOff", lightOff)
        .toString()
}

/** Crop presets: fill in the limits with one tap. */
data class Preset(val name: String, val tempMin: Float, val tempMax: Float, val humMax: Float, val soilMin: Int)

val PRESETS = listOf(
    Preset("Tomato", 18f, 30f, 75f, 40),
    Preset("Cucumber", 20f, 32f, 80f, 50),
    Preset("Pepper", 20f, 30f, 70f, 40),
    Preset("Lettuce", 10f, 24f, 70f, 45),
    Preset("Strawberry", 15f, 26f, 75f, 45),
    Preset("Herbs", 16f, 28f, 65f, 35),
)

/** One live packet from the ESP32 (sent every 2 s). */
data class Reading(
    val ts: Long,
    val name: String,
    val id: String,
    val fw: String,
    val temperature: Float,
    val humidity: Float,
    val soil: Int,
    val water: Int,
    val soilTemp: Float,
    val fan: Boolean,
    val pump: Boolean,
    val lights: Boolean,
    val heater: Boolean,
    val servo: Boolean,
    val auto: Boolean,
    val rssi: Int,
    val uptime: Long,
    val pumpLock: Boolean,
    val dhtOk: Boolean,
    val stOk: Boolean,
    val timeOk: Boolean,
    val watering: Int,
    val limits: Limits?,
) {
    companion object {
        fun parse(text: String): Reading? = try {
            val j = JSONObject(text)
            if (!j.has("temperature")) null else {
                val c = j.optJSONObject("cfg")
                Reading(
                    ts = System.currentTimeMillis(),
                    name = j.optString("name", ""),
                    id = j.optString("id", ""),
                    fw = j.optString("fw", "3.x"),
                    temperature = j.optDouble("temperature", 0.0).toFloat(),
                    humidity = j.optDouble("humidity", 0.0).toFloat(),
                    soil = j.optInt("soil"),
                    water = j.optInt("water"),
                    soilTemp = j.optDouble("soilTemp", 0.0).toFloat(),
                    fan = j.optBoolean("fan"),
                    pump = j.optBoolean("pump"),
                    lights = j.optBoolean("lights"),
                    heater = j.optBoolean("heater"),
                    servo = j.optBoolean("servo"),
                    auto = j.optBoolean("auto", true),
                    rssi = j.optInt("rssi"),
                    uptime = j.optLong("uptime"),
                    pumpLock = j.optBoolean("pumpLock"),
                    dhtOk = j.optBoolean("dhtOk", true),
                    stOk = j.optBoolean("stOk", true),
                    timeOk = j.optBoolean("timeOk", false),
                    watering = j.optInt("watering", 0),
                    limits = c?.let {
                        Limits(
                            it.optDouble("tempMax", 35.0).toFloat(),
                            it.optDouble("tempMin", 15.0).toFloat(),
                            it.optDouble("humMax", 60.0).toFloat(),
                            it.optInt("soilMin", 30),
                            it.optInt("waterMin", 5),
                            it.optInt("pumpMax", 60),
                            it.optBoolean("lightSched", false),
                            it.optInt("lightOn", 360),
                            it.optInt("lightOff", 1200),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** New firmware (4.0+) supports names, water-now and the light schedule. */
    val modern: Boolean get() = id.isNotEmpty()

    /** Active problems, keyed so each one is notified only once while it lasts. */
    fun alerts(): Map<String, String> {
        val l = limits ?: Limits()
        val m = linkedMapOf<String, String>()
        if (dhtOk && temperature > l.tempMax) m["hot"] = "Too hot: ${temperature.f1()}°C"
        if (dhtOk && temperature < l.tempMin) m["cold"] = "Too cold: ${temperature.f1()}°C"
        if (dhtOk && humidity > l.humMax) m["hum"] = "Humidity is high: ${humidity.roundToInt()}%"
        if (soil < l.soilMin) m["soil"] = "Soil is dry: $soil%"
        if (water <= l.waterMin) m["tank"] = "Water tank is empty"
        if (pumpLock) m["lock"] = "Pump is resting after a long run"
        if (!dhtOk) m["dht"] = "Air sensor is not responding"
        if (!stOk) m["st"] = "Soil temperature sensor is not responding"
        return m
    }

    /** 0–100: how comfortable the plants are right now. */
    fun health(): Int {
        val l = limits ?: Limits()
        fun off(v: Float, lo: Float, hi: Float, span: Float) = when {
            v < lo -> min(1f, (lo - v) / span)
            v > hi -> min(1f, (v - hi) / span)
            else -> 0f
        }
        var s = 100f
        if (dhtOk) {
            s -= 30 * off(temperature, l.tempMin, l.tempMax, 8f)
            s -= 15 * off(humidity, 30f, l.humMax, 20f)
        } else s -= 25
        s -= 35 * off(soil.toFloat(), l.soilMin.toFloat(), 100f, 20f)
        s -= 15 * off(water.toFloat(), l.waterMin + 10f, 100f, 15f)
        if (!stOk) s -= 5
        return s.coerceIn(0f, 100f).roundToInt()
    }

    /** One plain sentence for the dashboard. */
    fun summary(): String = when {
        watering > 0 -> "Watering now, $watering s left"
        alerts().isNotEmpty() -> alerts().values.first()
        pump -> "Watering the plants"
        fan && servo -> "Cooling down: fan on, vent open"
        heater -> "Warming up the greenhouse"
        else -> "Everything looks good"
    }

    fun toSample() = Sample(ts, temperature, humidity, soil, soilTemp, water, fan, pump, lights, heater, servo)
}

/** One row of logged history. */
data class Sample(
    val ts: Long,
    val t: Float,
    val h: Float,
    val s: Int,
    val st: Float,
    val w: Int,
    val fan: Boolean,
    val pump: Boolean,
    val lights: Boolean,
    val heater: Boolean,
    val vent: Boolean,
)

data class AlertEntry(val ts: Long, val msg: String)

data class Stat3(val min: Float, val avg: Float, val max: Float)

fun List<Float>.stat(): Stat3? = if (isEmpty()) null else Stat3(min(), average().toFloat(), max())

fun <T> List<T>.downsample(max: Int): List<T> =
    if (size <= max) this else {
        val step = size.toDouble() / max
        List(max) { this[(it * step).toInt()] }
    }

fun fmtUptime(sec: Long): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h ${m}m"
}

fun fmtMinutes(m: Int): String = String.format(Locale.US, "%02d:%02d", (m / 60) % 24, m % 60)

fun Float.f1(): String = String.format(Locale.US, "%.1f", this)

/** A greenhouse the user has saved. */
data class SavedGh(val name: String, val addr: String, val id: String = "")

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("hbrt", Context.MODE_PRIVATE)
    }

    var lastAddress: String
        get() = sp.getString("addr", "") ?: ""
        set(v) = sp.edit().putString("addr", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", false)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()

    var aiUrl: String
        get() = sp.getString("ai_url", "http://192.168.1.100:11434/v1/chat/completions") ?: ""
        set(v) = sp.edit().putString("ai_url", v).apply()

    var aiModel: String
        get() = sp.getString("ai_model", "qwen2.5:1.5b") ?: ""
        set(v) = sp.edit().putString("ai_model", v).apply()

    var background: Boolean
        get() = sp.getBoolean("background", true)
        set(v) = sp.edit().putBoolean("background", v).apply()

    var dailyReport: Boolean
        get() = sp.getBoolean("daily_report", false)
        set(v) = sp.edit().putBoolean("daily_report", v).apply()

    var logEverySec: Int
        get() = sp.getInt("log_every", 10)
        set(v) = sp.edit().putInt("log_every", v).apply()

    var greenhouses: List<SavedGh>
        get() = try {
            val a = JSONArray(sp.getString("greenhouses", "[]"))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                SavedGh(o.optString("name"), o.optString("addr"), o.optString("id"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("addr", it.addr).put("id", it.id)) }
            sp.edit().putString("greenhouses", a.toString()).apply()
        }

    /** Adds or updates a greenhouse (matched by board id, then by address). */
    fun saveGreenhouse(g: SavedGh) {
        val list = greenhouses.toMutableList()
        val i = list.indexOfFirst { (g.id.isNotEmpty() && it.id == g.id) || it.addr.equals(g.addr, true) }
        if (i >= 0) {
            val old = list[i]
            list[i] = SavedGh(g.name.ifBlank { old.name }, g.addr, g.id.ifBlank { old.id })
        } else list += g
        greenhouses = list
    }

    fun removeGreenhouse(addr: String) {
        greenhouses = greenhouses.filterNot { it.addr.equals(addr, true) }
    }
}
