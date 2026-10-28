package com.hbrt.agro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(r: Reading?, online: Boolean) {
    val ctx = LocalContext.current
    val cfg by GreenhouseClient.cfg.collectAsStateWithLifecycle()
    val wx by GreenhouseClient.wx.collectAsStateWithLifecycle()
    val ro by GreenhouseClient.readOnly.collectAsStateWithLifecycle()
    val l = cfg ?: r?.legacyCfg ?: Cfg()
    val mode = r?.mode ?: "auto"
    var sheet by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var waterSheet by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val notSent = stringResource(R.string.not_sent)
    fun ok(b: Boolean) { if (!b) toast(ctx, notSent) }

    fun tap(d: String) {
        val cur = r ?: return
        if (d == "pump") { if (cur.pump || cur.watering > 0) ok(GreenhouseClient.stopWater()) else waterSheet = true; return }
        val v = !cur.on(d)
        when {
            !cur.v6 -> ok(GreenhouseClient.device(d, v, null))            // older firmware: manual mode
            mode == "manual" -> ok(GreenhouseClient.device(d, v, 0))
            else -> sheet = d to v
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(stringResource(R.string.ctl_title))
        if (ro) Notice(stringResource(R.string.view_only_note), Muted)

        // modes
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("auto", R.string.mode_auto, R.string.mode_auto_sub), Triple("manual", R.string.mode_manual, R.string.mode_manual_sub), Triple("away", R.string.mode_away, R.string.mode_away_sub)).forEach { (m, n, sub) ->
                val sel = mode == m
                val bg by animateColorAsState(if (sel) Neon else CardC, tween(250), label = "m")
                val enabled = online && !ro && (m != "away" || r?.v6 == true)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, if (sel) Neon else Border, RoundedCornerShape(20.dp))
                        .bounceClick(enabled) {
                            if (r?.v6 == true) ok(GreenhouseClient.setMode(m)) else ok(GreenhouseClient.device("auto", m != "manual", null))
                        }.padding(12.dp)
                ) {
                    Text(stringResource(n), color = if (sel) OnNeon else TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(sub), color = if (sel) OnNeon.copy(alpha = .8f) else Muted, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
        if (mode == "away") Text(stringResource(R.string.away_note), color = Muted, fontSize = 13.sp)
        if (mode == "manual") Text(stringResource(R.string.manual_note), color = Muted, fontSize = 13.sp)

        // overrides
        r?.ovr?.forEach { (d, secs) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(TextC).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (r.on(d)) R.string.ovr_on else R.string.ovr_off, devName(d)), color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(if (secs < 0) stringResource(R.string.ovr_forever) else stringResource(R.string.ovr_back, max(1, (secs + 59) / 60)), color = Bg.copy(alpha = .75f), fontSize = 13.sp)
                }
                Text(stringResource(R.string.return_now), Modifier.clip(RoundedCornerShape(14.dp)).background(Neon).bounceClick(online) { ok(GreenhouseClient.clearOverride(d)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), color = OnNeon, fontWeight = FontWeight.Bold)
            }
        }

        // device bubbles
        Column(Modifier.fillMaxWidth().card().padding(vertical = 18.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val items = DEVICES + "alloff"
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { d ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            if (d == "alloff") Bubble(stringResource(R.string.all_off), stringResource(R.string.all_off_sub), Danger, on = false, stop = true, enabled = online && !ro, onClick = { confirmOff = true }) {
                                PowerIcon(Danger, Modifier.size(28.dp))
                            } else {
                                val on = r?.on(d) == true
                                val ov = r?.ovr?.get(d)
                                val st = when {
                                    d == "pump" && (r?.watering ?: 0) > 0 -> stringResource(R.string.watering_left, r!!.watering)
                                    ov != null -> if (ov < 0) stringResource(R.string.left_forever) else stringResource(R.string.left_min, max(1, (ov + 59) / 60))
                                    d == "servo" -> stringResource(if (on) R.string.open else R.string.closed)
                                    else -> stringResource(if (on) R.string.on else R.string.off) +
                                        if (mode != "manual") " · " + stringResource(if (d == "lights" && l.lightSched) R.string.st_schedule else R.string.st_auto) else ""
                                }
                                val c = devColor(d)
                                Bubble(devName(d), st, c, on = on, enabled = online && !ro && !(d == "pump" && !on && (r?.water ?: 0) <= l.waterMin), onClick = { tap(d) }) {
                                    DevIcon(d, on, if (on) (if (Pal.dark) Color(0xFF081A10) else Color.White) else Muted, Modifier.size(30.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // water now
        if (r != null) Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                H2(stringResource(R.string.water_now), Modifier.weight(1f))
                Text("${stringResource(R.string.m_tank)} ${r.water}%", color = Muted, fontSize = 13.sp)
            }
            val blocked = when { r.water <= l.waterMin -> stringResource(R.string.tank_block); r.pumpLock -> stringResource(R.string.pump_rest); else -> "" }
            HoldToWater(r.watering, l.pumpMax, enabled = online && !ro && (blocked.isEmpty() || r.watering > 0))
            Text(blocked.ifEmpty { stringResource(R.string.hold_hint, l.pumpMax) }, color = if (blocked.isEmpty()) Muted else Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            Chips {
                listOf(5, 10, 20, 30, 60).filter { it <= l.pumpMax }.forEach { s ->
                    Chip(stringResource(R.string.sec_short, s), false, enabled = online && blocked.isEmpty()) {
                        if (GreenhouseClient.waterNow(s)) toast(ctx, ctx.getString(R.string.watering_for, s)) else toast(ctx, notSent)
                    }
                }
            }
        }

        // week plan
        WeekPlan(l, wx)
        Spacer(Modifier.height(4.dp))
    }

    sheet?.let { (d, v) ->
        var pick by remember { mutableIntStateOf(30) }
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CardC) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(devColor(d)), contentAlignment = Alignment.Center) { DevIcon(d, true, Color.White, Modifier.size(24.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(if (v) R.string.keep_on else R.string.keep_off, devName(d).lowercase(Lang.locale())), color = TextC, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.then_auto), color = Muted, fontSize = 13.sp)
                    }
                }
                val opts = listOf(15 to R.string.dur_15, 30 to R.string.dur_30, 60 to R.string.dur_60, -1 to R.string.dur_forever)
                opts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (m, n) ->
                            val sel = pick == m
                            Text(stringResource(n), Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) TextC else CardHi)
                                .border(1.dp, if (sel) TextC else Border, RoundedCornerShape(16.dp)).bounceClick { pick = m }.padding(15.dp),
                                color = if (sel) Bg else TextC, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
                val label = opts.first { it.first == pick }.second
                PrimaryButton(if (pick < 0) stringResource(if (v) R.string.turn_on else R.string.turn_off) else stringResource(if (v) R.string.turn_on_for else R.string.turn_off_for, stringResource(label))) {
                    ok(GreenhouseClient.device(d, v, pick)); sheet = null
                }
                SecondaryButton(stringResource(R.string.cancel)) { sheet = null }
            }
        }
    }
    if (waterSheet) {
        ModalBottomSheet(onDismissRequest = { waterSheet = false }, containerColor = CardC) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                H2(stringResource(R.string.water_now))
                Chips {
                    listOf(5, 10, 20, 30, 60).filter { it <= l.pumpMax }.forEach { s ->
                        Chip(stringResource(R.string.sec_short, s), false) { ok(GreenhouseClient.waterNow(s)); waterSheet = false }
                    }
                }
            }
        }
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text(stringResource(R.string.all_off_q)) },
        text = { Text(stringResource(R.string.all_off_body)) },
        confirmButton = { TextButton(onClick = { confirmOff = false; ok(GreenhouseClient.allOff()) }) { Text(stringResource(R.string.turn_off), color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.cancel)) } },
        containerColor = CardC
    )
}

