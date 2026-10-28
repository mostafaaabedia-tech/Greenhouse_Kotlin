package com.hbrt.agro

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** Latest firmware version published on GitHub (asked once per app run). */
private object FwLatest { var v by mutableStateOf("") ; var asked = false }

@Composable
fun SettingsScreen(r: Reading?, onLang: () -> Unit) {
    val ctx = LocalContext.current
    val cfgFlow by GreenhouseClient.cfg.collectAsStateWithLifecycle()
    val ro by GreenhouseClient.readOnly.collectAsStateWithLifecycle()
    val via by GreenhouseClient.via.collectAsStateWithLifecycle()
    val l = cfgFlow ?: r?.legacyCfg ?: Cfg()
    val v6 = r?.v6 == true
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val pos = remember { mutableStateMapOf<String, Int>() }
    val saved = stringResource(R.string.saved)
    val notSent = stringResource(R.string.not_sent)
    fun send(j: JSONObject) = toast(ctx, if (GreenhouseClient.config(j)) saved else notSent)

    // section list (id, title) drives the quick nav at the top
    val sections = buildList {
        if (!ro) {
            add("name" to R.string.s_name); add("crop" to R.string.s_crop); add("limits" to R.string.s_limits); add("lights" to R.string.s_lights)
            if (v6) { add("times" to R.string.s_water_times); add("rules" to R.string.s_rules); add("alerts" to R.string.s_alerts); add("tg" to R.string.s_tg)
                add("usage" to R.string.s_usage); add("loc" to R.string.s_location); add("cal" to R.string.s_cal) }
        }
        add("share" to R.string.s_share)
        if (!ro) add("board" to R.string.s_board)
        add("app" to R.string.a_phone); add("look" to R.string.s_look)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            PageTitle(stringResource(R.string.s_title))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { (id, n) ->
                    Text(stringResource(n), Modifier.clip(CircleShape).background(CardHi).border(1.dp, Border, CircleShape)
                        .bounceClick { pos[id]?.let { y -> scope.launch { scroll.animateScrollTo(y) } } }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (r != null && !v6 && !ro) Notice(stringResource(R.string.a_needs_fw6), Orange)
            @Composable fun Sec(id: String, title: Int, sub: String? = null, content: @Composable ColumnScope.() -> Unit) =
                Box(Modifier.onGloballyPositioned { pos[id] = it.positionInParent().y.toInt() }) { SectionCard(stringResource(title), sub, content) }

            if (!ro) {
                Sec("name", R.string.s_name, if (r?.modern == true) null else stringResource(R.string.a_name_phone_only)) { NameSection(r) }
                Sec("crop", R.string.s_crop) { CropSection(l, v6, ::send) }
                Sec("limits", R.string.s_limits, stringResource(R.string.limits_sub)) { LimitsSection(l, ::send) }
                Sec("lights", R.string.s_lights) { LightsSection(l, ::send) }
                if (v6) {
                    Sec("times", R.string.s_water_times, stringResource(R.string.wt_sub)) { TimesSection(l, ::send) }
                    Sec("rules", R.string.s_rules, stringResource(R.string.rules_sub)) { RulesSection(l, ::send) }
                    Sec("alerts", R.string.s_alerts, stringResource(R.string.alerts_sub)) { AlertsSection(l, ::send) }
                    Sec("tg", R.string.s_tg, stringResource(R.string.tg_sub)) { TelegramSection(r, l, ::send) }
                    Sec("usage", R.string.s_usage) { UsageSection(l, ::send) }
                    Sec("loc", R.string.s_location) { LocationSection(l, ::send) }
                    Sec("cal", R.string.s_cal, stringResource(R.string.cal_sub)) { CalSection(r) }
                }
            }
            Sec("share", R.string.s_share) { ShareSection(r, ro) }
            if (!ro && r != null) Sec("board", R.string.s_board) { BoardSection(r, via) }
            Sec("app", R.string.a_phone) { AppSection() }
            Box(Modifier.onGloballyPositioned { pos["look"] = it.positionInParent().y.toInt() }) { LookCard(onLang) }
            SecondaryButton(stringResource(R.string.a_switch_gh), color = Neon) { leaveGreenhouse(ctx) }
            Column(Modifier.fillMaxWidth().card().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LeafMark(Modifier.size(40.dp))
                Spacer(Modifier.height(6.dp))
                Text("HBRT Agro", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.a_version, BuildConfigShim.version(ctx)), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                Credits()
            }
        }
    }
}

private object BuildConfigShim {
    fun version(c: android.content.Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
}

/* ---------------- sections ---------------- */

@Composable
private fun NameSection(r: Reading?) {
    val ctx = LocalContext.current
    var name by remember(r?.name) { mutableStateOf(r?.name ?: "") }
    OutlinedTextField(name, { if (it.length <= 32) name = it }, singleLine = true, colors = field(),
        placeholder = { Text(stringResource(R.string.name_ph)) }, modifier = Modifier.fillMaxWidth())
    SoftButton(stringResource(R.string.save_name), name.isNotBlank() && name != r?.name) {
        if (r?.modern == true) toast(ctx, ctx.getString(if (GreenhouseClient.rename(name.trim())) R.string.name_saved else R.string.not_sent))
        else { Prefs.saveGreenhouse(SavedGh(name.trim(), GreenhouseClient.address)); toast(ctx, ctx.getString(R.string.name_saved)) }
    }
}

private fun todayDay(): Int {
    val now = System.currentTimeMillis()
    return ((now + TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
}

@Composable
private fun CropSection(l: Cfg, v6: Boolean, send: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    var crop by remember(l.crop) { mutableStateOf(l.crop) }
    var day by remember(l.plantDay) { mutableIntStateOf(l.plantDay) }
    var auto by remember(l.autoStage) { mutableStateOf(l.autoStage) }
    Text(stringResource(R.string.crop_q), color = Muted, fontSize = 13.sp)
    Chips {
        Chip(stringResource(R.string.c_none), crop.isEmpty(), v6) { crop = "" }
        PRESETS.forEach { p -> Chip(cropName(p.id), crop == p.id, v6) { crop = p.id; if (day == 0) day = todayDay() } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.plant_date), color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f))
        PickerChip(if (day > 0) dateStr(day * 86_400_000L + 43_200_000L) else "–", v6) {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = (if (day > 0) day else todayDay()) * 86_400_000L }
            DatePickerDialog(ctx, { _, y, m, d ->
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m, d) }
                day = (u.timeInMillis / 86_400_000L).toInt()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }
    }
    SwitchRow(stringResource(R.string.auto_stage), auto, stringResource(R.string.auto_stage_sub), v6) { auto = it }
    SoftButton(stringResource(R.string.save_crop), v6) { send(JSONObject().put("crop", crop).put("plantDay", if (crop.isEmpty()) 0 else day).put("autoStage", auto)) }
}

@Composable
private fun LimitsSection(l: Cfg, send: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    var tMax by remember(l) { mutableStateOf(l.tempMax.f1()) }
    var tMin by remember(l) { mutableStateOf(l.tempMin.f1()) }
    var hMax by remember(l) { mutableStateOf(l.humMax.f0()) }
    var sMin by remember(l) { mutableStateOf("${l.soilMin}") }
    var wMin by remember(l) { mutableStateOf("${l.waterMin}") }
    var pMax by remember(l) { mutableStateOf("${l.pumpMax}") }
    Text(stringResource(R.string.presets_sub), color = Muted, fontSize = 13.sp)
    Chips {
        PRESETS.forEach { p -> Chip(cropName(p.id), false) { tMin = p.tempMin.f1(); tMax = p.tempMax.f1(); hMax = p.humMax.f0(); sMin = "${p.soilMin}" } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField(stringResource(R.string.f_tmax), tMax, Modifier.weight(1f)) { tMax = it }
        NumField(stringResource(R.string.f_tmin), tMin, Modifier.weight(1f)) { tMin = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField(stringResource(R.string.f_hmax), hMax, Modifier.weight(1f)) { hMax = it }
        NumField(stringResource(R.string.f_smin), sMin, Modifier.weight(1f)) { sMin = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField(stringResource(R.string.f_wmin), wMin, Modifier.weight(1f)) { wMin = it }
        NumField(stringResource(R.string.f_pmax), pMax, Modifier.weight(1f)) { pMax = it }
    }
    SoftButton(stringResource(R.string.save_gh)) {
        val a = tMax.toFloatOrNull(); val b = tMin.toFloatOrNull(); val h = hMax.toFloatOrNull()
        val s = sMin.toIntOrNull(); val w = wMin.toIntOrNull(); val p = pMax.toIntOrNull()
        when {
            a == null || b == null || h == null || s == null || w == null || p == null -> toast(ctx, ctx.getString(R.string.err_numbers))
            b >= a -> toast(ctx, ctx.getString(R.string.err_minmax))
            p !in 5..600 -> toast(ctx, ctx.getString(R.string.err_pump))
            else -> send(JSONObject().put("tempMax", a).put("tempMin", b).put("humMax", h).put("soilMin", s).put("waterMin", w).put("pumpMax", p))
        }
    }
}

@Composable
private fun PickerChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(14.dp)).background(CardHi).border(1.dp, Border, RoundedCornerShape(14.dp))
        .bounceClick(enabled, onClick).padding(horizontal = 14.dp, vertical = 10.dp), color = if (enabled) TextC else Muted, fontSize = 17.sp, style = Tnum)
}

@Composable
private fun TimeChip(minutes: Int, onChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    PickerChip(fmtMinutes(minutes)) {
        TimePickerDialog(ctx, { _, h, m -> onChange(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }
}

@Composable
private fun LightsSection(l: Cfg, send: (JSONObject) -> Unit) {
    var sched by remember(l) { mutableStateOf(l.lightSched) }
    var on by remember(l) { mutableIntStateOf(l.lightOn) }
    var off by remember(l) { mutableIntStateOf(l.lightOff) }
    SwitchRow(stringResource(R.string.light_sched), sched, stringResource(R.string.light_sched_sub)) { sched = it }
    AnimatedVisibility(sched, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lights_on_at), color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f)); TimeChip(on) { on = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lights_off_at), color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f)); TimeChip(off) { off = it }
            }
            // 24-hour strip
            val gold = Gold; val track = Track
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                val w = size.width
                fun seg(a: Int, b: Int) = drawRoundRect(gold, androidx.compose.ui.geometry.Offset(w * a / 1440f, 0f), androidx.compose.ui.geometry.Size(w * (b - a) / 1440f, size.height), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                if (off > on) seg(on, off) else { seg(on, 1440); seg(0, off) }
            }
            Text(stringResource(R.string.hours_light, (((off - on + 1440) % 1440) / 60f).f1()), color = Muted, fontSize = 12.sp)
        }
    }
    SoftButton(stringResource(R.string.save)) { send(JSONObject().put("lightSched", sched).put("lightOn", on).put("lightOff", off)) }
}

