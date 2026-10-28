package com.hbrt.agro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        Prefs.init(newBase)
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UiState.theme = Prefs.theme
        setContent { Root(onLang = { recreate() }) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null && Prefs.autoConnect && Prefs.lastAddress.isNotBlank() && GreenhouseClient.link.value == Link.IDLE) {
            GreenhouseClient.connect(Prefs.lastAddress)
        }
    }

    override fun onResume() {
        super.onResume()
        GreenhouseClient.kick()
    }
}

/** Language changes recreate the activity; the theme changes live. */
object UiState {
    var theme by mutableStateOf("system")
}

@Composable
fun Root(onLang: () -> Unit) {
    val sysDark = isSystemInDarkTheme()
    Pal.dark = when (UiState.theme) { "light" -> false; "dark" -> true; else -> sysDark }
    val font = BodyFont
    val t = Typography()
    val typo = Typography(
        bodyLarge = t.bodyLarge.copy(fontFamily = font), bodyMedium = t.bodyMedium.copy(fontFamily = font), bodySmall = t.bodySmall.copy(fontFamily = font),
        titleLarge = t.titleLarge.copy(fontFamily = font), titleMedium = t.titleMedium.copy(fontFamily = font), labelLarge = t.labelLarge.copy(fontFamily = font),
        labelMedium = t.labelMedium.copy(fontFamily = font), labelSmall = t.labelSmall.copy(fontFamily = font), headlineSmall = t.headlineSmall.copy(fontFamily = font),
    )
    val scheme = if (Pal.dark) darkColorScheme(primary = Neon, onPrimary = OnNeon, background = Bg, surface = CardC, onSurface = TextC, onBackground = TextC, error = Danger, surfaceContainer = CardC, surfaceContainerHigh = CardC)
    else lightColorScheme(primary = Neon, onPrimary = OnNeon, background = Bg, surface = CardC, onSurface = TextC, onBackground = TextC, error = Danger, surfaceContainer = CardC, surfaceContainerHigh = CardC)
    MaterialTheme(colorScheme = scheme, typography = typo) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = font)) {
            Surface(Modifier.fillMaxSize(), color = Bg) { HbrtApp(onLang) }
        }
    }
}

@Composable
fun HbrtApp(onLang: () -> Unit) {
    val linked by GreenhouseClient.linked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(linked) { if (linked && Prefs.background) MonitorService.start(ctx) }
    AnimatedContent(targetState = linked, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "root") { isLinked ->
        if (isLinked) MainScreen(onLang) else ConnectScreen(onLang)
    }
}

fun leaveGreenhouse(ctx: Context) {
    MonitorService.stop(ctx)
    GreenhouseClient.disconnect()
}

enum class Tab(val label: Int, val icon: ImageVector) {
    Home(R.string.nav_home, Icons.Filled.Home),
    Control(R.string.nav_control, Icons.Filled.Build),
    Data(R.string.nav_history, Icons.AutoMirrored.Filled.List),
    Journal(R.string.nav_journal, Icons.Filled.Edit),
    Config(R.string.nav_settings, Icons.Filled.Settings),
}

/** Screens that open over the tabs. */
enum class Page { None, Timeline, Ai }

