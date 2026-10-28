package com.hbrt.agro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* =================== Settings stored on the board =================== */

data class Slot(val m: Int, val s: Int, val d: Int = 127)
data class RuleC(val s: String = "S", val o: String = "<", val v: Float = 25f, val d: String = "pump", val on: Boolean = true)
data class Cal(val soilDry: Int = 4095, val soilWet: Int = 0, val waterEmpty: Int = 0, val waterFull: Int = 4095)

data class Cfg(
    val tempMax: Float = 35f, val tempMin: Float = 15f, val humMax: Float = 60f,
    val soilMin: Int = 30, val waterMin: Int = 5, val pumpMax: Int = 60,
    val lightSched: Boolean = false, val lightOn: Int = 360, val lightOff: Int = 1200,
    val flowMl: Int = 20, val tankL: Int = 20, val watt: Map<String, Int> = emptyMap(),
    val crop: String = "", val plantDay: Int = 0, val autoStage: Boolean = false,
    val lat: Double = 34.35, val lon: Double = 62.2, val rainSkip: Boolean = true, val frostAlert: Boolean = true,
    val quiet: Boolean = true, val quietFrom: Int = 1320, val quietTo: Int = 420, val alertMask: Int = 511,
    val dailySummary: Boolean = true, val lang: String = "en", val cal: Cal = Cal(),
    val sched: List<Slot> = emptyList(), val rules: List<RuleC> = emptyList(),
    val tgSet: Boolean = false, val tgChat: String = "",
) {
    companion object {
        fun parse(j: JSONObject?): Cfg? {
            if (j == null) return null
            val w = j.optJSONObject("watt")
            val c = j.optJSONObject("cal")
            val sa = j.optJSONArray("sched") ?: JSONArray()
            val ra = j.optJSONArray("rules") ?: JSONArray()
            return Cfg(
                j.optDouble("tempMax", 35.0).toFloat(), j.optDouble("tempMin", 15.0).toFloat(), j.optDouble("humMax", 60.0).toFloat(),
                j.optInt("soilMin", 30), j.optInt("waterMin", 5), j.optInt("pumpMax", 60),
                j.optBoolean("lightSched"), j.optInt("lightOn", 360), j.optInt("lightOff", 1200),
                j.optInt("flowMl", 20), j.optInt("tankL", 20),
                w?.keys()?.asSequence()?.associateWith { w.optInt(it) } ?: emptyMap(),
                j.optString("crop", ""), j.optInt("plantDay", 0), j.optBoolean("autoStage"),
                j.optDouble("lat", 34.35), j.optDouble("lon", 62.2), j.optBoolean("rainSkip", true), j.optBoolean("frostAlert", true),
                j.optBoolean("quiet", true), j.optInt("quietFrom", 1320), j.optInt("quietTo", 420), j.optInt("alertMask", 511),
                j.optBoolean("dailySummary", true), j.optString("lang", "en"),
                c?.let { Cal(it.optInt("soilDry", 4095), it.optInt("soilWet", 0), it.optInt("waterEmpty", 0), it.optInt("waterFull", 4095)) } ?: Cal(),
                List(sa.length()) { sa.getJSONObject(it).let { o -> Slot(o.optInt("m"), o.optInt("s"), o.optInt("d", 127)) } },
                List(ra.length()) { ra.getJSONObject(it).let { o -> RuleC(o.optString("s"), o.optString("o"), o.optDouble("v").toFloat(), o.optString("d"), o.optBoolean("on", true)) } },
                j.optBoolean("tgSet"), j.optString("tgChat", ""),
            )
        }
    }
}

/** Crop presets: min temp, max temp, max humidity, water below. Same numbers as the board. */
data class Preset(val id: String, val tempMin: Float, val tempMax: Float, val humMax: Float, val soilMin: Int, val base: Float, val gdd: Float)

val PRESETS = listOf(
    Preset("tomato", 18f, 30f, 75f, 40, 10f, 1250f),
    Preset("cucumber", 20f, 32f, 80f, 50, 10f, 850f),
    Preset("pepper", 20f, 30f, 70f, 40, 10f, 1300f),
    Preset("lettuce", 10f, 24f, 70f, 45, 4f, 900f),
    Preset("strawberry", 15f, 26f, 75f, 45, 7f, 1100f),
    Preset("herbs", 16f, 28f, 65f, 35, 10f, 700f),
)
val STAGES = listOf("seedling", "growing", "flowering", "fruiting", "harvest")
val DEVICES = listOf("fan", "pump", "lights", "heater", "servo")
val ALERTS = listOf("hot", "cold", "humid", "dry", "tank", "lock", "dht", "st", "frost")