@Composable
private fun DayToggles(mask: Int, onChange: (Int) -> Unit) {
    val names = listOf(R.string.day_0, R.string.day_1, R.string.day_2, R.string.day_3, R.string.day_4, R.string.day_5, R.string.day_6)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { i, n ->
            val on = (mask shr i) and 1 == 1
            Text(stringResource(n), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Neon else CardHi)
                .bounceClick { onChange(mask xor (1 shl i)) }.padding(vertical = 7.dp),
                color = if (on) OnNeon else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun TimesSection(l: Cfg, send: (JSONObject) -> Unit) {
    val slots = remember(l) { mutableStateListOf<Slot>().apply { addAll(l.sched) } }
    var rain by remember(l) { mutableStateOf(l.rainSkip) }
    slots.forEachIndexed { i, s ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeChip(s.m) { slots[i] = s.copy(m = it) }
                NumField(stringResource(R.string.wt_secs), "${s.s}", Modifier.weight(1f)) { v -> slots[i] = s.copy(s = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) }
                Icon(Icons.Rounded.Close, stringResource(R.string.delete), tint = Muted, modifier = Modifier.size(26.dp).clip(CircleShape).bounceClick { slots.removeAt(i) })
            }
            DayToggles(s.d) { slots[i] = s.copy(d = it) }
        }
    }
    if (slots.size < 4) Text("+ " + stringResource(R.string.wt_add), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick { slots.add(Slot(360, 20, 127)) }.padding(8.dp), color = Neon, fontWeight = FontWeight.Bold)
    SwitchRow(stringResource(R.string.rain_skip), rain) { rain = it }
    SoftButton(stringResource(R.string.save_times)) {
        val a = JSONArray(); slots.filter { it.s > 0 }.forEach { a.put(JSONObject().put("m", it.m).put("s", it.s.coerceIn(1, 600)).put("d", it.d)) }
        send(JSONObject().put("sched", a).put("rainSkip", rain))
    }
}

