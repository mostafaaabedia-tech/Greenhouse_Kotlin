package com.hbrt.agro

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.max

fun summaryText(c: Context, r: Reading, cfg: Cfg?, wx: Wx?): String {
    val al = r.alerts(cfg)
    return when {
        r.watering > 0 -> c.getString(R.string.sum_watering, r.watering)
        al.isNotEmpty() -> Notifier.alertText(c, al.first(), r, wx)
        r.pump -> c.getString(R.string.sum_pump)
        r.fan && r.servo -> c.getString(R.string.sum_cool)
        r.heater -> c.getString(R.string.sum_heat)
        else -> c.getString(R.string.sum_good)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashScreen(r: Reading?, onTimeline: () -> Unit, onControl: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by GreenhouseClient.cfg.collectAsStateWithLifecycle()
    val wx by GreenhouseClient.wx.collectAsStateWithLifecycle()
    val use by GreenhouseClient.use.collectAsStateWithLifecycle()
    val events by GreenhouseClient.events.collectAsStateWithLifecycle()
    val via by GreenhouseClient.via.collectAsStateWithLifecycle()
    val ro by GreenhouseClient.readOnly.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val l = cfg ?: Cfg()
    val tod = remember(now / 600_000) { timeOfDay() }
    val lightSky = skyIsLight(tod)
    val onSky = if (lightSky) Color(0xFF0D2236) else Color.White
    val glass = if (lightSky) Color(0x99FFFFFF) else Color(0x47000000)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Enter(0) {
            Sky(tod, wxKind(wx?.code ?: 0), RoundedCornerShape(30.dp), Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(30.dp))) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(270.dp)) {
                        PlantDial(r, cfg, Modifier.fillMaxSize())
                        Column(
                            Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft).shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                                .background(CardC.copy(alpha = .94f)).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(r?.health(cfg)?.toString() ?: "--", color = TextC, fontSize = 28.sp, style = Tnum)
                            Text(stringResource(R.string.health), color = Muted, fontSize = 11.sp)
                        }
                    }
                    Text(
                        r?.let { summaryText(ctx, it, cfg, wx) } ?: stringResource(R.string.sum_wait),
                        color = onSky, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 29.sp
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            (r?.temperature?.f1()?.plus("°") ?: "--") to R.string.m_air,
                            (r?.humidity?.f0()?.plus("%") ?: "--") to R.string.m_hum,
                            (r?.soil?.let { "$it%" } ?: "--") to R.string.m_soil,
                            (r?.water?.let { "$it%" } ?: "--") to R.string.m_tank,
                        ).forEach { (v, k) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(glass).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(v, color = onSky, fontSize = 21.sp, style = Tnum)
                                Text(stringResource(k), color = onSky.copy(alpha = .8f), fontSize = 11.sp)
                            }
                        }
                    }
                    if (r != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Pill(modeName(r.mode), if (r.mode == "manual") Color(0xFFB35A00) else Neon, if (r.mode == "manual") Color.White else OnNeon)
                        Pill(stringResource(if (ro) R.string.view_only else if (via == Via.CLOUD) R.string.via_cloud else R.string.via_wifi), glass, onSky)
                        growth(r, cfg)?.let { g -> Pill("${cropName(g.crop)} · ${stageName(g.stage)} · ${stringResource(R.string.g_day, g.day)}", glass, onSky) }
                        if (l.lightSched) Pill("${devName("lights")} ${fmtMinutes(l.lightOn)}–${fmtMinutes(l.lightOff)}", glass, onSky)
                        val n = r.alerts(cfg).size
                        if (n > 1) Pill(stringResource(R.string.al_n, n), Color(0xFFB02A2A), Color.White)
                    }
                    if (r != null && GreenhouseClient.lastMsgAt > 0) Text(
                        stringResource(R.string.updated_ago, ago(ctx, now - GreenhouseClient.lastMsgAt)) + " · " + stringResource(R.string.running_for, dur(ctx, r.uptime)),
                        color = onSky.copy(alpha = .85f), fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (r != null) Enter(1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val onNow = DEVICES.filter { r.on(it) }
                if (onNow.isEmpty()) Text(stringResource(R.string.nothing_on), Modifier.card(16.dp).padding(horizontal = 14.dp, vertical = 10.dp), color = Muted, fontSize = 13.sp)
                onNow.forEach { d -> DevChip(d, r, onControl) }
            }
        }

        if (r != null) Enter(2) { BarsCard(r, l) }
        if (r != null) Enter(3) { GrowthCard(r, cfg) }
        Enter(4) { WeatherCard(wx, l) }
        if (r != null) Enter(5) { UsageCard(r, l, use) }
        Enter(6) {
            Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    H2(stringResource(R.string.tl_today), Modifier.weight(1f))
                    TextButton(onClick = onTimeline) { Text(stringResource(R.string.tl_see_all), color = Neon, fontWeight = FontWeight.Bold) }
                }
                if (events.isEmpty()) Text(stringResource(R.string.tl_empty), color = Muted, fontSize = 13.sp)
                events.take(4).forEachIndexed { i, e -> EvRow(e, last = i == minOf(3, events.size - 1)) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DevChip(d: String, r: Reading, onClick: () -> Unit) {
    val c = devColor(d)
    val t = rememberInfiniteTransition(label = "glow")
    val g by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "g")
    val ov = r.ovr[d]
    Row(
        Modifier.shadow((8 * g).dp, RoundedCornerShape(16.dp), ambientColor = c, spotColor = c).clip(RoundedCornerShape(16.dp))
            .background(c.copy(alpha = .14f).compositeOver(CardC)).border(1.dp, c.copy(alpha = .55f), RoundedCornerShape(16.dp))
            .bounceClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DevIcon(d, true, c, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(devName(d) + (if (d == "servo") " · " + stringResource(R.string.open) else "") +
            (ov?.let { " · " + if (it < 0) "∞" else stringResource(R.string.min_short, max(1, (it + 59) / 60)) } ?: ""),
            color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
private fun BarsCard(r: Reading, l: Cfg) {
    val v = vpd(r.temperature, r.humidity)
    val dp = dewPoint(r.temperature, r.humidity)
    val band = vpdBand(r.stage)
    val gap = r.temperature - dp
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 18.dp, vertical = 8.dp)) {
        RangeBar(stringResource(R.string.m_air), r.temperature.f1() + "°", r.temperature, l.tempMin, l.tempMax, 0f, 45f, Orange, "${l.tempMin.f0()}–${l.tempMax.f0()}°")
        Divider()
        RangeBar(stringResource(R.string.m_hum), r.humidity.f0() + "%", r.humidity, max(0f, l.humMax - 35), l.humMax, 0f, 100f, Cyan, "≤ ${l.humMax.f0()}%")
        Divider()
        RangeBar(stringResource(R.string.m_soil), "${r.soil}%", r.soil.toFloat(), l.soilMin.toFloat(), 100f, 0f, 100f, Lime, "≥ ${l.soilMin}%")
        Divider()
        RangeBar(stringResource(R.string.m_soil_temp), r.soilTemp.f1() + "°", r.soilTemp, 15f, 28f, 0f, 40f, Gold, "15–28°")
        Divider()
        RangeBar(stringResource(R.string.m_vpd), v.fx(2), v, band.first, band.second, 0f, 2.5f, Neon, "${band.first}–${band.second}")
        Text(
            stringResource(if (v < band.first) R.string.vpd_low else if (v > band.second) R.string.vpd_high else R.string.vpd_good),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)
        )
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(104.dp)) {
                Text(stringResource(R.string.m_dew), color = Muted, fontSize = 13.sp)
                Text(dp.f1() + "°", color = TextC, fontSize = 23.sp, style = Tnum)
            }
            Text(
                stringResource(if (gap < 3) R.string.dew_risk else R.string.dew_ok, gap.f1()),
                color = if (gap < 3) Danger else Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GrowthCard(r: Reading, cfg: Cfg?) {
    val g = growth(r, cfg)
    Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
        if (g == null) {
            H2(stringResource(R.string.g_title))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.g_setup), color = Muted, fontSize = 13.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            H2(cropName(g.crop), Modifier.weight(1f))
            Pill(stageName(g.stage), Neon, OnNeon)
        }
        Text(
            if (g.frac >= 1f) stringResource(R.string.g_ready)
            else stringResource(R.string.g_of, g.day, g.totalDays) + " · " + stringResource(R.string.g_harvest, dateStr(g.harvestAt)),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
        )
        val still = androidx.compose.ui.platform.LocalInspectionMode.current
        val p = remember { androidx.compose.animation.core.Animatable(if (still) g.frac else 0f) }
        LaunchedEffect(g.frac) { p.animateTo(g.frac, tween(1400)) }
        val track = Track
        Canvas(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(12.dp)) {
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val w = size.width * max(.03f, p.value)
            drawRoundRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF5BD27A), Color(0xFFFFC93C), Color(0xFFFF7A59)), if (rtl) size.width else 0f, if (rtl) 0f else size.width),
                Offset(if (rtl) size.width - w else 0f, 0f), androidx.compose.ui.geometry.Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            STAGES.forEach { s -> Text(stageName(s), color = if (s == g.stage) Neon else Muted, fontSize = 11.sp, fontWeight = if (s == g.stage) FontWeight.Bold else FontWeight.Normal) }
        }
        Text(stringResource(R.string.gdd_note, g.gdd.f0(), g.gddH.f0()), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun WeatherCard(wx: Wx?, l: Cfg) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H2(stringResource(R.string.wx_title))
        if (wx == null || wx.days.isEmpty()) { Text(stringResource(R.string.wx_none), color = Muted, fontSize = 13.sp); return@Column }
        val k = wxKind(wx.code)
        val names = listOf(R.string.wx_0, R.string.wx_1, R.string.wx_2, R.string.wx_3, R.string.wx_4, R.string.wx_5, R.string.wx_6, R.string.wx_7, R.string.wx_8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1.3f)) {
                Text(stringResource(names[k]), color = Muted, fontSize = 13.sp)
                Text(wx.now.f0() + "°", color = TextC, fontSize = 32.sp, style = Tnum)
                Text("${wx.days[0].max.f0()}° / ${wx.days[0].min.f0()}°", color = Muted, fontSize = 12.sp)
            }
            wx.days.drop(1).take(3).forEachIndexed { i, d ->
                val c = if (d.rain) Blue else Gold
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (d.rain) Blue.copy(alpha = .14f) else CardHi)
                        .then(if (d.rain) Modifier.border(1.dp, Blue.copy(alpha = .5f), RoundedCornerShape(14.dp)) else Modifier).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(weekdayShort(System.currentTimeMillis() + (i + 1) * 86_400_000L), color = if (d.rain) Blue else Muted, fontSize = 11.sp)
                    WxIcon(wxKind(d.code), if (d.rain) Blue else Muted, Modifier.size(20.dp))
                    Text(d.max.f0() + "°", color = c, fontSize = 17.sp, style = Tnum)
                    Text(d.min.f0() + "°", color = Muted, fontSize = 11.sp)
                }
            }
        }
        if (wx.frost) Notice(stringResource(R.string.wx_frost, wx.days.getOrNull(1)?.min?.f0() ?: "--"), Warn)
        Notice(stringResource(if (wx.rain && l.rainSkip) R.string.wx_rain_skip else R.string.wx_no_rain), if (wx.rain) Blue else Muted)
    }
}

