package com.hbrt.agro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import kotlin.math.max

private enum class Rg(val ms: Long, val label: Int) {
    H1(3_600_000L, R.string.a_range_1h), D1(86_400_000L, R.string.r_24h), D7(7 * 86_400_000L, R.string.r_7d),
    D30(30 * 86_400_000L, R.string.a_range_30d), ALL(0L, R.string.a_range_all)
}

private enum class Met(val label: Int, val unit: String, val sel: (Sample) -> Float) {
    T(R.string.h_temp, "°C", { it.t }), H(R.string.h_hum, "%", { it.h }), S(R.string.h_soil, "%", { it.s.toFloat() }), W(R.string.h_tank, "%", { it.w.toFloat() });
    val color: Color get() = when (this) { T -> Orange; H -> Cyan; S -> Leaf; W -> Blue }
}

/** Averages [data] into [n] equal time buckets between [from] and [to]; empty buckets copy the neighbour. */
private fun buckets(data: List<Sample>, from: Long, to: Long, n: Int, sel: (Sample) -> Float): List<Float> {
    val sum = FloatArray(n); val cnt = IntArray(n)
    val span = max(1L, to - from)
    data.forEach { s -> if (s.ts in from until to) { val i = ((s.ts - from) * n / span).toInt().coerceIn(0, n - 1); sum[i] += sel(s); cnt[i]++ } }
    if (cnt.all { it == 0 }) return emptyList()
    val out = MutableList(n) { if (cnt[it] > 0) sum[it] / cnt[it] else Float.NaN }
    val first = out.indexOfFirst { !it.isNaN() }
    for (i in 0 until first) out[i] = out[first]
    for (i in 1 until n) if (out[i].isNaN()) out[i] = out[i - 1]
    return out
}

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val use by GreenhouseClient.use.collectAsStateWithLifecycle()
    val latest by GreenhouseClient.latest.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(Rg.D1) }
    var met by rememberSaveable { mutableStateOf(Met.T) }
    var compare by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var week by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var alertLog by remember { mutableStateOf<List<AlertEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }
    val noData = stringResource(R.string.a_no_data)
    val rangeLabel = stringResource(range.label)

    val onSaved: (android.net.Uri?) -> Unit = { uri ->
        val f = pending; pending = null
        if (uri != null && f != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Exporter.copyTo(ctx, f, uri) }
            toast(ctx, if (ok) ctx.getString(R.string.a_saved_file, f.name) else ctx.getString(R.string.a_save_failed))
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), onSaved)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onSaved)

    LaunchedEffect(range, refresh, compare) {
        val now = System.currentTimeMillis()
        val from = when { range == Rg.ALL -> 0L; compare && range == Rg.D1 -> now - 2 * range.ms; else -> now - range.ms }
        data = withContext(Dispatchers.IO) { History.since(from) }
        week = withContext(Dispatchers.IO) { History.since(now - 7 * 86_400_000L) }
        alertLog = withContext(Dispatchers.IO) { History.alerts() }
    }
    // a new point arrives every few seconds: refresh the short ranges now and then
    LaunchedEffect(latest?.ts?.div(60_000)) { if (range == Rg.H1) refresh++ }

    fun export(pdf: Boolean, then: (File) -> Unit) {
        val now = System.currentTimeMillis()
        val d = if (compare && range == Rg.D1) data.filter { it.ts >= now - range.ms } else data
        if (d.isEmpty()) { toast(ctx, noData); return }
        busy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { if (pdf) Exporter.pdf(ctx, d, rangeLabel) else Exporter.csv(ctx, d) }
            busy = false; then(f)
        }
    }

    val now = System.currentTimeMillis()
    val from = if (range == Rg.ALL) (data.firstOrNull()?.ts ?: now) else now - range.ms
    val shown = data.filter { it.ts >= from }
    val n = when (range) { Rg.H1 -> 60; Rg.D1 -> 96; Rg.D7 -> 168; else -> 180 }
    val main = buckets(shown, from, now, n, met.sel)
    val prev = if (compare && range == Rg.D1) buckets(data, from - range.ms, from, n, met.sel) else emptyList()
    val series = buildList {
        if (prev.isNotEmpty()) add(Series(stringResource(R.string.yesterday), met.color, prev, dashed = true))
        add(Series(stringResource(met.label), met.color, main))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle(stringResource(R.string.h_title), stringResource(R.string.a_on_phone_sub)) }
        item { Chips { Rg.entries.forEach { rg -> Chip(stringResource(rg.label), range == rg) { range = rg; if (rg != Rg.D1) compare = false } } } }

        // chart card
        item {
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Met.entries.forEach { m ->
                        val sel = met == m
                        Text(stringResource(m.label), Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) m.color.copy(alpha = .18f) else CardHi)
                            .bounceClick { met = m }.padding(horizontal = 11.dp, vertical = 8.dp),
                            color = if (sel) m.color else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val st = shown.map(met.sel).stat()
                Row {
                    listOf(R.string.lowest to st?.min, R.string.average to st?.avg, R.string.highest to st?.max).forEach { (l, v) ->
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(l), color = Muted, fontSize = 12.sp)
                            Text(v?.let { it.f1() + met.unit } ?: "–", color = TextC, fontSize = 22.sp, style = Tnum)
                        }
                    }
                }
                LineChart(series, Modifier.fillMaxWidth().height(210.dp), stringResource(R.string.h_no_range))
                if (prev.isNotEmpty()) Legend(series)
                if (range == Rg.D1) SwitchRow(stringResource(R.string.compare), compare) { compare = it }
            }
        }

        // hour-of-day heatmap (last 7 days)
        if (week.size > 24) item { Heatmap(week, met) }

        // water and power per day
        use?.let { u -> if (u.days.isNotEmpty()) item { UsageBars(u) } }

        // summary
        item {
            SectionCard(stringResource(R.string.a_summary), stringResource(R.string.a_samples, shown.size)) {
                if (shown.isEmpty()) Text(stringResource(R.string.a_readings_saved, Prefs.logEverySec), color = Muted, fontSize = 13.sp)
                else {
                    fun pct(sel: (Sample) -> Boolean) = shown.count(sel) * 100 / shown.size
                    listOf("fan" to pct { it.fan }, "pump" to pct { it.pump }, "heater" to pct { it.heater }, "lights" to pct { it.lights }, "servo" to pct { it.vent }).forEach { (d, p) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DevIcon(d, true, devColor(d), Modifier.padding(end = 10.dp).width(20.dp).height(20.dp))
                            Text(devName(d), color = TextC, fontSize = 14.sp, modifier = Modifier.width(80.dp))
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
                                Box(Modifier.fillMaxWidth(p / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(devColor(d)))
                            }
                            Text("$p%", color = Muted, fontSize = 13.sp, style = Tnum, modifier = Modifier.padding(start = 10.dp).width(40.dp))
                        }
                    }
                }
            }
        }

        // export
        item {
            SectionCard(stringResource(R.string.a_export_title, rangeLabel), stringResource(R.string.a_export_sub)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton(stringResource(R.string.a_export_csv), !busy, Modifier.weight(1f)) { export(false) { Exporter.share(ctx, it, "text/csv") } }
                    SoftButton(stringResource(R.string.a_export_pdf), !busy, Modifier.weight(1f)) { export(true) { Exporter.share(ctx, it, "application/pdf") } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.a_save_csv), !busy, Neon, Modifier.weight(1f)) { export(false) { pending = it; saveCsv.launch(it.name) } }
                    SecondaryButton(stringResource(R.string.a_save_pdf), !busy, Neon, Modifier.weight(1f)) { export(true) { pending = it; savePdf.launch(it.name) } }
                }
                if (busy) Text(stringResource(R.string.a_preparing), color = Muted, fontSize = 12.sp)
            }
        }

        // alert history
        item {
            SectionCard(stringResource(R.string.a_alert_log)) {
                if (alertLog.isEmpty()) Text(stringResource(R.string.a_no_alerts), color = Muted, fontSize = 13.sp)
                alertLog.take(40).forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(Danger))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.msg, color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(dateStr(a.ts) + " · " + timeStr(a.ts), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                if (alertLog.isNotEmpty()) SecondaryButton(stringResource(R.string.a_clear)) {
                    scope.launch { withContext(Dispatchers.IO) { History.clearAlerts() }; refresh++ }
                }
            }
        }
    }
}