private val SENSORS = listOf("T" to R.string.m_air_temp, "H" to R.string.m_hum, "S" to R.string.m_soil_moist, "ST" to R.string.m_soil_temp, "W" to R.string.m_water_tank)

@Composable
private fun RulesSection(l: Cfg, send: (JSONObject) -> Unit) {
    val rules = remember(l) { mutableStateListOf<RuleC>().apply { addAll(l.rules) } }
    if (rules.isEmpty()) Text(stringResource(R.string.rule_none), color = Muted, fontSize = 13.sp)
    rules.forEachIndexed { i, ru ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rule_if), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Close, stringResource(R.string.delete), tint = Muted, modifier = Modifier.size(24.dp).clip(CircleShape).bounceClick { rules.removeAt(i) })
            }
            Chips { SENSORS.forEach { (k, n) -> Chip(stringResource(n), ru.s == k) { rules[i] = ru.copy(s = k) } } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.below), ru.o == "<") { rules[i] = ru.copy(o = "<") }
                Chip(stringResource(R.string.above), ru.o == ">") { rules[i] = ru.copy(o = ">") }
                NumField("", ru.v.f1(), Modifier.weight(1f)) { v -> v.toFloatOrNull()?.let { rules[i] = ru.copy(v = it) } }
            }
            Text(stringResource(R.string.rule_then), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Chips { DEVICES.forEach { d -> Chip(devName(d), ru.d == d) { rules[i] = ru.copy(d = d) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.turn_on), ru.on) { rules[i] = ru.copy(on = true) }
                Chip(stringResource(R.string.turn_off), !ru.on) { rules[i] = ru.copy(on = false) }
            }
        }
    }
    if (rules.size < 6) Text("+ " + stringResource(R.string.rule_add), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick { rules.add(RuleC()) }.padding(8.dp), color = Neon, fontWeight = FontWeight.Bold)
    SoftButton(stringResource(R.string.rule_save)) {
        val a = JSONArray(); rules.forEach { a.put(JSONObject().put("s", it.s).put("o", it.o).put("v", it.v).put("d", it.d).put("on", it.on)) }
        send(JSONObject().put("rules", a))
    }
}

