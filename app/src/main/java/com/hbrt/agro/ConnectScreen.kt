package com.hbrt.agro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* =================== Not connected: saved greenhouses, network scan, share links =================== */

@Composable
fun ConnectScreen(onLang: () -> Unit) {
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

    fun connect(a: String) { connectingTo = a; GreenhouseClient.connect(a) }
    fun scan() {
        if (scanning) return
        scanning = true; scanned = false; progress = 0f
        found.clear()
        scope.launch {
            Discovery.scan({ progress = it }) { f -> if (found.none { it.ip == f.ip }) found += f }
            scanning = false; scanned = true
        }
    }
    LaunchedEffect(Unit) { if (saved.isEmpty() && !busy) scan() }
    val newOnes = found.filter { f -> saved.none { it.addr == f.ip || (f.id.isNotEmpty() && it.id == f.id && !it.viewOnly) } }

    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                LeafMark(Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("HBRT Agro", color = TextC, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.w_title), color = Muted, fontSize = 13.sp)
                }
            }
        }
        if (err.isNotEmpty() && !busy) item {
            Text(err, Modifier.fillMaxWidth().card(16.dp, Danger.copy(alpha = .12f)).padding(14.dp), color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (saved.isNotEmpty()) item { H2(stringResource(R.string.gh_title)) }
        items(saved, key = { "s" + it.addr }) { g ->
            GreenhouseRow(
                name = g.name.ifBlank { "Greenhouse" },
                addr = when {
                    g.viewOnly -> stringResource(R.string.a_view_any)
                    g.cloudOnly -> stringResource(R.string.a_cloud_any)
                    g.hasCloud -> "${g.addr}  ·  ${stringResource(R.string.a_wifi_cloud)}"
                    else -> "${g.addr}  ·  ${stringResource(R.string.a_wifi_only)}"
                },
                cloud = g.hasCloud, connecting = busy && connectingTo == g.addr, enabled = !busy,
                onOpen = { connect(g.addr) }, onForget = { forget = g }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(scanning, label = "scan") { s ->
                    if (s) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Radar(Modifier.size(150.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.a_searching), color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.a_found_n, (progress * 100).toInt(), found.size), color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { progress }, color = Neon, trackColor = Track, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.a_search_title), color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.a_search_sub), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(stringResource(if (scanned) R.string.a_search_again else R.string.a_search), enabled = !busy) { scan() }
                    }
                }
            }
        }
        if (newOnes.isNotEmpty()) item { H2(stringResource(R.string.a_found_title)) }
        items(newOnes, key = { "f" + it.ip }) { f ->
            Box(Modifier.animateItem()) {
                GreenhouseRow(
                    name = f.name, addr = "${f.ip}  ·  ${stringResource(R.string.a_fw, f.fw)}", cloud = f.cloud.isNotEmpty(),
                    connecting = busy && connectingTo == f.ip, enabled = !busy, isNew = true,
                    onOpen = { Prefs.saveGreenhouse(SavedGh(f.name, f.ip, f.id, f.cloud)); saved = Prefs.greenhouses; connect(f.ip) }
                )
            }
        }
        if (scanned && found.isEmpty()) item { Text(stringResource(R.string.a_nothing_found), color = Muted, fontSize = 13.sp) }
        item {
            Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 6.dp)) {
                TextButton(onClick = { manual = !manual }) { Text(stringResource(if (manual) R.string.a_hide else R.string.a_add_link), color = Neon) }
                AnimatedVisibility(manual, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            addr, { addr = it }, singleLine = true, placeholder = { Text(stringResource(R.string.a_link_ph)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (addr.isNotBlank()) connect(addr.trim()) }),
                            colors = field(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                        )
                        PrimaryButton(stringResource(if (busy && connectingTo == addr.trim()) R.string.a_connecting else R.string.a_connect), enabled = !busy && addr.isNotBlank()) { connect(addr.trim()) }
                    }
                }
            }
        }
        if (busy) item {
            TextButton(onClick = { GreenhouseClient.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.a_cancel_connect), color = Muted) }
        }
        item { LookCard(onLang) }
        item { Spacer(Modifier.height(6.dp)); Credits() }
    }

    forget?.let { g ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(stringResource(R.string.a_remove_q, g.name.ifBlank { "Greenhouse" })) },
            text = { Text(stringResource(R.string.a_remove_body)) },
            confirmButton = { TextButton(onClick = { Prefs.removeGreenhouse(g.addr); saved = Prefs.greenhouses; forget = null }) { Text(stringResource(R.string.a_remove), color = Danger) } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = CardC
        )
    }
}

@Composable
fun GreenhouseRow(
    name: String, addr: String, connecting: Boolean, enabled: Boolean, isNew: Boolean = false, cloud: Boolean = false,
    onOpen: () -> Unit, onForget: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().card(20.dp, if (isNew) CardHi else null).bounceClick(enabled, onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Neon.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Neon, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cloud) { CloudIcon(Blue, Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
                Text(addr, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            connecting -> CircularProgressIndicator(Modifier.size(22.dp), color = Neon, strokeWidth = 2.dp)
            onForget != null -> TextButton(onClick = onForget, enabled = enabled) { Text(stringResource(R.string.a_remove), color = Muted, fontSize = 12.sp) }
            else -> Text(stringResource(if (isNew) R.string.add else R.string.a_open), color = Neon, fontWeight = FontWeight.Bold)
        }
    }
}

/** Theme and language, also reachable before connecting. */
@Composable
fun LookCard(onLang: () -> Unit) {
    SectionCard(stringResource(R.string.s_look)) {
        Text(stringResource(R.string.theme), color = Muted, fontSize = 13.sp)
        Chips {
            listOf("system" to R.string.th_system, "light" to R.string.th_light, "dark" to R.string.th_dark).forEach { (k, n) ->
                Chip(stringResource(n), UiState.theme == k) { Prefs.theme = k; UiState.theme = k }
            }
        }
        Text(stringResource(R.string.language), color = Muted, fontSize = 13.sp)
        Chips {
            listOf("system" to "Auto", "en" to "English", "fa" to "دری", "ps" to "پښتو").forEach { (k, n) ->
                Chip(n, Prefs.lang == k) { if (Prefs.lang != k) { Prefs.lang = k; onLang() } }
            }
        }
    }
}
