package com.hbrt.agro

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/* ---------- Palette: night greenhouse, chlorophyll green, sodium-lamp amber ---------- */
val Bg = Color(0xFF07110C)
val CardC = Color(0xFF0F1D16)
val CardHi = Color(0xFF15281E)
val Border = Color(0xFF1E3628)
val Neon = Color(0xFF3DFF5A)
val Leaf = Color(0xFF2E8B57)
val Orange = Color(0xFFFF9A1F)
val TextC = Color(0xFFE6EFE8)
val Muted = Color(0xFF7E9787)
val Danger = Color(0xFFFF5252)
val DarkC = Color(0xFF040A07)
val Blue = Color(0xFF29B6FF)
val Gold = Color(0xFFFFD34D)

val Tnum = TextStyle(fontFeatureSettings = "tnum")

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

fun Modifier.card(radius: Dp = 20.dp, color: Color = CardC) =
    clip(RoundedCornerShape(radius)).background(color).border(1.dp, Border, RoundedCornerShape(radius))

/** Press-to-shrink + haptic tick. Used on every tappable tile. */
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f), label = "press")
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

fun healthColor(score: Int) = when {
    score >= 80 -> Neon
    score >= 50 -> Orange
    else -> Danger
}

/* =================== Text & buttons =================== */

@Composable
fun Brand(size: Int = 24, suffix: String = "") {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Neon)) { append("HBRT") }
            withStyle(SpanStyle(color = Color.White)) { append(".") }
            if (suffix.isNotEmpty()) withStyle(SpanStyle(color = TextC, fontWeight = FontWeight.Light)) { append(" $suffix") }
        },
        fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
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
fun PageTitle(text: String, sub: String? = null) {
    Column {
        Text(text, color = TextC, fontSize = 28.sp, fontWeight = FontWeight.Black)
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun SecondaryButton(
    text: String, enabled: Boolean = true, color: Color = Orange,
    modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) color else Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
fun SectionCard(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(title, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        content()
    }
}

@Composable
fun field() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon, unfocusedBorderColor = Border, focusedLabelColor = Neon,
    unfocusedLabelColor = Muted, cursorColor = Neon, focusedContainerColor = DarkC, unfocusedContainerColor = DarkC
)

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextC, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Neon))
    }
}

/* =================== Status dot (pulses while online) =================== */

@Composable
fun PulseDot(color: Color, pulsing: Boolean, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "k")
    Canvas(Modifier.size(size * 2.4f)) {
        val r = size.toPx() / 2
        if (pulsing) drawCircle(color.copy(alpha = (1 - k) * .45f), r * (1 + 1.4f * k))
        drawCircle(color, r)
    }
}

/* =================== Animated device icons (drawn, no image files) =================== */

@Composable
fun FanIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(on) {
        if (on) {
            while (isActive) angle.animateTo(angle.value + 360f, tween(650, easing = LinearEasing))
        } else {
            angle.animateTo(ceil(angle.value / 120f) * 120f, spring(stiffness = Spring.StiffnessLow))
        }
    }
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        rotate(angle.value, c) {
            for (i in 0 until 3) rotate(i * 120f, c) {
                drawOval(color, Offset(c.x - r * 0.22f, c.y - r * 0.95f), Size(r * 0.44f, r * 0.8f))
            }
        }
        drawCircle(CardC, r * 0.2f, c)
        drawCircle(color, r * 0.13f, c)
    }
}

@Composable
fun DropIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "drip")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "k")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val r = w * 0.26f
        val cy = h * 0.52f
        val p = Path().apply {
            moveTo(cx, h * 0.06f)
            cubicTo(cx + r * 0.3f, cy - r * 1.1f, cx + r, cy - r * 0.4f, cx + r, cy)
            cubicTo(cx + r, cy + r * 1.1f, cx - r, cy + r * 1.1f, cx - r, cy)
            cubicTo(cx - r, cy - r * 0.4f, cx - r * 0.3f, cy - r * 1.1f, cx, h * 0.06f)
            close()
        }
        drawPath(p, color)
        if (on) {
            val y = cy + r + (h - cy - r) * k
            drawCircle(color.copy(alpha = 1f - k), w * 0.06f, Offset(cx, y))
        }
    }
}