@Composable
private fun AlertsSection(l: Cfg, send: (JSONObject) -> Unit) {
    var mask by remember(l) { mutableIntStateOf(l.alertMask) }
    var quiet by remember(l) { mutableStateOf(l.quiet) }
    var qf by remember(l) { mutableIntStateOf(l.quietFrom) }
    var qt by remember(l) { mutableIntStateOf(l.quietTo) }
    var sum by remember(l) { mutableStateOf(l.dailySummary) }
    val names = listOf(R.string.al_hot_n, R.string.al_cold_n, R.string.al_humid_n, R.string.al_dry_n, R.string.al_tank_n, R.string.al_lock_n, R.string.al_dht_n, R.string.al_st_n, R.string.al_frost_n)
    Chips { ALERTS.forEachIndexed { i, _ -> Chip(stringResource(names[i]), (mask shr i) and 1 == 1) { mask = mask xor (1 shl i) } } }
    SwitchRow(stringResource(R.string.quiet), quiet, stringResource(R.string.quiet_sub)) { quiet = it }
    AnimatedVisibility(quiet) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.from), color = Muted); TimeChip(qf) { qf = it }
            Text(stringResource(R.string.to), color = Muted); TimeChip(qt) { qt = it }
        }
    }
    SwitchRow(stringResource(R.string.daily_summary), sum) { sum = it }
    SoftButton(stringResource(R.string.save)) {
        send(JSONObject().put("alertMask", mask).put("frostAlert", (mask shr 8) and 1 == 1).put("quiet", quiet).put("quietFrom", qf).put("quietTo", qt).put("dailySummary", sum))
    }
}