@Composable
private fun Bubble(name: String, status: String, color: Color, on: Boolean, stop: Boolean = false, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val t = rememberInfiniteTransition(label = "orbit")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a")
    val bg by animateColorAsState(if (on) color else CardHi, tween(350), label = "bg")
    Column(
        Modifier.bounceClick(enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            if (on) Canvas(Modifier.fillMaxSize()) {
                rotate(a) { drawCircle(color, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 10.dp.toPx())))) }
            }
            Box(
                Modifier.size(66.dp).then(if (on) Modifier.shadow(16.dp, CircleShape, ambientColor = color, spotColor = color) else Modifier)
                    .clip(CircleShape).background(bg)
                    .then(if (stop) Modifier.border(1.dp, color.copy(alpha = .6f), CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) { icon() }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = TextC.copy(alpha = if (enabled) 1f else .5f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(status, color = if (on) color else Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 2)
    }
}


@Composable
private fun HoldToWater(watering: Int, max: Int, enabled: Boolean) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    var start by remember { mutableLongStateOf(0L) }
    var secs by remember { mutableIntStateOf(10) }
    LaunchedEffect(holding) {
        while (holding) { secs = (5 + (System.currentTimeMillis() - start) / 1000f * 8).roundToInt().coerceIn(5, max); delay(60) }
    }
    val busy = watering > 0
    val target = when { holding -> secs / max.toFloat(); busy -> watering / max.toFloat(); else -> 0f }
    val arc by animateFloatAsState(target.coerceIn(0f, 1f), tween(200), label = "arc")
    val t = rememberInfiniteTransition(label = "halo")
    val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "h")
    val blue = Blue; val track = Track
    val stopTxt = stringResource(R.string.stop)
    val holdTxt = stringResource(R.string.hold_to_water)
    Box(
        Modifier.size(200.dp).pointerInput(enabled, busy) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                if (busy) { GreenhouseClient.stopWater(); return@detectTapGestures }
                start = System.currentTimeMillis(); secs = 5; holding = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val released = tryAwaitRelease()
                holding = false
                if (released) {
                    val s = if (System.currentTimeMillis() - start < 350) 10 else secs
                    if (GreenhouseClient.waterNow(s)) toast(ctx, ctx.getString(R.string.watering_for, s))
                }
            })
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 10.dp.toPx()
            val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            drawArc(blue, -90f, 360f * arc, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            if (holding || busy) drawCircle(blue.copy(alpha = (1 - halo) * .6f), size.minDimension / 2 * (0.78f + .28f * halo), style = Stroke(2.dp.toPx()))
        }
        Column(
            Modifier.size(156.dp).clip(CircleShape).background(Brush.radialGradient(listOf(blue.copy(alpha = .95f), blue.copy(alpha = .55f).compositeDark()))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            DropIcon(busy, Color.White, Modifier.size(28.dp))
            Text(stringResource(R.string.sec_short, if (holding) secs else if (busy) watering else 10), color = Color.White, fontSize = 34.sp, style = Tnum)
            Text(if (busy && !holding) stopTxt else holdTxt, color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
        }
    }
}

