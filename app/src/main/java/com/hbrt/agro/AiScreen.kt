package com.hbrt.agro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class Msg(val me: Boolean, val text: String, val err: Boolean = false)

/** Chat kept while the app runs, so leaving the page doesn't lose it. */
private val chat = mutableStateListOf<Msg>()

@Composable
fun AiScreen(r: Reading?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by GreenhouseClient.cfg.collectAsStateWithLifecycle()
    val alerts = r?.alerts(cfg) ?: emptyList()
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val lang = when (Lang.code()) { "fa" -> "Dari (Afghan Persian)"; "ps" -> "Pashto"; else -> "English" }
    val crop = cfg?.crop?.ifBlank { null } ?: "vegetables"
    val snap = r?.let {
        String.format(Locale.US, "Temp=%.1fC, Humidity=%.0f%%, Soil moisture=%d%%, Soil temp=%.1fC, Water tank=%d%%, crop=%s, stage=%s",
            it.temperature, it.humidity, it.soil, it.soilTemp, it.water, crop, it.stage.ifBlank { "unknown" })
    } ?: "no live data"

    fun run(shown: String, prompt: String) {
        if (busy) return
        chat += Msg(true, shown)
        busy = true
        scope.launch {
            val reply = try { Ai.ask("$prompt Answer in $lang, short and practical.") to false }
            catch (e: Exception) { ctx.getString(R.string.a_ai_err, e.message ?: "?") to true }
            chat += Msg(false, reply.first.trim(), reply.second)
            busy = false
        }
    }
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size + 1) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PageTitle(stringResource(R.string.a_ai_title), stringResource(R.string.a_ai_sub)) }
            item { Bubble(Msg(false, stringResource(R.string.a_ai_hello))) }
            itemsIndexed(chat) { _, m -> Bubble(m) }
            if (busy) item { Typing() }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chips {
                val diag = stringResource(R.string.a_ai_diagnose); val harv = stringResource(R.string.a_ai_harvest)
                val pest = stringResource(R.string.a_ai_pests); val water = stringResource(R.string.a_ai_water)
                val short = stringResource(R.string.a_ai_short)
                Chip(diag, false, !busy) {
                    run(diag, "You are a greenhouse technician. Live data: $snap. Active problems: ${alerts.joinToString(", ").ifEmpty { "none" }}. Give a short diagnosis and up to 3 concrete actions.")
                }
                Chip(harv, false, !busy) {
                    scope.launch {
                        val d = withContext(Dispatchers.IO) { History.since(System.currentTimeMillis() - 7 * 86_400_000L) }
                        if (d.size < 10) toast(ctx, short)
                        else run(harv, "You are an expert agronomist. 7-day greenhouse averages: Temp=${d.map { it.t }.average().toFloat().f1()}C, " +
                            "Humidity=${d.map { it.h }.average().toFloat().f1()}%, Soil=${d.map { it.s.toFloat() }.average().toFloat().f1()}%. Crop: $crop, " +
                            "day ${r?.day ?: -1} since planting. Predict harvest timing and yield, and what to improve.")
                    }
                }
                Chip(pest, false, !busy) { run(pest, "You are a plant-protection expert. Greenhouse data: $snap. Which pests or diseases are likely under these conditions, and how do I prevent them?") }
                Chip(water, false, !busy) { run(water, "Greenhouse data: $snap. Give 3 practical tips to use less irrigation water without stressing the plants.") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.a_ai_q)) }, colors = field(), modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(22.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(50.dp).clip(CircleShape).background(if (q.isNotBlank() && !busy) Neon else CardHi)
                    .bounceClick(q.isNotBlank() && !busy) { val t = q.trim(); q = ""; run(t, "You are an AI greenhouse assistant. Live data: $snap. Question: $t") },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.a_ai_ask), tint = if (q.isNotBlank() && !busy) OnNeon else Muted)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    var shown by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 3 }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.me) Arrangement.End else Arrangement.Start) {
            if (!m.me) {
                Box(Modifier.padding(end = 8.dp, top = 2.dp).size(30.dp).clip(CircleShape).background(Violet.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    LeafMark(Modifier.size(18.dp))
                }
            }
            SelectionContainer {
                Text(m.text,
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (m.me) 20.dp else 6.dp, bottomEnd = if (m.me) 6.dp else 20.dp))
                        .background(when { m.me -> Neon; m.err -> Danger.copy(alpha = .14f); else -> CardC }).padding(horizontal = 14.dp, vertical = 10.dp),
                    color = when { m.me -> OnNeon; m.err -> Danger; else -> TextC }, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun Typing() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(start = 38.dp).clip(RoundedCornerShape(18.dp)).background(CardC).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..2) {
            val a by t.animateFloat(.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), androidx.compose.animation.core.RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).graphicsLayer { alpha = a; translationY = (1 - a) * 6f }.clip(CircleShape).background(Violet))
        }
    }
}