@Composable
private fun TelegramSection(r: Reading?, l: Cfg, send: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    var tok by remember { mutableStateOf("") }
    listOf(R.string.tg_step1, R.string.tg_step2, R.string.tg_step3).forEachIndexed { i, s ->
        Row {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Blue.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(s), color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }
    OutlinedTextField(tok, { tok = it.trim() }, label = { Text(stringResource(R.string.tg_token)) }, singleLine = true, colors = field(),
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    SoftButton(stringResource(R.string.tg_save), tok.length > 20) { send(JSONObject().put("tgToken", tok)); tok = "" }
    val st = r?.tg ?: "off"
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(when (st) { "ok" -> Neon; "err" -> Danger; "off" -> Muted; else -> Orange }, st == "wait" || st == "on")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(when (st) { "ok" -> R.string.tg_ok; "wait" -> R.string.tg_wait; "err" -> R.string.tg_err; "on" -> R.string.connecting; else -> R.string.tg_off }),
                color = when (st) { "ok" -> Neon; "err" -> Danger; else -> TextC }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (l.tgChat.isNotBlank()) Text("chat ${l.tgChat}", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = { toast(ctx, ctx.getString(if (GreenhouseClient.telegramTest()) R.string.saved else R.string.not_sent)) }, enabled = st == "ok") { Text(stringResource(R.string.tg_test), color = if (st == "ok") Neon else Muted) }
    }
    Text(stringResource(R.string.tg_lang), color = Muted, fontSize = 13.sp)
    Chips { listOf("en" to "English", "fa" to "دری", "ps" to "پښتو").forEach { (k, n) -> Chip(n, l.lang == k) { send(JSONObject().put("lang", k)) } } }
    if (l.tgSet) Text(stringResource(R.string.tg_remove), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick { send(JSONObject().put("tgToken", "")) }.padding(8.dp), color = Danger, fontWeight = FontWeight.Bold)
}

@Composable
private fun UsageSection(l: Cfg, send: (JSONObject) -> Unit) {
    var flow by remember(l) { mutableStateOf("${l.flowMl}") }
    var tank by remember(l) { mutableStateOf("${l.tankL}") }
    val watts = remember(l) { mutableStateMapOf<String, String>().apply { listOf("fan", "pump", "lights", "heater").forEach { put(it, l.watt[it]?.toString() ?: "") } } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField(stringResource(R.string.flow), flow, Modifier.weight(1f)) { flow = it }
        NumField(stringResource(R.string.tank_size), tank, Modifier.weight(1f)) { tank = it }
    }
    Text(stringResource(R.string.flow_help), color = Muted, fontSize = 12.sp)
    Text(stringResource(R.string.watts), color = Muted, fontSize = 13.sp)
    listOf("fan", "pump", "lights", "heater").chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { d -> NumField(devName(d), watts[d] ?: "", Modifier.weight(1f)) { watts[d] = it } } }
    }
    SoftButton(stringResource(R.string.save)) {
        val w = JSONObject(); watts.forEach { (k, v) -> v.toIntOrNull()?.let { w.put(k, it) } }
        val j = JSONObject().put("watt", w)
        flow.toIntOrNull()?.let { j.put("flowMl", it) }; tank.toIntOrNull()?.let { j.put("tankL", it) }
        send(j)
    }
}

