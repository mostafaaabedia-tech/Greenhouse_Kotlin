package com.hbrt.agro

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

/**
 * Night Garden (dark) and daylight palettes. The colours are read through [Pal.dark], a snapshot
 * state, so every screen and every Canvas redraws by itself when the theme changes.
 */
object Pal {
    var dark by mutableStateOf(true)
}

private fun pick(dark: Long, light: Long) = Color(if (Pal.dark) dark else light)

val Bg: Color get() = pick(0xFF08130E, 0xFFF2F6F3)
val CardC: Color get() = pick(0xFF0F1E17, 0xFFFFFFFF)
val CardHi: Color get() = pick(0xFF13261D, 0xFFEEF4F0)
val Border: Color get() = pick(0xFF1E3329, 0xFFDCE7E0)
val Track: Color get() = pick(0xFF1B2E24, 0xFFE2EBE5)
val DarkC: Color get() = pick(0xFF050C08, 0xFFFFFFFF)
val TextC: Color get() = pick(0xFFE8F3EC, 0xFF10241A)
val Muted: Color get() = pick(0xFF93AC9E, 0xFF566F62)
val Neon: Color get() = pick(0xFF39FF14, 0xFF1F9D3A)          // brand
val OnNeon: Color get() = pick(0xFF062B00, 0xFFFFFFFF)
val Leaf: Color get() = pick(0xFF2E8B57, 0xFF2E8B57)
val Orange: Color get() = pick(0xFFFF7A5C, 0xFFDF4F33)        // temperature, heater
val Cyan: Color get() = pick(0xFF3FD8F0, 0xFF0B93AA)          // humidity, fan
val Lime: Color get() = pick(0xFFB6F23A, 0xFF5B9A10)          // soil
val Blue: Color get() = pick(0xFF5B8CFF, 0xFF3366E0)          // water, pump
val Gold: Color get() = pick(0xFFFFC247, 0xFFC98A00)          // lights
val Violet: Color get() = pick(0xFFA88BFF, 0xFF6A55D8)        // vent
val Danger: Color get() = pick(0xFFFF6B6B, 0xFFCF3535)
val Warn: Color get() = pick(0xFFFFB347, 0xFFC77700)

/** Colour of each device, same as the website. */
fun devColor(d: String): Color = when (d) {
    "fan" -> Cyan
    "pump" -> Blue
    "lights" -> Gold
    "heater" -> Orange
    else -> Violet
}

/* ---------------- fonts ---------------- */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun vf(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val UiFont = FontFamily(vf(R.font.space_grotesk, 400), vf(R.font.space_grotesk, 500), vf(R.font.space_grotesk, 600), vf(R.font.space_grotesk, 700))
val NumFont = FontFamily(vf(R.font.oswald, 400), vf(R.font.oswald, 500), vf(R.font.oswald, 600))
val RtlFont = FontFamily(vf(R.font.vazirmatn, 400), vf(R.font.vazirmatn, 500), vf(R.font.vazirmatn, 600), vf(R.font.vazirmatn, 700))

/** Text font for the current language (Vazirmatn for Dari and Pashto). */
val BodyFont: FontFamily get() = if (Lang.rtl) RtlFont else UiFont
val Tnum = TextStyle(fontFeatureSettings = "tnum", fontFamily = NumFont)

/* ---------------- language ---------------- */
object Lang {
    /** "system", "en", "fa" (Dari) or "ps" (Pashto). */
    fun code(): String = Prefs.lang.let { if (it == "system") Locale.getDefault().language.let { l -> if (l == "fa" || l == "ps") l else "en" } else it }
    val rtl: Boolean get() = code() == "fa" || code() == "ps"

    /** A context whose resources speak the chosen language. */
    fun wrap(base: Context): Context {
        val pref = try { Prefs.lang } catch (e: Exception) { "system" }
        if (pref == "system") return base
        val loc = Locale(pref, if (pref == "en") "" else "AF")
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg) ?: base
    }

    /** Dates in the user's language with Latin digits (the Afghan calendar for Dari and Pashto). */
    fun locale(): Locale = when (code()) {
        "fa" -> if (Build.VERSION.SDK_INT >= 24) Locale.forLanguageTag("fa-AF-u-nu-latn") else Locale("fa", "AF")
        "ps" -> if (Build.VERSION.SDK_INT >= 24) Locale.forLanguageTag("ps-AF-u-nu-latn") else Locale("ps", "AF")
        else -> Locale.UK
    }
}
