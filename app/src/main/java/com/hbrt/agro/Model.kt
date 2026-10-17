package com.hbrt.agro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale

/** Limits stored on the ESP32 and editable from the app. */
data class Limits(
    val tempMax: Float = 35f,
    val tempMin: Float = 15f,
    val humMax: Float = 60f,
    val soilMin: Int = 30,
    val waterMin: Int = 5,
    val pumpMax: Int = 60,
) {
    fun toJson(): String = JSONObject()
        .put("device", "config")
        .put("tempMax", tempMax.toDouble())
        .put("tempMin", tempMin.toDouble())
        .put("humMax", humMax.toDouble())
        .put("soilMin", soilMin)
        .put("waterMin", waterMin)
        .put("pumpMax", pumpMax)
        .toString()
}

/** One live packet from the ESP32 (sent every 2 s). */
data class Reading(
    val ts: Long,
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
    val limits: Limits?,
) {
    companion object {
        fun parse(text: String): Reading? = try {
            val j = JSONObject(text)
            if (!j.has("temperature")) null else {
                val c = j.optJSONObject("cfg")
                Reading(
                    ts = System.currentTimeMillis(),
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
                    limits = c?.let {
                        Limits(
                            it.optDouble("tempMax", 35.0).toFloat(),
                            it.optDouble("tempMin", 15.0).toFloat(),
                            it.optDouble("humMax", 60.0).toFloat(),
                            it.optInt("soilMin", 30),
                            it.optInt("waterMin", 5),
                            it.optInt("pumpMax", 60),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Active problems, keyed so each one is notified only once while it lasts. */
    fun alerts(): Map<String, String> {
        val l = limits ?: Limits()
        val m = linkedMapOf<String, String>()
        if (dhtOk && temperature > l.tempMax) m["hot"] = "High temperature %.1f°C".format(temperature)
        if (dhtOk && temperature < l.tempMin) m["cold"] = "Low temperature %.1f°C".format(temperature)
        if (dhtOk && humidity > l.humMax) m["hum"] = "High humidity %.0f%%".format(humidity)
        if (soil < l.soilMin) m["soil"] = "Soil is dry ($soil%)"
        if (water <= l.waterMin) m["tank"] = "Water tank is empty"
        if (pumpLock) m["lock"] = "Pump locked after running too long"
        if (!dhtOk) m["dht"] = "Air sensor (DHT) not responding"
        if (!stOk) m["st"] = "Soil temperature sensor not responding"
        return m
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

fun Float.f1(): String = String.format(Locale.US, "%.1f", this)

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

    /** How often a reading is written to the history database. */
    var logEverySec: Int
        get() = sp.getInt("log_every", 10)
        set(v) = sp.edit().putInt("log_every", v).apply()
}