@Composable
private fun LocationSection(l: Cfg, send: (JSONObject) -> Unit) {
    var lat by remember(l) { mutableStateOf(l.lat.toString()) }
    var lon by remember(l) { mutableStateOf(l.lon.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumField(stringResource(R.string.lat), lat, Modifier.weight(1f)) { lat = it }
        NumField(stringResource(R.string.lon), lon, Modifier.weight(1f)) { lon = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.loc_herat), modifier = Modifier.weight(1f)) { lat = "34.35"; lon = "62.2" }
        SoftButton(stringResource(R.string.save), lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null, Modifier.weight(1f)) {
            send(JSONObject().put("lat", lat.toDouble()).put("lon", lon.toDouble()))
        }
    }
}

@Composable
private fun CalSection(r: Reading?) {
    val ctx = LocalContext.current
    fun cal(w: String) = toast(ctx, ctx.getString(if (GreenhouseClient.calibrate(w)) R.string.cal_done else R.string.not_sent))
    @Composable fun Step(text: Int, btn: Int, what: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(stringResource(btn), Modifier.clip(RoundedCornerShape(12.dp)).background(CardHi).border(1.dp, Border, RoundedCornerShape(12.dp))
            .bounceClick(r != null) { cal(what) }.padding(horizontal = 12.dp, vertical = 9.dp), color = Neon, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Text(stringResource(R.string.cal_soil), color = TextC, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.cal_raw, if ((r?.rawS ?: -1) >= 0) "${r!!.rawS}" else "--") + " · ${r?.soil ?: "--"}%", color = Muted, fontSize = 12.sp, style = Tnum)
    Step(R.string.cal_soil_dry, R.string.cal_dry_btn, "soilDry"); Step(R.string.cal_soil_wet, R.string.cal_wet_btn, "soilWet")
    Divider()
    Text(stringResource(R.string.cal_tank), color = TextC, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.cal_raw, if ((r?.rawW ?: -1) >= 0) "${r!!.rawW}" else "--") + " · ${r?.water ?: "--"}%", color = Muted, fontSize = 12.sp, style = Tnum)
    Step(R.string.cal_tank_empty, R.string.cal_empty_btn, "waterEmpty"); Step(R.string.cal_tank_full, R.string.cal_full_btn, "waterFull")
    Text(stringResource(R.string.cal_reset), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick { cal("reset") }.padding(8.dp), color = Orange, fontWeight = FontWeight.Bold)
}

private fun shareText(c: android.content.Context, link: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, link) }
    c.startActivity(Intent.createChooser(i, title))
}
private fun copyText(c: android.content.Context, s: String) {
    (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("link", s))
    toast(c, c.getString(R.string.copied))
}

@Composable
private fun ShareSection(r: Reading?, ro: Boolean) {
    val ctx = LocalContext.current
    val ref = GreenhouseClient.cloud
    if (ref == null) { Text(stringResource(R.string.a_needs_fw6), color = Muted, fontSize = 13.sp); return }
    val title = "HBRT · ${r?.name ?: ""}"
    if (!ro) {
        Text(stringResource(R.string.share_ctrl), color = TextC, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.share_ctrl_sub), color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(stringResource(R.string.share), modifier = Modifier.weight(1f)) { shareText(ctx, ref.shareLink(), title) }
            SecondaryButton(stringResource(R.string.copy_link), modifier = Modifier.weight(1f)) { copyText(ctx, ref.shareLink()) }
        }
        SecondaryButton(stringResource(R.string.a_open_web), color = Blue) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ref.shareLink()))) }
        Divider()
    }
    Text(stringResource(R.string.share_view), color = TextC, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.share_view_sub), color = Muted, fontSize = 13.sp)
    val vk = r?.view ?: ""
    val viewLink = if (ro) ref.shareLink() else if (vk.isNotBlank()) CloudRef(vk, ref.db).shareLink() else ""
    if (viewLink.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(stringResource(R.string.share), modifier = Modifier.weight(1f)) { shareText(ctx, viewLink, title) }
            SecondaryButton(stringResource(R.string.copy_link), modifier = Modifier.weight(1f)) { copyText(ctx, viewLink) }
        }
        if (!ro) Text(stringResource(R.string.view_remove), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick { GreenhouseClient.viewLink(false) }.padding(8.dp), color = Danger, fontWeight = FontWeight.Bold)
    } else if (!ro) SecondaryButton(stringResource(R.string.view_make), color = Neon) {
        toast(ctx, ctx.getString(if (GreenhouseClient.viewLink(true)) R.string.view_wait else R.string.not_sent))
    }
}