@Composable
fun BulbIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "glow")
    val g by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2, h * 0.42f)
        val r = w * 0.28f
        if (on) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .55f * g), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
        drawCircle(color, r, c, style = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(w * 0.07f))
        drawRoundRect(color, Offset(w / 2 - r * 0.55f, h * 0.68f), Size(r * 1.1f, h * 0.18f), CornerRadius(w * 0.04f))
    }
}

@Composable
fun FlameIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "flame")
    val f by t.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "f")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sy = if (on) f else 0.9f
        scale(1f, sy, Offset(w / 2, h * 0.92f)) {
            val p = Path().apply {
                moveTo(w / 2, h * 0.06f)
                cubicTo(w * 0.95f, h * 0.45f, w * 0.85f, h * 0.92f, w / 2, h * 0.92f)
                cubicTo(w * 0.15f, h * 0.92f, w * 0.05f, h * 0.5f, w * 0.38f, h * 0.3f)
                cubicTo(w * 0.36f, h * 0.48f, w * 0.48f, h * 0.55f, w / 2, h * 0.06f)
                close()
            }
            drawPath(p, color)
            if (on) drawCircle(Gold, w * 0.12f, Offset(w / 2, h * 0.72f))
        }
    }
}

@Composable
fun VentIcon(open: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val a by animateFloatAsState(if (open) 55f else 0f, spring(dampingRatio = 0.55f), label = "vent")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, Offset(w * 0.06f, h * 0.06f), Size(w * 0.88f, h * 0.88f), CornerRadius(w * 0.12f), style = Stroke(w * 0.07f))
        for (i in 0 until 3) {
            val y = h * (0.3f + i * 0.2f)
            rotate(-a, Offset(w / 2, y)) {
                drawLine(color, Offset(w * 0.24f, y), Offset(w * 0.76f, y), w * 0.08f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun LeafMark(modifier: Modifier = Modifier.size(56.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.15f, h * 0.85f)
            cubicTo(w * 0.1f, h * 0.3f, w * 0.55f, h * 0.08f, w * 0.9f, h * 0.1f)
            cubicTo(w * 0.92f, h * 0.5f, w * 0.65f, h * 0.9f, w * 0.15f, h * 0.85f)
            close()
        }
        drawPath(p, Brush.linearGradient(listOf(Neon, Leaf), Offset(0f, h), Offset(w, 0f)))
        drawLine(Bg, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.75f, h * 0.25f), w * 0.05f, cap = StrokeCap.Round)
    }
}

/* =================== Health ring (hero) =================== */

@Composable
fun HealthRing(score: Int?, modifier: Modifier = Modifier.size(132.dp)) {
    val target = (score ?: 0) / 100f
    val sweep by animateFloatAsState(target, tween(1200, easing = FastOutSlowInEasing), label = "health")
    val shown by animateIntAsState(score ?: 0, tween(1200, easing = FastOutSlowInEasing), label = "num")
    val col by animateColorAsState(healthColor(score ?: 0), tween(600), label = "col")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 12.dp.toPx()
            val tl = Offset(s / 2, s / 2)
            val sz = Size(size.width - s, size.height - s)
            drawArc(Color.White.copy(alpha = .06f), 135f, 270f, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(col, 135f, 270f * sweep, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (score == null) "--" else "$shown", color = TextC, fontSize = 40.sp, fontWeight = FontWeight.Black, style = Tnum)
            Text("plant health", color = Muted, fontSize = 11.sp)
        }
    }
}

/* =================== Water tank with moving surface =================== */

