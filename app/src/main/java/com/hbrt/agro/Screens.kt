package com.hbrt.agro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Staggered fade-and-rise when the dashboard opens: the one orchestrated entrance. */
@Composable
fun Enter(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        a.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 36.dp.toPx() }) { content() }
}

@Composable
fun Pill(text: String, color: Color = Muted) {
    Text(
        text,
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = .06f)).padding(horizontal = 10.dp, vertical = 5.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
    )
}

/* =================== Dashboard =================== */

@Composable
fun DashboardTab(r: Reading?) {
    val live by GreenhouseClient.live.collectAsStateWithLifecycle()
    val alerts by GreenhouseClient.activeAlerts.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    val l = r?.limits ?: Limits()
    val score = r?.health()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Enter(0) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(CardHi, CardC)))
                    .border(1.dp, Border, RoundedCornerShape(26.dp)).padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r?.summary() ?: "Waiting for the first reading…",
                            color = if (alerts.isNotEmpty()) Danger else TextC,
                            fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(if (r?.auto != false) "Automatic" else "Manual", if (r?.auto != false) Neon else Orange)
                            if (r != null) Pill("Wi-Fi ${r.rssi} dBm")
                            if (r?.limits?.lightSched == true) Pill("Lights ${fmtMinutes(l.lightOn)}–${fmtMinutes(l.lightOff)}", Gold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    HealthRing(score, Modifier.size(124.dp))
                }
                Spacer(Modifier.height(10.dp))
                val last = GreenhouseClient.lastMsgAt
                Text(
                    (if (last == 0L) "No data yet" else "Updated ${(now - last).coerceAtLeast(0) / 1000}s ago") +
                        (r?.let { "   ·   running for ${fmtUptime(it.uptime)}" } ?: ""),
                    color = Muted, fontSize = 11.sp
                )
            }
        }

        AnimatedVisibility((r?.watering ?: 0) > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().card(18.dp, Blue.copy(alpha = .12f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DropIcon(true, Blue, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Watering: ${r?.watering ?: 0} s left", color = TextC, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { GreenhouseClient.command("pump", false) }) { Text("Stop", color = Blue) }
            }
        }

        AnimatedVisibility(alerts.size > 1, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.fillMaxWidth().card(18.dp, Danger.copy(alpha = .1f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Needs attention", color = Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                alerts.values.forEach { Text(it, color = TextC, fontSize = 13.sp) }
            }
        }

        Enter(1) { DeviceStrip(r) }

        Enter(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MetricTile(
                    "Air temp", r?.temperature, "°C", 1, Orange, live.map { it.temperature },
                    r != null && (r.temperature > l.tempMax || r.temperature < l.tempMin), Modifier.weight(1f)
                )
                MetricTile(
                    "Humidity", r?.humidity, "%", 0, Neon, live.map { it.humidity },
                    r != null && r.humidity > l.humMax, Modifier.weight(1f)
                )
            }
        }
        Enter(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MetricTile(
                    "Soil moisture", r?.soil?.toFloat(), "%", 0, Leaf, live.map { it.soil.toFloat() },
                    r != null && r.soil < l.soilMin, Modifier.weight(1f)
                )
                MetricTile(
                    "Soil temp", r?.soilTemp, "°C", 1, Gold, live.map { it.soilTemp },
                    r != null && !r.stOk, Modifier.weight(1f)
                )
            }
        }
        Enter(4) { TankCard(r?.water, l.waterMin, Modifier.fillMaxWidth()) }
        Enter(5) {
            ChartCard(
                "Climate, last 3 minutes",
                listOf(Series("Temp °C", Orange, live.map { it.temperature }), Series("Humidity %", Neon, live.map { it.humidity }))
            )
        }
    }
}

@Composable
fun DeviceStrip(r: Reading?) {
    Row(Modifier.fillMaxWidth().card().padding(vertical = 14.dp, horizontal = 6.dp)) {
        DeviceBadge("Fan", r?.fan == true, Neon, Modifier.weight(1f)) { FanIcon(r?.fan == true, it, Modifier.size(26.dp)) }
        DeviceBadge("Pump", r?.pump == true, Blue, Modifier.weight(1f)) { DropIcon(r?.pump == true, it, Modifier.size(26.dp)) }
        DeviceBadge("Lights", r?.lights == true, Gold, Modifier.weight(1f)) { BulbIcon(r?.lights == true, it, Modifier.size(26.dp)) }
        DeviceBadge("Heater", r?.heater == true, Orange, Modifier.weight(1f)) { FlameIcon(r?.heater == true, it, Modifier.size(26.dp)) }
        DeviceBadge("Vent", r?.servo == true, Neon, Modifier.weight(1f)) { VentIcon(r?.servo == true, it, Modifier.size(26.dp)) }
    }
}