@Composable
private fun BoardSection(r: Reading, via: Via) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askOta by remember { mutableStateOf(false) }
    var askReboot by remember { mutableStateOf(false) }
    var askRekey by remember { mutableStateOf(false) }
    var upload by remember { mutableFloatStateOf(-1f) }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var wifiOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!FwLatest.asked) {
            FwLatest.asked = true
            FwLatest.v = withContext(Dispatchers.IO) {
                try {
                    GreenhouseClient.cloudHttp.newCall(Request.Builder().url("https://api.github.com/repos/mostafaaabedia-tech/Greenhouse_Kotlin/releases/latest").build()).execute().use {
                        if (it.isSuccessful) JSONObject(it.body!!.string()).optString("tag_name").trimStart { c -> !c.isDigit() } else ""
                    }
                } catch (e: Exception) { "" }
            }
        }
    }
    val pickBin = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            upload = 0f
            val ok = GreenhouseClient.uploadFirmware(ctx, uri) { upload = it }
            upload = -1f
            toast(ctx, ctx.getString(if (ok) R.string.a_ota_ok else R.string.a_save_failed))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.fw, r.fw.ifBlank { "?" }), color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (FwLatest.v.isNotEmpty()) Text(if (FwLatest.v == r.fw) stringResource(R.string.fw_uptodate) else stringResource(R.string.fw_latest, FwLatest.v),
            color = if (FwLatest.v == r.fw) Neon else Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    val ota = r.ota
    if (ota.isNotEmpty() && ota != "done") Text(if (ota.startsWith("failed")) stringResource(R.string.fw_failed, ota.removePrefix("failed").trimStart(':', ' ')) else stringResource(R.string.fw_updating),
        color = if (ota.startsWith("failed")) Danger else Muted, fontSize = 13.sp)
    SoftButton(stringResource(R.string.fw_update), r.v6) { askOta = true }

    // health
    val rr = when (r.resetReason) { 1 -> R.string.rr_power; 3 -> R.string.rr_sw; 4 -> R.string.rr_panic; 5, 6, 7 -> R.string.rr_wdt; 9 -> R.string.rr_brown; else -> R.string.rr_other }
    val kv = listOf(
        stringResource(R.string.hp_signal) to "${r.rssi} dBm", stringResource(R.string.hp_uptime) to (if (r.uptime > 0) dur(ctx, r.uptime) else "--"),
        stringResource(R.string.hp_heap) to (if (r.heap > 0) "${r.heap / 1024} KB" else "--"), stringResource(R.string.hp_reset) to stringResource(rr),
        stringResource(R.string.hp_drops) to "${r.drops}", stringResource(R.string.hp_cloud) to "${r.cloudErrors}",
    )
    kv.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (k, v) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(CardHi).padding(10.dp)) {
                    Text(k, color = Muted, fontSize = 11.sp); Text(v, color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = Tnum)
                }
            }
        }
    }
    if (r.ssid.isNotBlank()) Text("${stringResource(R.string.wifi_name)}: ${r.ssid}${if (r.ip.isNotBlank()) " · " + r.ip else ""}", color = Muted, fontSize = 13.sp)
    Text(stringResource(R.string.wifi_change_note), color = Muted, fontSize = 12.sp)
    Divider()

    // things that only work on the greenhouse Wi-Fi
    val local = via == Via.WIFI
    if (!local) Text(stringResource(R.string.a_wifi_first), color = Muted, fontSize = 12.sp)
    SecondaryButton(stringResource(R.string.a_ota_file), local && upload < 0) { pickBin.launch("application/octet-stream") }
    if (upload >= 0) {
        LinearProgressIndicator(progress = { upload }, Modifier.fillMaxWidth(), color = Neon, trackColor = Track)
        Text(stringResource(R.string.a_ota_uploading, (upload * 100).toInt()), color = Muted, fontSize = 12.sp)
    }
    SecondaryButton(stringResource(R.string.a_wifi_change), local) { wifiOpen = !wifiOpen }
    AnimatedVisibility(wifiOpen && local) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.a_wifi_ssid)) }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.a_wifi_pass)) }, singleLine = true, colors = field(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            SoftButton(stringResource(R.string.a_wifi_send), ssid.isNotBlank()) {
                toast(ctx, if (GreenhouseClient.setWifi(ssid.trim(), pass)) ctx.getString(R.string.a_wifi_sent, ssid.trim()) else ctx.getString(R.string.a_wifi_first)); wifiOpen = false
            }
        }
    }
    Text(stringResource(R.string.a_rekey), Modifier.clip(RoundedCornerShape(10.dp)).bounceClick(local) { askRekey = true }.padding(8.dp), color = if (local) Orange else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    SecondaryButton(stringResource(R.string.reboot), color = Danger) { askReboot = true }

    if (askOta) Confirm(stringResource(R.string.fw_update), stringResource(R.string.fw_confirm), stringResource(R.string.fw_update), Neon, { askOta = false }) {
        toast(ctx, ctx.getString(if (GreenhouseClient.updateFirmware()) R.string.fw_updating else R.string.not_sent))
    }
    if (askReboot) Confirm(stringResource(R.string.reboot), stringResource(R.string.reboot_q), stringResource(R.string.reboot), Danger, { askReboot = false }) {
        toast(ctx, ctx.getString(if (GreenhouseClient.reboot()) R.string.saved else R.string.not_sent))
    }
    if (askRekey) Confirm(stringResource(R.string.a_rekey_q), stringResource(R.string.a_rekey_body), stringResource(R.string.a_rekey_done), Orange, { askRekey = false }) {
        toast(ctx, ctx.getString(if (GreenhouseClient.newCloudKey()) R.string.a_rekey_done else R.string.a_wifi_first))
    }
}