private fun Color.compositeDark() = Color(red * .55f, green * .55f, blue * .55f, 1f)

@Composable
fun WeekPlan(l: Cfg, wx: Wx?) {
    val sched = l.sched.filter { it.s > 0 }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        H2(stringResource(R.string.plan))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 7) {
                val ms = System.currentTimeMillis() + i * 86_400_000L
                val wd = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.DAY_OF_WEEK) - 1
                val n = sched.count { (it.d shr wd) and 1 == 1 }
                val rain = l.rainSkip && n > 0 && i < 4 && wx?.days?.getOrNull(i)?.rain == true
                val c = when { rain -> Blue; n > 0 -> Neon; else -> Muted }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (n == 0) CardHi else c.copy(alpha = .14f))
                        .then(if (i == 0) Modifier.border(2.dp, Neon, RoundedCornerShape(14.dp)) else Modifier).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(weekdayShort(ms), color = Muted, fontSize = 10.sp, maxLines = 1)
                    Text(if (rain) "☂" else if (n > 0) "${n}×" else "–", color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            (if (sched.isNotEmpty()) stringResource(R.string.plan_times, sched.joinToString(", ") { fmtMinutes(it.m) }) else stringResource(R.string.plan_none)) +
                " " + stringResource(R.string.plan_soil, l.soilMin),
            color = Muted, fontSize = 13.sp
        )
    }
}
