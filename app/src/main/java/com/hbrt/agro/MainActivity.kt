package com.hbrt.agro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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

/* ---------- Brand colours (same as the old web app) ---------- */
val Bg = Color(0xFF0B1410)
val CardC = Color(0xFF12201A)
val Border = Color(0xFF1F3A2C)
val Neon = Color(0xFF39FF14)
val Orange = Color(0xFFFF8C00)
val TextC = Color(0xFFE0E0E0)
val Muted = Color(0xFF7A9080)
val Danger = Color(0xFFFF4C4C)
val DarkC = Color(0xFF060D0A)
val Blue = Color(0xFF00AAFF)
val Gold = Color(0xFFFFD700)
val Forest = Color(0xFF2E8B57)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon, onPrimary = Color.Black, secondary = Orange,
                    background = Bg, surface = CardC, onSurface = TextC, onBackground = TextC, error = Danger
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { HbrtApp() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null && Prefs.autoConnect && Prefs.lastAddress.isNotBlank() &&
            GreenhouseClient.link.value == Link.IDLE
        ) {
            GreenhouseClient.connect(Prefs.lastAddress)
        }
    }

    override fun onResume() {
        super.onResume()
        GreenhouseClient.kick() // phone woke up: reconnect without waiting for the back-off timer
    }
}

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

fun Modifier.card(radius: Dp = 16.dp) =
    clip(RoundedCornerShape(radius)).background(CardC).border(1.dp, Border, RoundedCornerShape(radius))

@Composable
fun HbrtApp() {
    val linked by GreenhouseClient.linked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(linked) {
        if (linked && Prefs.background) MonitorService.start(ctx)
    }
    if (linked) MainScreen() else ConnectScreen()
}

/* =================== Shared pieces =================== */

@Composable
fun Brand(size: Int = 24, suffix: String = "") {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Neon)) { append("HBRT") }
            withStyle(SpanStyle(color = Color.White)) { append(".") }
            if (suffix.isNotEmpty()) withStyle(SpanStyle(color = TextC)) { append(" $suffix") }
        },
        fontSize = size.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
    )
}

@Composable
fun Credits() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Developed by", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text("Mostafa Mesbah Aabedi", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Milad Ahmadi", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun SecondaryButton(
    text: String, enabled: Boolean = true, color: Color = Orange,
    modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) color else Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Neon, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun PageTitle(text: String) = Text(text, color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Bold)

@Composable
fun field() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon, unfocusedBorderColor = Border, focusedLabelColor = Neon,
    unfocusedLabelColor = Muted, cursorColor = Neon, focusedContainerColor = DarkC, unfocusedContainerColor = DarkC
)

/* =================== Connect screen =================== */