@Composable
fun Notice(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .12f)).padding(12.dp), color = TextC, fontSize = 13.sp)
}

@Composable
private fun UsageCard(r: Reading, l: Cfg, u: Use?) {
    val days = u?.days?.take(6)?.reversed() ?: emptyList()
    val avg = if (days.isNotEmpty()) days.map { it.l }.average().toFloat() else u?.todayL ?: 0f
    val leftL = l.tankL * r.water / 100f
    val daysLeft = if (avg > 0.05f) leftL / avg else null
    Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TankGlass(r.water)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.m_water_tank), color = Muted, fontSize = 13.sp)
                Text("${r.water}%", color = TextC, fontSize = 36.sp, style = Tnum)
                Text(
                    when {
                        r.water <= l.waterMin -> stringResource(R.string.tank_empty)
                        daysLeft != null -> stringResource(R.string.tank_days, daysLeft.fx(if (daysLeft < 10) 1 else 0))
                        r.water < l.waterMin + 15 -> stringResource(R.string.tank_low)
                        else -> stringResource(R.string.tank_ok)
                    },
                    color = if (r.water <= l.waterMin) Danger else Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (u != null) {
            Row(Modifier.padding(top = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.water_today), color = Muted, fontSize = 12.sp)
                    Text(stringResource(R.string.liters, u.todayL.f1()), color = TextC, fontSize = 22.sp, style = Tnum)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.power_today), color = Muted, fontSize = 12.sp)
                    Text(stringResource(R.string.kwh, u.todayKwh.fx(2)), color = TextC, fontSize = 22.sp, style = Tnum)
                }
            }
            val all = days.map { it.l } + u.todayL
            val mx = max(0.1f, all.maxOrNull() ?: 0.1f)
            Row(Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                all.forEachIndexed { i, v ->
                    Box(Modifier.weight(1f).height((34 * v / mx).coerceAtLeast(3f).dp).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(if (i == all.size - 1) Blue else Blue.copy(alpha = .6f)))
                }
            }
        }
    }
}
