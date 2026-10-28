package com.hbrt.agro

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Calendar

data class EvInfo(val title: String, val sub: String, val color: Color, val kind: String)

@Composable
fun evInfo(e: Ev): EvInfo {
    val by = stringResource(
        when (e.by) {
            "auto" -> R.string.by_auto; "you" -> R.string.by_you; "schedule" -> R.string.by_schedule; "safety" -> R.string.by_safety
            "rule" -> R.string.by_rule; "timer" -> R.string.by_timer; "away" -> R.string.by_away; "telegram" -> R.string.by_telegram
            else -> R.string.by_system
        }
    )
    val why = when (e.w) {
        "hot" -> stringResource(R.string.why_hot, e.x.f1())
        "cold" -> stringResource(R.string.why_cold, e.x.f1())
        "humid" -> stringResource(R.string.why_humid, e.x.f0())
        "dry" -> stringResource(R.string.why_dry, e.x.f0())
        "moist" -> stringResource(R.string.why_moist, e.x.f0())
        "maxrun" -> stringResource(R.string.why_maxrun, e.x.f0())
        "rule" -> stringResource(R.string.why_rule, (e.x.toInt() + 1))
        "tank" -> stringResource(R.string.why_tank)
        "lightson", "lightsoff" -> stringResource(R.string.why_lightson)
        "ok" -> stringResource(R.string.why_ok)
        "sensor" -> stringResource(R.string.why_sensor)
        "done" -> stringResource(R.string.why_done)
        "rain" -> stringResource(R.string.why_rain)
        "lock" -> stringResource(R.string.why_lock)
        "away" -> stringResource(R.string.why_away)
        "manual" -> stringResource(R.string.why_manual)
        "frost" -> stringResource(R.string.why_frost)
        "stage" -> stringResource(R.string.why_stage)
        else -> ""
    }
    val sub = if (why.isNotEmpty()) "$by: $why" else by
    val dc = if (e.d.isNotEmpty()) devColor(e.d) else Muted
    return when (e.c) {
        "dev" -> EvInfo(
            if (e.d == "servo") stringResource(if (e.v == 1) R.string.ev_vent_open else R.string.ev_vent_close)
            else stringResource(if (e.v == 1) R.string.ev_on else R.string.ev_off, devName(e.d)), sub, dc, "devices"
        )
        "alert" -> EvInfo(
            when (e.a) {
                "hot" -> stringResource(R.string.al_hot, e.x.f1()); "cold" -> stringResource(R.string.al_cold, e.x.f1())
                "humid" -> stringResource(R.string.al_humid, e.x.f0()); "dry" -> stringResource(R.string.al_dry, e.x.f0())
                "tank" -> stringResource(R.string.al_tank); "lock" -> stringResource(R.string.al_lock); "dht" -> stringResource(R.string.al_dht)
                "st" -> stringResource(R.string.al_st); "frost" -> stringResource(R.string.al_frost, e.x.f0()); else -> e.a
            }, "", Danger, "alerts"
        )
        "mode" -> EvInfo(stringResource(R.string.ev_mode, modeName(e.m)), by, Neon, "settings")
        "skip" -> EvInfo(stringResource(R.string.ev_skip), sub, Blue, "devices")
        "water" -> EvInfo(stringResource(R.string.ev_water, e.x.f0()), by, Blue, "devices")
        "boot" -> EvInfo(stringResource(R.string.ev_boot), "", Muted, "settings")
        "cfg" -> EvInfo(stringResource(R.string.ev_cfg), sub, Muted, "settings")
        "cal" -> EvInfo(stringResource(R.string.ev_cal), by, Muted, "settings")
        "ota" -> EvInfo(stringResource(if (e.v == 1) R.string.ev_ota_ok else R.string.ev_ota_fail), "", if (e.v == 1) Neon else Danger, "settings")
        "wifi" -> EvInfo(stringResource(if (e.v == 1) R.string.ev_wifi_new else R.string.ev_wifi_setup), "", Muted, "settings")
        "ovrend" -> EvInfo(stringResource(R.string.ev_ovrend, devName(e.d)), by, dc, "devices")
        "stage" -> EvInfo(stringResource(R.string.ev_stage, stageName(e.st)), "", Lime, "settings")
        else -> EvInfo(e.c, sub, dc, "settings")
    }
}

@Composable
fun EvRow(e: Ev, last: Boolean) {
    val i = evInfo(e)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(16.dp).fillMaxHeight(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 5.dp).size(12.dp).shadow(6.dp, CircleShape, ambientColor = i.color, spotColor = i.color).clip(CircleShape).background(i.color))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Border))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            Text(i.title, color = TextC, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (i.sub.isNotEmpty()) Text(i.sub, color = Muted, fontSize = 13.sp)
        }
        if (e.t > 0) Text(timeStr(e.t), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun TimelineScreen() {
    val live by GreenhouseClient.events.collectAsStateWithLifecycle()
    val r by GreenhouseClient.latest.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    val stored = remember(live.size, r?.id) { History.events(r?.id ?: "") }
    // board's events first (newest), then older ones kept on the phone
    val all = (live + stored).distinctBy { "${it.t}:${it.c}:${it.d}:${it.v}" }.sortedByDescending { it.t }
    val withInfo = all.map { it to evInfo(it) }.filter { filter == "all" || it.second.kind == filter }
    val groups = withInfo.groupBy { (e, _) -> if (e.t == 0L) 0L else Calendar.getInstance().apply { timeInMillis = e.t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageTitle(stringResource(R.string.tl_title)) }
        item {
            Chips {
                listOf("all" to R.string.filter_all, "devices" to R.string.filter_devices, "alerts" to R.string.filter_alerts, "settings" to R.string.filter_settings).forEach { (k, n) ->
                    Chip(stringResource(n), filter == k) { filter = k }
                }
            }
        }
        if (withInfo.isEmpty()) item { Text(stringResource(R.string.tl_empty), color = Muted) }
        groups.forEach { (day, list) ->
            item(key = "h$day") {
                Text(
                    when { day == 0L -> stringResource(R.string.tl_earlier); day == today -> stringResource(R.string.today); day == today - 86_400_000L -> stringResource(R.string.yesterday); else -> dateStr(day) },
                    color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)
                )
            }
            item(key = "g$day") {
                Column(Modifier.fillMaxWidth().card().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    list.forEachIndexed { i, (e, _) -> EvRow(e, i == list.size - 1) }
                }
            }
        }
    }
}