@Composable
private fun Confirm(title: String, body: String, ok: String, color: Color, dismiss: () -> Unit, onOk: () -> Unit) = AlertDialog(
    onDismissRequest = dismiss, containerColor = CardC,
    title = { Text(title) }, text = { Text(body) },
    confirmButton = { TextButton(onClick = { dismiss(); onOk() }) { Text(ok, color = color) } },
    dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
)

@Composable
private fun AppSection() {
    val ctx = LocalContext.current
    var bg by remember { mutableStateOf(Prefs.background) }
    var daily by remember { mutableStateOf(Prefs.dailyReport) }
    var every by remember { mutableIntStateOf(Prefs.logEverySec) }
    var aiUrl by remember { mutableStateOf(Prefs.aiUrl) }
    var aiModel by remember { mutableStateOf(Prefs.aiModel) }
    SwitchRow(stringResource(R.string.a_bg), bg, stringResource(R.string.a_bg_sub)) {
        bg = it; Prefs.background = it
        if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
    }
    SwitchRow(stringResource(R.string.a_daily_pdf), daily, stringResource(R.string.a_daily_pdf_sub)) {
        daily = it; Prefs.dailyReport = it
        if (it) ReportWorker.schedule(ctx) else ReportWorker.cancel(ctx)
    }
    Text(stringResource(R.string.a_log_every), color = TextC, fontSize = 14.sp)
    Chips { listOf(5, 10, 30, 60).forEach { s -> Chip(stringResource(R.string.sec_short, s), every == s) { every = s; Prefs.logEverySec = s } } }
    Divider()
    Text(stringResource(R.string.a_ai_title), color = TextC, fontWeight = FontWeight.Bold)
    OutlinedTextField(aiUrl, { aiUrl = it }, label = { Text(stringResource(R.string.a_ai_url)) }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(aiModel, { aiModel = it }, label = { Text(stringResource(R.string.a_ai_model)) }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
    SoftButton(stringResource(R.string.save)) { Prefs.aiUrl = aiUrl.trim(); Prefs.aiModel = aiModel.trim(); toast(ctx, ctx.getString(R.string.a_ai_saved)) }
}