@Composable
private fun Heatmap(week: List<Sample>, met: Met) {
    // rows = days (oldest first), columns = hours
    val days = 7
    val today0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val start = today0 - (days - 1) * 86_400_000L
    val sum = Array(days) { FloatArray(24) }; val cnt = Array(days) { IntArray(24) }
    val cal = Calendar.getInstance()
    week.forEach { s ->
        if (s.ts < start) return@forEach
        val d = ((s.ts - start) / 86_400_000L).toInt().coerceIn(0, days - 1)
        cal.timeInMillis = s.ts
        val h = cal.get(Calendar.HOUR_OF_DAY)
        sum[d][h] += met.sel(s); cnt[d][h]++
    }
    val vals = (0 until days).flatMap { d -> (0 until 24).mapNotNull { h -> if (cnt[d][h] > 0) sum[d][h] / cnt[d][h] else null } }
    val lo = vals.minOrNull() ?: 0f; val hi = max(lo + 1f, vals.maxOrNull() ?: 1f)
    val c = met.color; val empty = Track
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H2(stringResource(R.string.heat_title))
        Row {
            Column(Modifier.width(34.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                for (d in 0 until days) Text(weekdayShort(start + d * 86_400_000L), color = Muted, fontSize = 10.sp, modifier = Modifier.height(16.dp))
            }
            Canvas(Modifier.weight(1f).aspectRatio(24f / 7f * 0.62f)) {
                val gap = 2.dp.toPx()
                val cw = (size.width - gap * 23) / 24; val ch = (size.height - gap * 6) / 7
                for (d in 0 until days) for (h in 0 until 24) {
                    val x = (if (rtl) 23 - h else h) * (cw + gap); val y = d * (ch + gap)
                    val col = if (cnt[d][h] == 0) empty else c.copy(alpha = .15f + .85f * ((sum[d][h] / cnt[d][h] - lo) / (hi - lo)).coerceIn(0f, 1f))
                    drawRoundRect(col, Offset(x, y), Size(cw, ch), CornerRadius(3.dp.toPx()))
                }
            }
        }
        Row(Modifier.padding(start = 34.dp)) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, s -> Text(s, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(if (i == 4) .01f else 1f)) }
        }
        Text(stringResource(R.string.heat_note) + "  " + lo.f1() + met.unit + " – " + hi.f1() + met.unit, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun UsageBars(u: Use) {
    val days = u.days.take(7).reversed()
    val mxL = max(0.1f, days.maxOf { it.l }); val mxK = max(0.01f, days.maxOf { it.kwh })
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H2(stringResource(R.string.usage_title))
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { i, d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(9.dp).height((92 * d.l / mxL).coerceAtLeast(2f).dp).clip(RoundedCornerShape(4.dp)).background(Blue))
                        Box(Modifier.width(9.dp).height((92 * d.kwh / mxK).coerceAtLeast(2f).dp).clip(RoundedCornerShape(4.dp)).background(Gold))
                    }
                    Text(weekdayShort(System.currentTimeMillis() - (days.size - i) * 86_400_000L), color = Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Legend(listOf(
            Series(stringResource(R.string.liters, days.sumOf { it.l.toDouble() }.toFloat().f1()), Blue, emptyList()),
            Series(stringResource(R.string.kwh, days.sumOf { it.kwh.toDouble() }.toFloat().fx(2)), Gold, emptyList()),
        ))
    }
}