@Composable
fun MainScreen(onLang: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var page by rememberSaveable { mutableStateOf(Page.None) }
    val r by GreenhouseClient.latest.collectAsStateWithLifecycle()
    val link by GreenhouseClient.link.collectAsStateWithLifecycle()
    val attempt by GreenhouseClient.attempt.collectAsStateWithLifecycle()
    val via by GreenhouseClient.via.collectAsStateWithLifecycle()
    val ro by GreenhouseClient.readOnly.collectAsStateWithLifecycle()
    val boardOnline by GreenhouseClient.boardOnline.collectAsStateWithLifecycle()
    val boardDown = link == Link.ONLINE && via == Via.CLOUD && !boardOnline
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = System.currentTimeMillis() } }
    var askedName by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = page != Page.None || tab != Tab.Home) { if (page != Page.None) page = Page.None else tab = Tab.Home }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // top bar
        Row(
            Modifier.fillMaxWidth().background(Bg).statusBarsPadding().padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (page != Page.None) page = Page.None else leaveGreenhouse(ctx) }) {
                Text(if (Lang.rtl) "›" else "‹", color = TextC, fontSize = 28.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    r?.name?.ifBlank { null } ?: Prefs.greenhouses.firstOrNull { it.addr == GreenhouseClient.address }?.name?.ifBlank { null } ?: "Greenhouse",
                    color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        link != Link.ONLINE -> stringResource(R.string.connecting)
                        boardDown -> stringResource(R.string.offline) + " · " + ago(ctx, now - GreenhouseClient.lastMsgAt)
                        else -> stringResource(R.string.live) + " · " + stringResource(if (via == Via.CLOUD) R.string.via_cloud else R.string.via_wifi) +
                            (if (ro) " · " + stringResource(R.string.view_only) else "")
                    },
                    color = if (link == Link.ONLINE && !boardDown) Muted else Warn, fontSize = 12.sp
                )
            }
            if (!ro) Box(Modifier.size(40.dp).clip(CircleShape).background(CardHi).bounceClick { page = Page.Ai }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Star, stringResource(R.string.a_ai), tint = Violet, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            if (via == Via.CLOUD && link == Link.ONLINE) { CloudIcon(if (boardDown) Warn else Blue, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            PulseDot(when { link != Link.ONLINE || boardDown -> Warn; else -> Neon }, pulsing = link == Link.ONLINE && !boardDown)
        }
        AnimatedVisibility(link != Link.ONLINE || boardDown, enter = expandVertically(), exit = shrinkVertically()) {
            Text(
                when {
                    boardDown -> stringResource(R.string.banner_board_down, ago(ctx, now - GreenhouseClient.lastMsgAt))
                    attempt > 0 -> stringResource(R.string.banner_reconnect_n, attempt)
                    else -> stringResource(R.string.connecting) + "…"
                },
                Modifier.fillMaxWidth().background(Warn).padding(8.dp), color = Color(0xFF1A0D00), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
        }
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = if (page != Page.None) page.name else tab.name,
                transitionSpec = { (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 14 }) togetherWith fadeOut(tween(140)) },
                label = "tabs"
            ) { key ->
                when (key) {
                    Page.Timeline.name -> TimelineScreen()
                    Page.Ai.name -> AiScreen(r)
                    Tab.Control.name -> ControlScreen(r, link == Link.ONLINE && !boardDown && !ro)
                    Tab.Data.name -> HistoryScreen()
                    Tab.Journal.name -> JournalScreen()
                    Tab.Config.name -> SettingsScreen(r, onLang)
                    else -> DashScreen(r, onTimeline = { page = Page.Timeline }, onControl = { tab = Tab.Control })
                }
            }
        }
        // floating bottom navigation
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp)).background(CardC).border(1.dp, Border, RoundedCornerShape(26.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.entries.forEach { t ->
                val sel = tab == t && page == Page.None
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(19.dp)).background(if (sel) Neon else Color.Transparent)
                        .bounceClick { tab = t; page = Page.None }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, null, tint = if (sel) OnNeon else Muted, modifier = Modifier.size(22.dp))
                    Text(stringResource(t.label), color = if (sel) OnNeon else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }

    // A brand-new board is still called "My Greenhouse": offer to name it once.
    val cur = r
    if (cur != null && cur.modern && !ro && cur.name == "My Greenhouse" && !askedName) {
        AlertDialog(
            onDismissRequest = { askedName = true },
            title = { Text(stringResource(R.string.a_name_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.a_name_body), color = Muted, fontSize = 13.sp)
                    OutlinedTextField(newName, { if (it.length <= 32) newName = it }, singleLine = true, placeholder = { Text(stringResource(R.string.name_ph)) }, colors = field())
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) GreenhouseClient.rename(newName.trim()); askedName = true }) { Text(stringResource(R.string.save), color = Neon) } },
            dismissButton = { TextButton(onClick = { askedName = true }) { Text(stringResource(R.string.a_later)) } },
            containerColor = CardC
        )
    }
}