@Composable
fun DeviceBadge(label: String, on: Boolean, color: Color, modifier: Modifier, icon: @Composable (Color) -> Unit) {
    val col by animateColorAsState(if (on) color else Muted, tween(400), label = "badge")
    val bg by animateColorAsState(if (on) color.copy(alpha = .14f) else Color.White.copy(alpha = .04f), tween(400), label = "bg")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { icon(col) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextC, fontSize = 11.sp)
        Text(if (on) "On" else "Off", color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/* =================== Controls =================== */

@Composable
fun ControlsTab(r: Reading?, online: Boolean) {
    val ctx = LocalContext.current
    val auto = r?.auto ?: true
    var confirm by remember { mutableStateOf(false) }
    fun cmd(d: String, v: Boolean) {
        if (!GreenhouseClient.command(d, v)) toast(ctx, "Offline. Command not sent.")
    }
    val manualOk = online && !auto

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageTitle("Control", "Tap a device to switch it")

        Column(Modifier.fillMaxWidth().card(20.dp, if (auto) Neon.copy(alpha = .08f) else CardC).padding(16.dp)) {
            SwitchRow(
                "Automatic mode", auto,
                if (auto) "The greenhouse runs itself using your limits." else "You're in charge. Pump safety stays on."
            ) { if (online) cmd("auto", it) else toast(ctx, "Offline") }
        }

        AnimatedVisibility(auto, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text("Turn off automatic mode to switch devices by hand.", color = Muted, fontSize = 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceTile("Fan", onOff(r?.fan), r?.fan == true, Neon, manualOk, Modifier.weight(1f), { FanIcon(r?.fan == true, it) }) {
                cmd("fan", r?.fan != true)
            }
            DeviceTile("Pump", onOff(r?.pump), r?.pump == true, Blue, manualOk && r?.pumpLock != true, Modifier.weight(1f), { DropIcon(r?.pump == true, it) }) {
                cmd("pump", r?.pump != true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceTile("Lights", onOff(r?.lights), r?.lights == true, Gold, manualOk, Modifier.weight(1f), { BulbIcon(r?.lights == true, it) }) {
                cmd("lights", r?.lights != true)
            }
            DeviceTile("Heater", onOff(r?.heater), r?.heater == true, Orange, manualOk, Modifier.weight(1f), { FlameIcon(r?.heater == true, it) }) {
                cmd("heater", r?.heater != true)
            }
        }
        DeviceTile(
            "Roof vent", if (r?.servo == true) "Open" else "Closed", r?.servo == true, Neon, manualOk,
            Modifier.fillMaxWidth(), { VentIcon(r?.servo == true, it) }
        ) { cmd("servo", r?.servo != true) }

        SectionCard("Water now", "Runs the pump for a set time, in any mode.") {
            if (r != null && !r.modern) {
                Text("Update the ESP32 to firmware 4.0 to use this.", color = Muted, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { s ->
                        val ok = online && r != null && !r.pumpLock && r.water > (r.limits?.waterMin ?: 5) && s <= (r.limits?.pumpMax ?: 60)
                        Box(
                            Modifier.weight(1f).height(48.dp).alpha(if (ok) 1f else .4f)
                                .card(14.dp, Blue.copy(alpha = .12f))
                                .bounceClick(ok) { if (!GreenhouseClient.waterNow(s)) toast(ctx, "Offline") },
                            contentAlignment = Alignment.Center
                        ) { Text("$s s", color = Blue, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            val note = when {
                r == null -> null
                r.pumpLock -> "The pump is resting for 5 minutes after a long run."
                r.water <= (r.limits?.waterMin ?: 5) -> "The tank is empty, so watering is blocked."
                else -> null
            }
            note?.let { Text(it, color = Danger, fontSize = 13.sp) }
        }

        SecondaryButton("Turn everything off", enabled = online, color = Danger) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Turn everything off?") },
            text = { Text("Switches to manual mode, turns off the fan, pump, lights and heater, and closes the vent.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    listOf("fan", "pump", "lights", "heater", "servo").forEach { cmd(it, false) }
                }) { Text("Turn off", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

fun onOff(b: Boolean?) = when (b) { true -> "On"; false -> "Off"; null -> "--" }

@Composable
fun DeviceTile(
    label: String, state: String, on: Boolean, color: Color, enabled: Boolean, modifier: Modifier,
    icon: @Composable (Color) -> Unit, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val bg by animateColorAsState(if (on) color.copy(alpha = .16f) else CardC, tween(350), label = "bg")
    val bd by animateColorAsState(if (on) color else Border, tween(350), label = "bd")
    val ic by animateColorAsState(if (on) color else Muted, tween(350), label = "ic")
    Column(
        modifier.height(116.dp).alpha(if (enabled) 1f else .45f).clip(shape).background(bg).border(1.5.dp, bd, shape)
            .bounceClick(enabled, onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        icon(ic)
        Column {
            Text(label, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(state, color = ic, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/* =================== History & export =================== */

enum class Range(val label: String, val ms: Long) {
    H1("1 hour", 3_600_000L),
    D1("24 hours", 86_400_000L),
    D7("7 days", 7 * 86_400_000L),
    D30("30 days", 30 * 86_400_000L),
    ALL("All", 0L),
}

@Composable
fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Neon, selectedLabelColor = Color.Black, labelColor = TextC
        )
    )
}

@Composable
fun DataTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(Range.D1) }
    var data by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var alertLog by remember { mutableStateOf<List<AlertEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }
    val tf = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    val onSaved: (android.net.Uri?) -> Unit = { uri ->
        val f = pending
        pending = null
        if (uri != null && f != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Exporter.copyTo(ctx, f, uri) }
            toast(ctx, if (ok) "Saved ${f.name}" else "Could not save the file")
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), onSaved)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onSaved)

    LaunchedEffect(range, refresh) {
        val from = if (range == Range.ALL) 0L else System.currentTimeMillis() - range.ms
        data = withContext(Dispatchers.IO) { History.since(from) }
        alertLog = withContext(Dispatchers.IO) { History.alerts() }
    }

    fun export(pdf: Boolean, then: (File) -> Unit) {
        if (data.isEmpty()) { toast(ctx, "No data in this range yet"); return }
        busy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { if (pdf) Exporter.pdf(ctx, data, range.label) else Exporter.csv(ctx, data) }
            busy = false
            then(f)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageTitle("History", "Saved on this phone for 6 months") }
                TextButton(onClick = { refresh++ }) { Text("Refresh", color = Neon) }
            }
        }
        item { Chips { Range.entries.forEach { rg -> Chip(rg.label, range == rg) { range = rg } } } }
        item { StatsCard(data) }
        item {
            val d = data.downsample(300)
            ChartCard(
                "Trend, ${range.label}",
                listOf(
                    Series("Temp", Orange, d.map { it.t }), Series("Hum", Neon, d.map { it.h }),
                    Series("Soil", Leaf, d.map { it.s.toFloat() }), Series("Tank", Blue, d.map { it.w.toFloat() }),
                ),
                220.dp
            )
        }
        item {
            SectionCard("Export ${range.label}", "Share sends to WhatsApp, email or Drive. Save picks a folder.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Share CSV", !busy, modifier = Modifier.weight(1f)) { export(false) { Exporter.share(ctx, it, "text/csv") } }
                    SecondaryButton("Share PDF", !busy, modifier = Modifier.weight(1f)) { export(true) { Exporter.share(ctx, it, "application/pdf") } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Save CSV", !busy, Neon, Modifier.weight(1f)) { export(false) { pending = it; saveCsv.launch(it.name) } }
                    SecondaryButton("Save PDF", !busy, Neon, Modifier.weight(1f)) { export(true) { pending = it; savePdf.launch(it.name) } }
                }
                if (busy) Text("Preparing file…", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            Column {
                Text("Latest readings", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TableRow(listOf("Time", "Temp", "Hum", "Soil", "S.T", "Tank"), header = true)
            }
        }
        val recent = data.takeLast(100).asReversed()
        if (recent.isEmpty()) item { Text("No readings stored yet.", color = Muted, fontSize = 13.sp) }
        items(recent, key = { it.ts }) { s ->
            TableRow(listOf(tf.format(Date(s.ts)), s.t.f1(), s.h.f1(), "${s.s}", s.st.f1(), "${s.w}"))
        }
        item {
            SectionCard("Alert log") {
                if (alertLog.isEmpty()) Text("No alerts recorded.", color = Muted, fontSize = 13.sp)
                alertLog.take(50).forEach { a ->
                    Column {
                        Text(a.msg, color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(a.ts)), color = Muted, fontSize = 11.sp)
                    }
                }
                if (alertLog.isNotEmpty()) SecondaryButton("Clear log") {
                    scope.launch { withContext(Dispatchers.IO) { History.clearAlerts() }; refresh++ }
                }
            }
        }
    }
}

@Composable
fun StatsCard(data: List<Sample>) {
    SectionCard("Summary", "${data.size} samples") {
        if (data.isEmpty()) {
            Text("Readings are saved every ${Prefs.logEverySec} s while the app or background monitor is connected.", color = Muted, fontSize = 13.sp)
            return@SectionCard
        }
        TableRow(listOf("", "Min", "Avg", "Max"), header = true, first = 2f)
        listOf(
            "Air temp °C" to data.map { it.t }, "Humidity %" to data.map { it.h },
            "Soil %" to data.map { it.s.toFloat() }, "Soil temp °C" to data.map { it.st },
            "Tank %" to data.map { it.w.toFloat() },
        ).forEach { (n, v) -> v.stat()?.let { TableRow(listOf(n, it.min.f1(), it.avg.f1(), it.max.f1()), first = 2f) } }
        fun pct(sel: (Sample) -> Boolean) = data.count(sel) * 100 / data.size
        Text(
            "Time on: fan ${pct { it.fan }}%, pump ${pct { it.pump }}%, heater ${pct { it.heater }}%, lights ${pct { it.lights }}%",
            color = Muted, fontSize = 12.sp
        )
    }
}

@Composable
fun TableRow(cells: List<String>, header: Boolean = false, first: Float = 1.6f) {
    Row(Modifier.fillMaxWidth().background(if (header) DarkC else Color.Transparent).padding(vertical = 6.dp, horizontal = 4.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c, Modifier.weight(if (i == 0) first else 1f),
                color = if (header) Neon else TextC, fontSize = 12.sp, style = Tnum,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End
            )
        }
    }
}

/* =================== AI =================== */

@Composable
fun AiTab(r: Reading?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var out by rememberSaveable { mutableStateOf("Ask anything about your greenhouse, or tap one of the quick actions.") }
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val alerts by GreenhouseClient.activeAlerts.collectAsStateWithLifecycle()

    fun run(prompt: String) {
        busy = true
        out = "Thinking…"
        scope.launch {
            out = try {
                Ai.ask(prompt)
            } catch (e: Exception) {
                "Could not reach the AI (${e.message}).\n\nCheck the URL in Settings, that the phone is on the same Wi-Fi, " +
                    "and that Ollama was started with OLLAMA_HOST=0.0.0.0."
            }
            busy = false
        }
    }
    val snap = r?.let {
        String.format(
            Locale.US, "Temp=%.1fC, Humidity=%.0f%%, Soil moisture=%d%%, Soil temp=%.1fC, Water tank=%d%%",
            it.temperature, it.humidity, it.soil, it.soilTemp, it.water
        )
    } ?: "no live data"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageTitle("AI assistant", "Runs on your own Ollama server")
        SelectionContainer {
            Text(
                out,
                Modifier.fillMaxWidth().heightIn(min = 220.dp).card(18.dp, DarkC).padding(16.dp),
                color = if (busy) Muted else TextC, fontSize = 14.sp, lineHeight = 21.sp
            )
        }
        OutlinedTextField(q, { q = it }, label = { Text("Your question") }, colors = field(), modifier = Modifier.fillMaxWidth())
        PrimaryButton("Ask", enabled = !busy) {
            run("You are an AI greenhouse assistant. Live data: $snap. Question: ${q.ifBlank { "Give a one-sentence status summary." }}")
        }
        Chips {
            Chip("Diagnose", false) {
                if (!busy) run(
                    "You are a greenhouse technician. Live data: $snap. Active alerts: ${alerts.values.joinToString("; ").ifEmpty { "none" }}. " +
                        "Give a short diagnosis and up to 3 concrete actions."
                )
            }
            Chip("Predict harvest", false) {
                if (!busy) scope.launch {
                    val d = withContext(Dispatchers.IO) { History.since(System.currentTimeMillis() - 7 * 86_400_000L) }
                    if (d.size < 10) toast(ctx, "Not enough history yet. Keep the app connected a little longer.")
                    else run(
                        "You are an expert agronomist. 7-day greenhouse averages: Temp=${d.map { it.t }.average().toFloat().f1()}C, " +
                            "Humidity=${d.map { it.h }.average().toFloat().f1()}%, Soil=${d.map { it.s.toFloat() }.average().toFloat().f1()}%, " +
                            "Soil temp=${d.map { it.st }.average().toFloat().f1()}C. Predict harvest timing and yield for tomatoes, and what to improve."
                    )
                }
            }
            Chip("Pest risk", false) {
                if (!busy) run("You are a plant-protection expert. Greenhouse data: $snap. Which pests or diseases are likely under these conditions, and how do I prevent them? Be brief.")
            }
            Chip("Save water", false) {
                if (!busy) run("Greenhouse data: $snap. Give 3 short, practical tips to use less irrigation water without stressing the plants.")
            }
        }
    }
}

/* =================== Settings =================== */

@Composable
fun ConfigTab(r: Reading?) {
    val ctx = LocalContext.current
    var filled by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var tMax by rememberSaveable { mutableStateOf("") }
    var tMin by rememberSaveable { mutableStateOf("") }
    var hMax by rememberSaveable { mutableStateOf("") }
    var sMin by rememberSaveable { mutableStateOf("") }
    var wMin by rememberSaveable { mutableStateOf("") }
    var pMax by rememberSaveable { mutableStateOf("") }
    var sched by rememberSaveable { mutableStateOf(false) }
    var lOn by rememberSaveable { mutableIntStateOf(360) }
    var lOff by rememberSaveable { mutableIntStateOf(1200) }
    var preset by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(r?.limits) {
        val l = r?.limits
        if (l != null && !filled) {
            name = r?.name ?: ""
            tMax = l.tempMax.f1(); tMin = l.tempMin.f1(); hMax = l.humMax.f1()
            sMin = "${l.soilMin}"; wMin = "${l.waterMin}"; pMax = "${l.pumpMax}"
            sched = l.lightSched; lOn = l.lightOn; lOff = l.lightOff
            filled = true
        }
    }
    var bg by remember { mutableStateOf(Prefs.background) }
    var daily by remember { mutableStateOf(Prefs.dailyReport) }
    var every by remember { mutableIntStateOf(Prefs.logEverySec) }
    var aiUrl by rememberSaveable { mutableStateOf(Prefs.aiUrl) }
    var aiModel by rememberSaveable { mutableStateOf(Prefs.aiModel) }
    val modern = r?.modern == true

    fun save() {
        val l = Limits(
            tMax.toFloatOrNull() ?: Float.NaN, tMin.toFloatOrNull() ?: Float.NaN, hMax.toFloatOrNull() ?: Float.NaN,
            sMin.toIntOrNull() ?: -1, wMin.toIntOrNull() ?: -1, pMax.toIntOrNull() ?: -1, sched, lOn, lOff
        )
        when {
            l.tempMax.isNaN() || l.tempMin.isNaN() || l.humMax.isNaN() || l.soilMin < 0 || l.waterMin < 0 || l.pumpMax < 0 ->
                toast(ctx, "Fill in every field with a number")
            l.tempMin >= l.tempMax -> toast(ctx, "Min temperature must be below max")
            l.pumpMax !in 5..600 -> toast(ctx, "Pump max run must be 5 to 600 seconds")
            GreenhouseClient.send(l.toJson()) -> toast(ctx, "Saved on the greenhouse")
            else -> toast(ctx, "Offline. Not saved.")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageTitle("Settings")

        SectionCard("Greenhouse name", if (modern) "Saved on the ESP32" else "Saved on this phone (update firmware to save it on the board)") {
            OutlinedTextField(
                name, { if (it.length <= 32) name = it }, singleLine = true, colors = field(),
                placeholder = { Text("e.g. Tomato house") }, modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton("Save name", enabled = name.isNotBlank()) {
                if (modern) {
                    if (GreenhouseClient.rename(name.trim())) toast(ctx, "Renamed") else toast(ctx, "Offline. Not saved.")
                } else {
                    Prefs.saveGreenhouse(SavedGh(name.trim(), GreenhouseClient.address))
                    toast(ctx, "Renamed")
                }
            }
        }

        SectionCard("What are you growing?", "Fills in good limits for the crop. Then tap Save.") {
            Chips {
                PRESETS.forEach { p ->
                    Chip(p.name, preset == p.name) {
                        preset = p.name
                        tMin = p.tempMin.f1(); tMax = p.tempMax.f1(); hMax = p.humMax.f1(); sMin = "${p.soilMin}"
                    }
                }
            }
        }

        SectionCard("Limits", "Automatic mode keeps the greenhouse inside these.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Max temp °C", tMax, Modifier.weight(1f)) { tMax = it; preset = "" }
                NumField("Min temp °C", tMin, Modifier.weight(1f)) { tMin = it; preset = "" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Max humidity %", hMax, Modifier.weight(1f)) { hMax = it; preset = "" }
                NumField("Water below %", sMin, Modifier.weight(1f)) { sMin = it; preset = "" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Tank empty at %", wMin, Modifier.weight(1f)) { wMin = it }
                NumField("Pump max run s", pMax, Modifier.weight(1f)) { pMax = it }
            }
            PrimaryButton("Save to greenhouse") { save() }
        }

        SectionCard("Grow lights", if (modern) "Switches the lights by the clock in automatic mode" else "Needs firmware 4.0") {
            SwitchRow("Light schedule", sched) { sched = it }
            AnimatedVisibility(sched, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeStepper("Lights on", lOn) { lOn = it }
                    TimeStepper("Lights off", lOff) { lOff = it }
                    val hours = ((lOff - lOn + 1440) % 1440) / 60f
                    Text("${hours.f1()} hours of light per day", color = Muted, fontSize = 12.sp)
                    if (r != null && !r.timeOk && modern) Text("The board's clock is set by the app each time it connects.", color = Muted, fontSize = 12.sp)
                }
            }
            PrimaryButton("Save schedule", enabled = modern) { save() }
        }

        SectionCard("Background and reports") {
            SwitchRow("Keep monitoring when the app is closed", bg, "Logs readings and sends alerts with the screen off") {
                bg = it; Prefs.background = it
                if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
            }
            SwitchRow("Daily PDF report at 20:00", daily, "Saved to Downloads/HBRT (Android 10+)") {
                daily = it; Prefs.dailyReport = it
                if (it) ReportWorker.schedule(ctx) else ReportWorker.cancel(ctx)
            }
            Text("Save a reading every", color = TextC, fontSize = 14.sp)
            Chips { listOf(5, 10, 30, 60).forEach { s -> Chip("$s s", every == s) { every = s; Prefs.logEverySec = s } } }
        }

        SectionCard("Local AI (Ollama)") {
            OutlinedTextField(aiUrl, { aiUrl = it }, label = { Text("API endpoint URL") }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(aiModel, { aiModel = it }, label = { Text("Model") }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
            PrimaryButton("Save AI settings") {
                Prefs.aiUrl = aiUrl.trim(); Prefs.aiModel = aiModel.trim(); toast(ctx, "AI settings saved")
            }
        }

        SectionCard("Connection", GreenhouseClient.url) {
            if (r != null) Text("Board ${r.id.ifBlank { "unknown" }}   ·   firmware ${r.fw}   ·   Wi-Fi ${r.rssi} dBm", color = Muted, fontSize = 12.sp)
            SecondaryButton("Switch greenhouse", color = Neon) { leaveGreenhouse(ctx) }
        }

        Column(Modifier.fillMaxWidth().card().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LeafMark(Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Brand(22, "Agro")
            Text("Smart Greenhouse  ·  version 4.0", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Credits()
        }
    }
}

@Composable
fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f))
        StepButton("−") { onChange((minutes - 30 + 1440) % 1440) }
        Text(fmtMinutes(minutes), color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = Tnum, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        StepButton("+") { onChange((minutes + 30) % 1440) }
    }
}

@Composable
fun StepButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).card(14.dp, CardHi).bounceClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = field(), modifier = modifier
    )
}