/* =================== Live readings =================== */

data class Reading(
    val ts: Long,
    val name: String = "", val id: String = "", val fw: String = "",
    val temperature: Float = 0f, val humidity: Float = 0f, val soil: Int = 0, val water: Int = 0, val soilTemp: Float = 0f,
    val fan: Boolean = false, val pump: Boolean = false, val lights: Boolean = false, val heater: Boolean = false, val servo: Boolean = false,
    val mode: String = "auto", val ovr: Map<String, Int> = emptyMap(),
    val rssi: Int = 0, val uptime: Long = 0, val pumpLock: Boolean = false, val dhtOk: Boolean = true, val stOk: Boolean = true,
    val timeOk: Boolean = false, val watering: Int = 0, val ip: String = "",
    val al: List<String>? = null, val stage: String = "", val gdd: Float = 0f, val gddH: Float = 0f, val day: Int = -1,
    val db: String = "", val key: String = "", val view: String = "", val cloud: Boolean = false, val ro: Boolean = false,
    val rawS: Int = -1, val rawW: Int = -1, val heap: Int = 0, val resetReason: Int = 0, val drops: Int = 0, val cloudErrors: Int = 0,
    val ota: String = "", val tg: String = "off", val portal: Boolean = false, val ssid: String = "",
    /** Limits inside the packet (firmware 4.x / 5.x). */
    val legacyCfg: Cfg? = null,
) {
    val auto get() = mode != "manual"
    val modern get() = id.isNotEmpty()
    val v6 get() = fw.substringBefore('.').toIntOrNull()?.let { it >= 6 } == true

    fun on(d: String) = when (d) { "fan" -> fan; "pump" -> pump; "lights" -> lights; "heater" -> heater; else -> servo }

    /** Active problems: board's own list (firmware 6) or worked out here for older boards. */
    fun alerts(c: Cfg?): List<String> {
        al?.let { return it }
        val l = c ?: legacyCfg ?: Cfg()
        val m = mutableListOf<String>()
        if (dhtOk && temperature > l.tempMax) m += "hot"
        if (dhtOk && temperature < l.tempMin) m += "cold"
        if (dhtOk && humidity > l.humMax) m += "humid"
        if (soil < l.soilMin) m += "dry"
        if (water <= l.waterMin) m += "tank"
        if (pumpLock) m += "lock"
        if (!dhtOk) m += "dht"
        if (!stOk) m += "st"
        return m
    }

    /** 0–100: how comfortable the plants are right now. */
    fun health(c: Cfg?): Int {
        val l = c ?: legacyCfg ?: Cfg()
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

    fun toSample() = Sample(ts, temperature, humidity, soil, soilTemp, water, fan, pump, lights, heater, servo)

    companion object {
        fun parse(j: JSONObject, ts: Long): Reading? {
            if (!j.has("temperature")) return null
            val ovr = j.optJSONObject("ovr")
            val al = j.optJSONArray("al")
            val raw = j.optJSONObject("raw")
            val hp = j.optJSONObject("hp")
            return Reading(
                ts, j.optString("name"), j.optString("id"), j.optString("fw", "3.x"),
                j.optDouble("temperature", 0.0).toFloat(), j.optDouble("humidity", 0.0).toFloat(), j.optInt("soil"), j.optInt("water"),
                j.optDouble("soilTemp", 0.0).toFloat(),
                j.optBoolean("fan"), j.optBoolean("pump"), j.optBoolean("lights"), j.optBoolean("heater"), j.optBoolean("servo"),
                j.optString("mode", if (j.optBoolean("auto", true)) "auto" else "manual"),
                ovr?.keys()?.asSequence()?.associateWith { ovr.optInt(it) } ?: emptyMap(),
                j.optInt("rssi"), j.optLong("uptime"), j.optBoolean("pumpLock"), j.optBoolean("dhtOk", true), j.optBoolean("stOk", true),
                j.optBoolean("timeOk"), j.optInt("watering"), j.optString("ip"),
                al?.let { a -> List(a.length()) { a.getString(it) } },
                j.optString("stage"), j.optDouble("gdd", 0.0).toFloat(), j.optDouble("gddH", 0.0).toFloat(), j.optInt("day", -1),
                j.optString("db"), j.optString("key"), j.optString("view"), j.optBoolean("cloud"), j.optBoolean("ro"),
                raw?.optInt("s", -1) ?: -1, raw?.optInt("w", -1) ?: -1,
                hp?.optInt("heap") ?: 0, hp?.optInt("rr") ?: 0, hp?.optInt("drops") ?: 0, hp?.optInt("cerr") ?: 0,
                j.optString("ota"), j.optString("tg", "off"), j.optBoolean("portal"), j.optString("ssid"),
                Cfg.parse(j.optJSONObject("cfg")),
            )
        }
    }
}

data class WxDay(val max: Float, val min: Float, val pp: Float, val ps: Float, val code: Int) {
    val rain get() = ps >= 1f || pp >= 60f
}
data class Wx(val t: Long, val now: Float, val code: Int, val days: List<WxDay>, val rain: Boolean, val frost: Boolean) {
    companion object {
        fun parse(j: JSONObject?): Wx? {
            val d = j?.optJSONArray("d") ?: return null
            return Wx(
                j.optLong("t"), j.optDouble("now").toFloat(), j.optInt("code"),
                List(d.length()) { d.getJSONObject(it).let { o -> WxDay(o.optDouble("max").toFloat(), o.optDouble("min").toFloat(), o.optDouble("pp").toFloat(), o.optDouble("ps").toFloat(), o.optInt("code")) } },
                j.optBoolean("rain"), j.optBoolean("frost"),
            )
        }
    }
}
/** WMO weather code -> 0 clear, 1 partly cloudy, 2 cloudy, 3 fog, 4 drizzle, 5 rain, 6 snow, 7 showers, 8 storm */
fun wxKind(c: Int) = when {
    c == 0 -> 0; c <= 2 -> 1; c == 3 -> 2; c <= 48 -> 3; c <= 57 -> 4; c <= 67 -> 5; c <= 77 -> 6; c <= 82 -> 7; c <= 86 -> 6; else -> 8
}

data class UseDay(val d: Int, val l: Float, val kwh: Float)
data class Use(val todayL: Float, val todayKwh: Float, val today: Int, val days: List<UseDay>) {
    companion object {
        fun parse(j: JSONObject?): Use? {
            if (j == null) return null
            val t = j.optJSONObject("today")
            val d = j.optJSONArray("days") ?: JSONArray()
            return Use(
                t?.optDouble("L", 0.0)?.toFloat() ?: 0f, t?.optDouble("kWh", 0.0)?.toFloat() ?: 0f, t?.optInt("d") ?: 0,
                List(d.length()) { d.getJSONObject(it).let { o -> UseDay(o.optInt("d"), o.optDouble("L").toFloat(), o.optDouble("kWh").toFloat()) } },
            )
        }
    }
}

/** One timeline entry from the board. */
data class Ev(
    val n: Long, val t: Long, val c: String, val d: String, val by: String, val w: String,
    val a: String, val m: String, val st: String, val v: Int, val x: Float,
) {
    companion object {
        fun parse(o: JSONObject) = Ev(
            o.optLong("n"), o.optLong("t"), o.optString("c"), o.optString("d"), o.optString("by", "system"), o.optString("w"),
            o.optString("a"), o.optString("m"), o.optString("st"), o.optInt("v"), o.optDouble("x", 0.0).toFloat(),
        )
    }
}

/* =================== Growth =================== */

data class Growth(val crop: String, val stage: String, val day: Int, val frac: Float, val totalDays: Int, val harvestAt: Long, val gdd: Float, val gddH: Float)

fun growth(r: Reading, c: Cfg?): Growth? {
    val p = PRESETS.firstOrNull { it.id == c?.crop } ?: return null
    val gH = if (r.gddH > 0) r.gddH else p.gdd
    val day = max(0, r.day)
    val rate = if (day > 3 && r.gdd > 0) r.gdd / day else 12f
    val left = max(0f, (gH - r.gdd) / max(1f, rate))
    return Growth(p.id, r.stage.ifBlank { "seedling" }, day, (r.gdd / gH).coerceIn(0f, 1f), (day + left).roundToInt(),
        System.currentTimeMillis() + (left * 86_400_000L).toLong(), r.gdd, gH)
}

/* =================== Climate maths =================== */

/** Vapour pressure deficit in kPa: how strongly the air pulls water from the leaves. */
fun vpd(t: Float, rh: Float): Float = (0.6108 * exp(17.27 * t / (t + 237.3)) * (1 - rh / 100.0)).toFloat()
fun dewPoint(t: Float, rh: Float): Float {
    val g = ln(max(1f, rh) / 100.0) + 17.62 * t / (243.12 + t)
    return (243.12 * g / (17.62 - g)).toFloat()
}
fun vpdBand(stage: String) = when (stage) {
    "seedling" -> 0.4f to 0.8f
    "growing" -> 0.8f to 1.2f
    "flowering", "fruiting" -> 1.0f to 1.5f
    else -> 0.8f to 1.2f
}

/* =================== Phone history =================== */

data class Sample(
    val ts: Long, val t: Float, val h: Float, val s: Int, val st: Float, val w: Int,
    val fan: Boolean, val pump: Boolean, val lights: Boolean, val heater: Boolean, val vent: Boolean,
)

data class AlertEntry(val ts: Long, val msg: String)
data class Stat3(val min: Float, val avg: Float, val max: Float)

fun List<Float>.stat(): Stat3? = if (isEmpty()) null else Stat3(min(), average().toFloat(), max())

fun <T> List<T>.downsample(max: Int): List<T> =
    if (size <= max) this else {
        val step = size.toDouble() / max
        List(max) { this[(it * step).toInt()] }
    }

fun fmtMinutes(m: Int): String = String.format(Locale.US, "%02d:%02d", (m / 60) % 24, m % 60)
fun Float.f1(): String = String.format(Locale.US, "%.1f", this)
fun Float.f0(): String = String.format(Locale.US, "%.0f", this)
fun Float.fx(d: Int): String = String.format(Locale.US, "%.${d}f", this)

/* =================== Saved greenhouses + preferences =================== */

/** A greenhouse the user has saved. [cloud] = "key@db" once paired (firmware 5.0+). */
data class SavedGh(val name: String, val addr: String, val id: String = "", val cloud: String = "") {
    val hasCloud get() = cloud.isNotEmpty()
    val cloudOnly get() = addr.startsWith("cloud:")
    val viewOnly get() = cloud.startsWith("gv") || addr.startsWith("cloud:gv")
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("hbrt", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d

    var lastAddress: String
        get() = str("addr", "")
        set(v) = sp.edit().putString("addr", v).apply()
    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", false)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()
    var aiUrl: String
        get() = str("ai_url", "http://192.168.1.100:11434/v1/chat/completions")
        set(v) = sp.edit().putString("ai_url", v).apply()
    var aiModel: String
        get() = str("ai_model", "qwen2.5:1.5b")
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
    /** "system", "en", "fa", "ps" */
    var lang: String
        get() = str("lang", "system")
        set(v) = sp.edit().putString("lang", v).commit().let { }
    /** "system", "light", "dark" */
    var theme: String
        get() = str("theme", "system")
        set(v) = sp.edit().putString("theme", v).apply()
    /** Last reading, for the home screen widget. */
    var widgetJson: String
        get() = str("widget", "")
        set(v) = sp.edit().putString("widget", v).apply()

    var greenhouses: List<SavedGh>
        get() = try {
            val a = JSONArray(str("greenhouses", "[]"))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                SavedGh(o.optString("name"), o.optString("addr"), o.optString("id"), o.optString("cloud"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("addr", it.addr).put("id", it.id).put("cloud", it.cloud)) }
            sp.edit().putString("greenhouses", a.toString()).apply()
        }

    /** Adds or updates a greenhouse (matched by board id, then cloud key, then address). */
    fun saveGreenhouse(g: SavedGh) {
        val list = greenhouses.toMutableList()
        val i = list.indexOfFirst {
            (g.id.isNotEmpty() && it.id == g.id && it.viewOnly == g.viewOnly) || (g.cloud.isNotEmpty() && it.cloud == g.cloud) || it.addr.equals(g.addr, true)
        }
        if (i >= 0) {
            val old = list[i]
            // never downgrade a Wi-Fi address to cloud-only: keep the IP so local stays fastest
            val addr = if (g.addr.startsWith("cloud:") && !old.addr.startsWith("cloud:")) old.addr else g.addr
            list[i] = SavedGh(g.name.ifBlank { old.name }, addr, g.id.ifBlank { old.id }, g.cloud.ifBlank { old.cloud })
        } else list += g
        greenhouses = list
    }

    fun removeGreenhouse(addr: String) {
        greenhouses = greenhouses.filterNot { it.addr.equals(addr, true) }
    }
}