@Composable
fun ConnectScreen() {
    val link by GreenhouseClient.link.collectAsStateWithLifecycle()
    val err by GreenhouseClient.error.collectAsStateWithLifecycle()
    var addr by rememberSaveable { mutableStateOf(Prefs.lastAddress) }
    val busy = link == Link.CONNECTING || link == Link.RECONNECTING

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().card(24.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Brand(28, "Agro")
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter the ESP32 IP address or your Cloudflare tunnel URL",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = addr, onValueChange = { addr = it }, singleLine = true,
                placeholder = { Text("192.168.1.50 or https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (addr.isNotBlank()) GreenhouseClient.connect(addr) }),
                colors = field(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (busy) "Connecting…" else "Connect", enabled = !busy && addr.isNotBlank()) {
                GreenhouseClient.connect(addr)
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { GreenhouseClient.disconnect() }) { Text("Cancel", color = Muted) }
            }
            if (err.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(err, color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(26.dp))
            Credits()
        }
    }
}

/* =================== Main screen =================== */

enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Controls("Controls", Icons.Filled.Build),
    Data("Data", Icons.AutoMirrored.Filled.List),
    Ai("AI", Icons.Filled.Star),
    Config("Config", Icons.Filled.Settings),
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    val r by GreenhouseClient.latest.collectAsStateWithLifecycle()
    val link by GreenhouseClient.link.collectAsStateWithLifecycle()
    val attempt by GreenhouseClient.attempt.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        topBar = { TopBar(link, r) },
        bottomBar = {
            NavigationBar(containerColor = DarkC) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black, indicatorColor = Neon, selectedTextColor = Neon,
                            unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (link != Link.ONLINE) {
                Text(
                    if (attempt > 0) "Connection lost. Retrying (attempt $attempt)…" else "Connecting to the greenhouse…",
                    Modifier.fillMaxWidth().background(Orange).padding(6.dp),
                    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
            when (tab) {
                Tab.Dashboard -> DashboardTab(r)
                Tab.Controls -> ControlsTab(r, link == Link.ONLINE)
                Tab.Data -> DataTab()
                Tab.Ai -> AiTab(r)
                Tab.Config -> ConfigTab(r)
            }
        }
    }
}

@Composable
fun TopBar(link: Link, r: Reading?) {
    Row(
        Modifier.fillMaxWidth().background(DarkC).statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Brand()
        Spacer(Modifier.weight(1f))
        val dot = when (link) {
            Link.ONLINE -> Neon
            Link.CONNECTING, Link.RECONNECTING -> Orange
            else -> Danger
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                link != Link.ONLINE -> "Offline"
                r?.auto != false -> "Auto"
                else -> "Manual"
            },
            color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (alerts.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Danger.copy(alpha = .12f))
                    .border(1.dp, Danger, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                alerts.values.forEach { Text("⚠  $it", color = Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        Row(
            Modifier.fillMaxWidth().card(12.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniStat("Wi-Fi", r?.let { "${it.rssi} dBm" } ?: "--")
            MiniStat("Uptime", r?.let { fmtUptime(it.uptime) } ?: "--")
            val last = GreenhouseClient.lastMsgAt
            MiniStat("Updated", if (last == 0L) "--" else "${(now - last).coerceAtLeast(0) / 1000}s ago")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Gauge("Air temp", r?.temperature, "°C", 50f, Orange, 1, Modifier.weight(1f))
            Gauge("Humidity", r?.humidity, "%", 100f, Neon, 0, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Gauge("Soil moisture", r?.soil?.toFloat(), "%", 100f, Forest, 0, Modifier.weight(1f))
            Gauge("Soil temp", r?.soilTemp, "°C", 50f, Gold, 1, Modifier.weight(1f))
        }
        Gauge("Water tank", r?.water?.toFloat(), "%", 100f, Blue, 0, Modifier.fillMaxWidth())
        ChartCard(
            "Temperature and humidity (last 3 min)",
            listOf(Series("Temp °C", Orange, live.map { it.temperature }), Series("Humidity %", Neon, live.map { it.humidity }))
        )
        ChartCard(
            "Soil and tank (last 3 min)",
            listOf(Series("Soil %", Forest, live.map { it.soil.toFloat() }), Series("Tank %", Blue, live.map { it.water.toFloat() }))
        )
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = TextC, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Gauge(label: String, value: Float?, unit: String, max: Float, color: Color, decimals: Int, modifier: Modifier) {
    val target = ((value ?: 0f) / max).coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, tween(500), label = "gauge")
    Column(modifier.card().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 8.dp.toPx()
                val tl = Offset(s / 2, s / 2)
                val sz = Size(size.width - s, size.height - s)
                drawArc(Color.White.copy(alpha = .06f), 0f, 360f, false, tl, sz, style = Stroke(s))
                drawArc(color, -90f, 360f * anim, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--",
                    color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Bold
                )
                Text(unit, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp, start = 1.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* =================== Charts (drawn natively, no library) =================== */

data class Series(val name: String, val color: Color, val values: List<Float>)

@Composable
fun ChartCard(title: String, series: List<Series>, height: Dp = 170.dp) {
    Column(Modifier.fillMaxWidth().card().padding(14.dp)) {
        Text(title, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LineChart(series, Modifier.fillMaxWidth().height(height))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp, 3.dp).background(s.color))
                    Spacer(Modifier.width(5.dp))
                    Text(s.name + (s.values.lastOrNull()?.let { "  ${it.f1()}" } ?: ""), color = TextC, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun LineChart(series: List<Series>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = Muted, fontSize = 10.sp)
    Canvas(modifier) {
        val all = series.flatMap { it.values }
        if (all.size < 4) {
            drawText(measurer, "Waiting for data…", Offset(0f, size.height / 2 - 8.dp.toPx()), axis)
            return@Canvas
        }
        var lo = all.min()
        var hi = all.max()
        if (hi - lo < 2f) { hi += 1f; lo -= 1f }
        val padL = 30.dp.toPx()
        val w = size.width - padL
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(Forest.copy(alpha = .18f), Offset(padL, y), Offset(size.width, y), 1f)
            val v = hi - (hi - lo) * i / 3f
            drawText(
                measurer, String.format(Locale.US, "%.0f", v),
                Offset(0f, (y - 7.dp.toPx()).coerceIn(0f, h - 14.dp.toPx())), axis
            )
        }
        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val n = s.values.size
            val path = Path()
            s.values.forEachIndexed { i, v ->
                val x = padL + w * i / (n - 1)
                val y = h - (v - lo) / (hi - lo) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageTitle("Controls")
        Text(
            if (auto) "Auto mode runs the greenhouse from the limits in Config. Turn it off to control devices yourself."
            else "Manual mode: devices stay as you set them. Pump safety still applies.",
            color = Muted, fontSize = 13.sp
        )
        ToggleTile("Auto mode", if (auto) "On" else "Off", auto, Neon, online, Modifier.fillMaxWidth()) { cmd("auto", !auto) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleTile("Fan", onOff(r?.fan), r?.fan == true, Orange, manualOk, Modifier.weight(1f)) { cmd("fan", r?.fan != true) }
            ToggleTile("Pump", onOff(r?.pump), r?.pump == true, Danger, manualOk && r?.pumpLock != true, Modifier.weight(1f)) {
                cmd("pump", r?.pump != true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleTile("Lights", onOff(r?.lights), r?.lights == true, Orange, manualOk, Modifier.weight(1f)) { cmd("lights", r?.lights != true) }
            ToggleTile("Heater", onOff(r?.heater), r?.heater == true, Orange, manualOk, Modifier.weight(1f)) { cmd("heater", r?.heater != true) }
        }
        ToggleTile("Vent", if (r?.servo == true) "Open" else "Closed", r?.servo == true, Orange, manualOk, Modifier.fillMaxWidth()) {
            cmd("servo", r?.servo != true)
        }
        val note = when {
            r == null -> null
            r.pumpLock -> "The pump is locked for 5 minutes to cool down after a long run."
            r.water <= (r.limits?.waterMin ?: 5) -> "The water tank is empty, so the pump is blocked."
            else -> null
        }
        note?.let { Text(it, color = Danger, fontSize = 13.sp) }
        Spacer(Modifier.height(4.dp))
        SecondaryButton("Turn everything off", enabled = online, color = Danger) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Turn everything off?") },
            text = { Text("This switches to manual mode, turns off the fan, pump, lights and heater, and closes the vent.") },
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
fun ToggleTile(label: String, state: String, on: Boolean, onColor: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.height(72.dp).alpha(if (enabled) 1f else .4f).clip(shape)
            .background(if (on) onColor else CardC).border(1.dp, if (on) onColor else Border, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        val fg = if (on) Color.Black else TextC
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(state, color = if (on) Color.Black.copy(alpha = .7f) else Muted, fontSize = 12.sp)
    }
}

/* =================== Data & export =================== */

enum class Range(val label: String, val ms: Long) {
    H1("1 hour", 3_600_000L),
    D1("24 hours", 86_400_000L),
    D7("7 days", 7 * 86_400_000L),
    D30("30 days", 30 * 86_400_000L),
    ALL("All", 0L),
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
            val f = withContext(Dispatchers.IO) {
                if (pdf) Exporter.pdf(ctx, data, range.label) else Exporter.csv(ctx, data)
            }
            busy = false
            then(f)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PageTitle("Data")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("Refresh", color = Neon) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { rg ->
                    FilterChip(
                        selected = range == rg, onClick = { range = rg }, label = { Text(rg.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Neon, selectedLabelColor = Color.Black, labelColor = TextC
                        )
                    )
                }
            }
        }
        item { StatsCard(data) }
        item {
            val d = data.downsample(300)
            ChartCard(
                "History (${range.label})",
                listOf(
                    Series("Temp", Orange, d.map { it.t }), Series("Hum", Neon, d.map { it.h }),
                    Series("Soil", Forest, d.map { it.s.toFloat() }), Series("Tank", Blue, d.map { it.w.toFloat() }),
                ),
                220.dp
            )
        }
        item {
            SectionCard("Export ${range.label}") {
                Text("Share sends the file to WhatsApp, email or Drive. Save lets you pick a folder on the phone.", color = Muted, fontSize = 12.sp)
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
                Text("Latest readings", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    SectionCard("Summary  (${data.size} samples)") {
        if (data.isEmpty()) {
            Text("Readings are saved every ${Prefs.logEverySec} s while the app or background monitor is connected.", color = Muted, fontSize = 13.sp)
            return@SectionCard
        }
        TableRow(listOf("", "Min", "Avg", "Max"), header = true, first = 2f)
        listOf(
            "Air temp °C" to data.map { it.t }, "Humidity %" to data.map { it.h },
            "Soil %" to data.map { it.s.toFloat() }, "Soil temp °C" to data.map { it.st },
            "Tank %" to data.map { it.w.toFloat() },
        ).forEach { (n, v) ->
            v.stat()?.let { TableRow(listOf(n, it.min.f1(), it.avg.f1(), it.max.f1()), first = 2f) }
        }
        fun pct(sel: (Sample) -> Boolean) = data.count(sel) * 100 / data.size
        Text(
            "Time on: fan ${pct { it.fan }}%, pump ${pct { it.pump }}%, heater ${pct { it.heater }}%, lights ${pct { it.lights }}%",
            color = Muted, fontSize = 12.sp
        )
    }
}

@Composable
fun TableRow(cells: List<String>, header: Boolean = false, first: Float = 1.6f) {
    Row(
        Modifier.fillMaxWidth().background(if (header) DarkC else Color.Transparent).padding(vertical = 5.dp, horizontal = 4.dp)
    ) {
        cells.forEachIndexed { i, c ->
            Text(
                c, Modifier.weight(if (i == 0) first else 1f),
                color = if (header) Neon else TextC, fontSize = 12.sp,
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
    var out by rememberSaveable { mutableStateOf("Ask a question about your greenhouse.") }
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
                "Could not reach the AI (${e.message}).\n\nCheck the URL in Config, that the phone is on the same Wi-Fi, " +
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
        PageTitle("AI assistant")
        SelectionContainer {
            Text(
                out,
                Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(12.dp)).background(DarkC)
                    .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(14.dp),
                color = TextC, fontSize = 14.sp, lineHeight = 21.sp
            )
        }
        OutlinedTextField(q, { q = it }, label = { Text("Your question") }, colors = field(), modifier = Modifier.fillMaxWidth())
        PrimaryButton("Ask", enabled = !busy) {
            run("You are an AI greenhouse assistant. Live data: $snap. Question: ${q.ifBlank { "Give a one-sentence status summary." }}")
        }
        SecondaryButton("Diagnose greenhouse", !busy) {
            run(
                "You are a greenhouse technician. Live data: $snap. Active alerts: ${alerts.values.joinToString("; ").ifEmpty { "none" }}. " +
                    "Give a short diagnosis and up to 3 concrete actions."
            )
        }
        SecondaryButton("Predict harvest (tomatoes)", !busy) {
            scope.launch {
                val d = withContext(Dispatchers.IO) { History.since(System.currentTimeMillis() - 7 * 86_400_000L) }
                if (d.size < 10) {
                    toast(ctx, "Not enough history yet. Keep the app connected a little longer.")
                } else {
                    run(
                        "You are an expert agronomist. 7-day greenhouse averages: Temp=${d.map { it.t }.average().toFloat().f1()}C, " +
                            "Humidity=${d.map { it.h }.average().toFloat().f1()}%, Soil=${d.map { it.s.toFloat() }.average().toFloat().f1()}%, " +
                            "Soil temp=${d.map { it.st }.average().toFloat().f1()}C. Predict the harvest timing and yield for tomatoes, and what to improve."
                    )
                }
            }
        }
    }
}

/* =================== Config =================== */

@Composable
fun ConfigTab(r: Reading?) {
    val ctx = LocalContext.current
    var filled by rememberSaveable { mutableStateOf(false) }
    var tMax by rememberSaveable { mutableStateOf("") }
    var tMin by rememberSaveable { mutableStateOf("") }
    var hMax by rememberSaveable { mutableStateOf("") }
    var sMin by rememberSaveable { mutableStateOf("") }
    var wMin by rememberSaveable { mutableStateOf("") }
    var pMax by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(r?.limits) {
        val l = r?.limits
        if (l != null && !filled) {
            tMax = l.tempMax.f1(); tMin = l.tempMin.f1(); hMax = l.humMax.f1()
            sMin = "${l.soilMin}"; wMin = "${l.waterMin}"; pMax = "${l.pumpMax}"
            filled = true
        }
    }
    var bg by remember { mutableStateOf(Prefs.background) }
    var daily by remember { mutableStateOf(Prefs.dailyReport) }
    var every by remember { mutableIntStateOf(Prefs.logEverySec) }
    var aiUrl by rememberSaveable { mutableStateOf(Prefs.aiUrl) }
    var aiModel by rememberSaveable { mutableStateOf(Prefs.aiModel) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageTitle("Config")

        SectionCard("Greenhouse limits") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Max temp °C", tMax, Modifier.weight(1f)) { tMax = it }
                NumField("Min temp °C", tMin, Modifier.weight(1f)) { tMin = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Max humidity %", hMax, Modifier.weight(1f)) { hMax = it }
                NumField("Water soil below %", sMin, Modifier.weight(1f)) { sMin = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Tank empty at %", wMin, Modifier.weight(1f)) { wMin = it }
                NumField("Pump max run (s)", pMax, Modifier.weight(1f)) { pMax = it }
            }
            PrimaryButton("Send to greenhouse") {
                val l = Limits(
                    tMax.toFloatOrNull() ?: Float.NaN, tMin.toFloatOrNull() ?: Float.NaN, hMax.toFloatOrNull() ?: Float.NaN,
                    sMin.toIntOrNull() ?: -1, wMin.toIntOrNull() ?: -1, pMax.toIntOrNull() ?: -1
                )
                when {
                    l.tempMax.isNaN() || l.tempMin.isNaN() || l.humMax.isNaN() || l.soilMin < 0 || l.waterMin < 0 || l.pumpMax < 0 ->
                        toast(ctx, "Fill in every field with a number")
                    l.tempMin >= l.tempMax -> toast(ctx, "Min temp must be below max temp")
                    l.pumpMax !in 5..600 -> toast(ctx, "Pump max run must be 5 to 600 seconds")
                    GreenhouseClient.send(l.toJson()) -> toast(ctx, "Saved on the greenhouse")
                    else -> toast(ctx, "Offline. Not saved.")
                }
            }
        }

        SectionCard("Background and reports") {
            SwitchRow("Keep monitoring when the app is closed", bg) {
                bg = it; Prefs.background = it
                if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
            }
            Text("Keeps the connection open, logs readings and sends alert notifications with the screen off.", color = Muted, fontSize = 12.sp)
            SwitchRow("Save a PDF report every evening (20:00)", daily) {
                daily = it; Prefs.dailyReport = it
                if (it) ReportWorker.schedule(ctx) else ReportWorker.cancel(ctx)
            }
            Text("Saved to Downloads/HBRT (Android 10 and newer).", color = Muted, fontSize = 12.sp)
            Text("Save a reading every", color = TextC, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 30, 60).forEach { s ->
                    FilterChip(
                        selected = every == s, onClick = { every = s; Prefs.logEverySec = s }, label = { Text("$s s") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon, selectedLabelColor = Color.Black, labelColor = TextC)
                    )
                }
            }
        }

        SectionCard("Local AI (Ollama)") {
            OutlinedTextField(aiUrl, { aiUrl = it }, label = { Text("API endpoint URL") }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(aiModel, { aiModel = it }, label = { Text("Model") }, singleLine = true, colors = field(), modifier = Modifier.fillMaxWidth())
            PrimaryButton("Save AI settings") {
                Prefs.aiUrl = aiUrl.trim(); Prefs.aiModel = aiModel.trim(); toast(ctx, "AI settings saved")
            }
        }

        SectionCard("Connection") {
            Text("Linked to ${GreenhouseClient.url}", color = Muted, fontSize = 12.sp)
            SecondaryButton("Disconnect", color = Danger) {
                MonitorService.stop(ctx)
                GreenhouseClient.disconnect()
            }
        }

        Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Brand(22, "Agro")
            Text("Smart Greenhouse  ·  version 3.0", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Credits()
        }
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

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Neon)
        )
    }
}
