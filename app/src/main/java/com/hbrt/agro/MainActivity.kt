package com.hbrt.agro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon, onPrimary = Color.Black, secondary = Orange,
                    background = Bg, surface = CardC, onSurface = TextC, onBackground = TextC, error = Danger,
                    surfaceContainer = DarkC
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
        GreenhouseClient.kick()
    }
}

@Composable
fun HbrtApp() {
    val linked by GreenhouseClient.linked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(linked) {
        if (linked && Prefs.background) MonitorService.start(ctx)
    }
    AnimatedContent(
        targetState = linked,
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
        label = "root"
    ) { isLinked -> if (isLinked) MainScreen() else HomeScreen() }
}

fun leaveGreenhouse(ctx: android.content.Context) {
    MonitorService.stop(ctx)
    GreenhouseClient.disconnect()
}

/* =================== Home: saved greenhouses + network scan =================== */

@Composable
fun HomeScreen() {
    val scope = rememberCoroutineScope()
    val link by GreenhouseClient.link.collectAsStateWithLifecycle()
    val err by GreenhouseClient.error.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(Prefs.greenhouses) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val found = remember { mutableStateListOf<Found>() }
    var manual by rememberSaveable { mutableStateOf(false) }
    var addr by rememberSaveable { mutableStateOf("") }
    var connectingTo by remember { mutableStateOf(GreenhouseClient.address) }
    var forget by remember { mutableStateOf<SavedGh?>(null) }
    val busy = link == Link.CONNECTING || link == Link.RECONNECTING

    fun connect(a: String) {
        connectingTo = a
        GreenhouseClient.connect(a)
    }

    fun scan() {
        if (scanning) return
        scanning = true; scanned = false; progress = 0f
        found.clear()
        scope.launch {
            Discovery.scan({ progress = it }) { f -> if (found.none { it.ip == f.ip }) found += f }
            scanning = false
            scanned = true
        }
    }

    LaunchedEffect(Unit) { if (saved.isEmpty() && !busy) scan() } // first launch: start looking right away

    val newOnes = found.filter { f -> saved.none { it.addr == f.ip || (f.id.isNotEmpty() && it.id == f.id) } }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                LeafMark(Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Brand(30, "Agro")
                    Text("Smart greenhouse control", color = Muted, fontSize = 13.sp)
                }
            }
        }
        if (err.isNotEmpty() && !busy) item {
            Text(
                err, Modifier.fillMaxWidth().card(16.dp, Danger.copy(alpha = .1f)).padding(14.dp),
                color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        if (saved.isNotEmpty()) item { Text("Your greenhouses", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(saved, key = { "s" + it.addr }) { g ->
            GreenhouseRow(
                name = g.name.ifBlank { "Greenhouse" }, addr = g.addr,
                connecting = busy && connectingTo == g.addr, enabled = !busy,
                onOpen = { connect(g.addr) }, onForget = { forget = g }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(scanning, label = "scan") { s ->
                    if (s) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Radar(Modifier.size(150.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Searching your network…", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("${(progress * 100).toInt()}%  ·  ${found.size} found", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress }, color = Neon, trackColor = Border,
                                modifier = Modifier.fillMaxWidth().clip(CircleShape)
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Find greenhouses nearby", color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Searches the Wi-Fi you're on and your phone's hotspot.",
                                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton(if (scanned) "Search again" else "Search", enabled = !busy) { scan() }
                        }
                    }
                }
            }
        }
        if (newOnes.isNotEmpty()) item { Text("Found on your network", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(newOnes, key = { "f" + it.ip }) { f ->
            Box(Modifier.animateItem()) {
                GreenhouseRow(
                    name = f.name, addr = "${f.ip}  ·  firmware ${f.fw}", connecting = busy && connectingTo == f.ip,
                    enabled = !busy, isNew = true,
                    onOpen = {
                        Prefs.saveGreenhouse(SavedGh(f.name, f.ip, f.id))
                        saved = Prefs.greenhouses
                        connect(f.ip)
                    }
                )
            }
        }
        if (scanned && found.isEmpty()) item {
            Text(
                "Nothing found. Check that the ESP32 is powered, on the same Wi-Fi as this phone (or on its hotspot), " +
                    "and running firmware 4.0. You can also add it by IP address below.",
                color = Muted, fontSize = 13.sp
            )
        }
        item {
            Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 6.dp)) {
                TextButton(onClick = { manual = !manual }) {
                    Text(if (manual) "Hide" else "Add by IP address or tunnel URL", color = Neon)
                }
                AnimatedVisibility(manual, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            addr, { addr = it }, singleLine = true,
                            placeholder = { Text("192.168.1.50 or https://…") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (addr.isNotBlank()) connect(addr.trim()) }),
                            colors = field(), modifier = Modifier.fillMaxWidth()
                        )
                        PrimaryButton(if (busy && connectingTo == addr.trim()) "Connecting…" else "Connect", enabled = !busy && addr.isNotBlank()) {
                            connect(addr.trim())
                        }
                    }
                }
            }
        }
        if (busy) item {
            TextButton(onClick = { GreenhouseClient.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel connecting", color = Muted)
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Credits()
        }
    }

    forget?.let { g ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Remove ${g.name.ifBlank { "this greenhouse" }}?") },
            text = { Text("It disappears from this list. History already saved on the phone is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.removeGreenhouse(g.addr); saved = Prefs.greenhouses; forget = null
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun GreenhouseRow(
    name: String, addr: String, connecting: Boolean, enabled: Boolean, isNew: Boolean = false,
    onOpen: () -> Unit, onForget: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().card(20.dp, if (isNew) CardHi else CardC).bounceClick(enabled, onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Leaf.copy(alpha = .25f)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Neon, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(addr, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            connecting -> CircularProgressIndicator(Modifier.size(22.dp), color = Neon, strokeWidth = 2.dp)
            onForget != null -> TextButton(onClick = onForget, enabled = enabled) { Text("Remove", color = Muted, fontSize = 12.sp) }
            else -> Text(if (isNew) "Add" else "Open", color = Neon, fontWeight = FontWeight.Bold)
        }
    }
}

/* =================== Main screen =================== */

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Controls("Control", Icons.Filled.Build),
    Data("History", Icons.AutoMirrored.Filled.List),
    Ai("AI", Icons.Filled.Star),
    Config("Settings", Icons.Filled.Settings),
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val r by GreenhouseClient.latest.collectAsStateWithLifecycle()
    val link by GreenhouseClient.link.collectAsStateWithLifecycle()
    val attempt by GreenhouseClient.attempt.collectAsStateWithLifecycle()
    var askedName by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(DarkC).statusBarsPadding().padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { leaveGreenhouse(ctx) }) { Text("‹", color = TextC, fontSize = 28.sp) }
                Column(Modifier.weight(1f)) {
                    Text(
                        r?.name?.ifBlank { null } ?: Prefs.greenhouses.firstOrNull { it.addr == GreenhouseClient.address }?.name?.ifBlank { null } ?: "Greenhouse",
                        color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            link != Link.ONLINE -> "Reconnecting…"
                            r?.auto != false -> "Online  ·  automatic"
                            else -> "Online  ·  manual"
                        },
                        color = if (link == Link.ONLINE) Muted else Orange, fontSize = 12.sp
                    )
                }
                PulseDot(if (link == Link.ONLINE) Neon else Orange, pulsing = link == Link.ONLINE)
            }
        },
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
            AnimatedVisibility(link != Link.ONLINE, enter = expandVertically(), exit = shrinkVertically()) {
                Text(
                    if (attempt > 0) "Connection lost. Retrying (attempt $attempt)…" else "Connecting to the greenhouse…",
                    Modifier.fillMaxWidth().background(Orange).padding(7.dp),
                    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 12 * dir }) togetherWith fadeOut(tween(150))
                },
                label = "tabs"
            ) { t ->
                when (t) {
                    Tab.Home -> DashboardTab(r)
                    Tab.Controls -> ControlsTab(r, link == Link.ONLINE)
                    Tab.Data -> DataTab()
                    Tab.Ai -> AiTab(r)
                    Tab.Config -> ConfigTab(r)
                }
            }
        }
    }

    // A brand-new board is still called "My Greenhouse": offer to name it once.
    val cur = r
    if (cur != null && cur.modern && cur.name == "My Greenhouse" && !askedName) {
        AlertDialog(
            onDismissRequest = { askedName = true },
            title = { Text("Name this greenhouse") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The name is saved on the ESP32, so every phone sees it.", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(
                        newName, { if (it.length <= 32) newName = it }, singleLine = true,
                        placeholder = { Text("e.g. Tomato house") }, colors = field()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) GreenhouseClient.rename(newName.trim())
                    askedName = true
                }) { Text("Save", color = Neon) }
            },
            dismissButton = { TextButton(onClick = { askedName = true }) { Text("Later") } }
        )
    }
}