@Composable
fun TankCard(level: Int?, emptyAt: Int, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(((level ?: 0) / 100f).coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "tank")
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val low = level != null && level <= emptyAt + 10
    Row(modifier.card().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(64.dp, 86.dp)) {
            val w = size.width
            val h = size.height
            val shape = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(w * 0.22f)))
            }
            clipPath(shape) {
                drawRect(Color.White.copy(alpha = .05f))
                val top = h * (1 - fill)
                val amp = if (fill in 0.02f..0.98f) h * 0.035f else 0f
                val wave = Path().apply {
                    moveTo(0f, h)
                    var x = 0f
                    while (x <= w) {
                        lineTo(x, top + amp * sin(phase + x / w * 2 * PI.toFloat()))
                        x += 2f
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(wave, Brush.verticalGradient(listOf(Blue, Blue.copy(alpha = .55f)), top, h))
            }
            drawPath(shape, Border, style = Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Water tank", color = Muted, fontSize = 13.sp)
            Text(level?.let { "$it%" } ?: "--", color = TextC, fontSize = 34.sp, fontWeight = FontWeight.Black, style = Tnum)
            Text(
                when {
                    level == null -> "Waiting for data"
                    level <= emptyAt -> "Empty. Refill to allow watering."
                    low -> "Running low. Refill soon."
                    else -> "Enough water for irrigation"
                },
                color = if (low) Danger else Muted, fontSize = 12.sp
            )
        }
    }
}

/* =================== Metric tile with trend and sparkline =================== */

@Composable
fun MetricTile(
    label: String, value: Float?, unit: String, decimals: Int, color: Color,
    history: List<Float>, bad: Boolean, modifier: Modifier,
) {
    val v by animateFloatAsState(value ?: 0f, tween(700), label = "v")
    val trend = if (history.size >= 15) history.last() - history[history.size - 15] else 0f
    val accent by animateColorAsState(if (bad) Danger else color, tween(500), label = "acc")
    Column(modifier.card().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val arrow = when {
                trend > 0.4f -> "↑"
                trend < -0.4f -> "↓"
                else -> "→"
            }
            Text(arrow, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (value == null) "--" else String.format(Locale.US, "%.${decimals}f", v),
                color = if (bad) Danger else TextC, fontSize = 30.sp, fontWeight = FontWeight.Black, style = Tnum
            )
            Text(unit, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        }
        Spacer(Modifier.height(6.dp))
        Spark(history, accent, Modifier.fillMaxWidth().height(26.dp))
    }
}

@Composable
fun Spark(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < 1f) { hi += .5f; lo -= .5f }
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/* =================== Line chart =================== */

data class Series(val name: String, val color: Color, val values: List<Float>)

@Composable
fun ChartCard(title: String, series: List<Series>, height: Dp = 170.dp) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Text(title, color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val all = series.flatMap { it.values }
        if (all.size < 4) {
            drawText(measurer, "Collecting data…", Offset(0f, size.height / 2 - 8.dp.toPx()), axis)
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
            drawLine(Leaf.copy(alpha = .16f), Offset(padL, y), Offset(size.width, y), 1f)
            val v = hi - (hi - lo) * i / 3f
            drawText(measurer, String.format(Locale.US, "%.0f", v), Offset(0f, (y - 7.dp.toPx()).coerceIn(0f, h - 14.dp.toPx())), axis)
        }
        clipPath(Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, padL + w * reveal.value, h)) }) {
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
}

/* =================== Radar (shown while scanning) =================== */

@Composable
fun Radar(modifier: Modifier = Modifier.size(180.dp)) {
    val t = rememberInfiniteTransition(label = "radar")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "a")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "k")
    Canvas(modifier) {
        val r = size.minDimension / 2
        for (i in 1..3) drawCircle(Leaf.copy(alpha = .35f), r * i / 3f, style = Stroke(1.dp.toPx()))
        drawCircle(Neon.copy(alpha = (1 - k) * .5f), r * k, style = Stroke(2.dp.toPx()))
        rotate(a) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Neon.copy(alpha = .45f)), center),
                -60f, 60f, true
            )
        }
        drawCircle(Neon, 5.dp.toPx())
    }
}

/** Small cloud glyph for "connected through the cloud". */
@Composable
fun CloudIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(color, w * 0.22f, Offset(w * 0.34f, h * 0.58f))
        drawCircle(color, w * 0.27f, Offset(w * 0.56f, h * 0.46f))
        drawCircle(color, w * 0.18f, Offset(w * 0.78f, h * 0.62f))
        drawRoundRect(color, Offset(w * 0.14f, h * 0.58f), Size(w * 0.80f, h * 0.24f), CornerRadius(h * 0.12f))
    }
}

/** "12 s ago", "4 min ago", "3 h ago". */
fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
