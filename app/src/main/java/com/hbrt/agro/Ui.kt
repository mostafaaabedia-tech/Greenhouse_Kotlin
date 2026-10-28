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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

/* ---------------- time words ---------------- */
fun ago(c: Context, ms: Long): String {
    val s = max(0L, ms / 1000)
    return when {
        s < 5 -> c.getString(R.string.just_now)
        s < 60 -> c.getString(R.string.ago_s, s)
        s < 3600 -> c.getString(R.string.ago_m, s / 60)
        s < 86400 -> c.getString(R.string.ago_h, s / 3600)
        else -> c.getString(R.string.ago_d, s / 86400)
    }
}
fun dur(c: Context, sec: Long): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return if (h >= 24) c.getString(R.string.dur_dh, h / 24, h % 24) else c.getString(R.string.dur_hm, h, m)
}
fun timeStr(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, Lang.locale()).format(Date(ms))
fun dateStr(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Lang.locale()).format(Date(ms))
fun weekdayShort(ms: Long): String = java.text.SimpleDateFormat("EEE", Lang.locale()).format(Date(ms))

@Composable fun devName(d: String) = stringResource(
    when (d) { "fan" -> R.string.d_fan; "pump" -> R.string.d_pump; "lights" -> R.string.d_lights; "heater" -> R.string.d_heater; else -> R.string.d_servo }
)
@Composable fun modeName(m: String) = stringResource(when (m) { "manual" -> R.string.mode_manual; "away" -> R.string.mode_away; else -> R.string.mode_auto })
@Composable fun stageName(s: String) = stringResource(
    when (s) { "growing" -> R.string.st_growing; "flowering" -> R.string.st_flowering; "fruiting" -> R.string.st_fruiting; "harvest" -> R.string.st_harvest; else -> R.string.st_seedling }
)
@Composable fun cropName(id: String) = stringResource(
    when (id) {
        "tomato" -> R.string.c_tomato; "cucumber" -> R.string.c_cucumber; "pepper" -> R.string.c_pepper
        "lettuce" -> R.string.c_lettuce; "strawberry" -> R.string.c_strawberry; "herbs" -> R.string.c_herbs; else -> R.string.c_none
    }
)

/* ---------------- surfaces ---------------- */
fun Modifier.card(radius: Dp = 24.dp, color: Color? = null) = composed {
    this.clip(RoundedCornerShape(radius)).background(color ?: CardC).border(1.dp, Border, RoundedCornerShape(radius))
}

fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed && enabled) .94f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "press")
    this.graphicsLayer { scaleX = k; scaleY = k }.clickable(src, null, enabled, onClick = onClick)
}

/** Staggered fade-and-rise when a screen opens. */
@Composable
fun Enter(index: Int, content: @Composable () -> Unit) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(index * 60L)
        a.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 30.dp.toPx() }) { content() }
}

@Composable
fun PageTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        Text(text, color = TextC, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun H2(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
fun SectionCard(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            H2(title)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
        }
        content()
    }
}

/* ---------------- buttons ---------------- */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = OnNeon, disabledContainerColor = Track, disabledContentColor = Muted)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** Calm button for section "Save" actions, so the bright one stays special. */
@Composable
fun SoftButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Neon.copy(alpha = .5f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Neon.copy(alpha = .14f), contentColor = TextC)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, color: Color? = null, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val c = color ?: TextC
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) (if (color == null) Border else c) else Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
fun field() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon, unfocusedBorderColor = Border, focusedLabelColor = Neon,
    unfocusedLabelColor = Muted, cursorColor = Neon, focusedContainerColor = Bg, unfocusedContainerColor = Bg,
    focusedTextColor = TextC, unfocusedTextColor = TextC,
)

@Composable
fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = field(), modifier = modifier, shape = RoundedCornerShape(14.dp)
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = OnNeon, checkedTrackColor = Neon, uncheckedTrackColor = Track, uncheckedBorderColor = Border))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Neon else CardHi, tween(220), label = "chip")
    val fg by animateColorAsState(if (selected) OnNeon else TextC, tween(220), label = "chipt")
    Text(
        label,
        Modifier.clip(CircleShape).background(bg).border(1.dp, if (selected) Neon else Border, CircleShape)
            .bounceClick(enabled, onClick).graphicsLayer { alpha = if (enabled) 1f else .45f }.padding(horizontal = 15.dp, vertical = 9.dp),
        color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 5.dp), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/* ---------------- status dot ---------------- */
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

/* ---------------- animated device icons (drawn, no image files) ---------------- */
@Composable
fun DevIcon(d: String, on: Boolean, color: Color, modifier: Modifier = Modifier.size(30.dp)) = when (d) {
    "fan" -> FanIcon(on, color, modifier)
    "pump" -> DropIcon(on, color, modifier)
    "lights" -> BulbIcon(on, color, modifier)
    "heater" -> FlameIcon(on, color, modifier)
    else -> VentIcon(on, color, modifier)
}

@Composable
fun FanIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(on) {
        if (on) while (isActive) angle.animateTo(angle.value + 360f, tween(650, easing = LinearEasing))
        else angle.animateTo(ceil(angle.value / 90f) * 90f, spring(stiffness = Spring.StiffnessLow))
    }
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        rotate(angle.value, c) {
            for (i in 0 until 4) rotate(i * 90f, c) {
                drawOval(color, Offset(c.x - r * 0.2f, c.y - r * 0.95f), Size(r * 0.42f, r * 0.82f))
            }
        }
        drawCircle(color, r * 0.18f, c)
    }
}

@Composable
fun DropIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "drip")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "k")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2; val r = w * 0.26f; val cy = h * 0.5f
        val p = Path().apply {
            moveTo(cx, h * 0.06f)
            cubicTo(cx + r * 0.3f, cy - r * 1.1f, cx + r, cy - r * 0.4f, cx + r, cy)
            cubicTo(cx + r, cy + r * 1.1f, cx - r, cy + r * 1.1f, cx - r, cy)
            cubicTo(cx - r, cy - r * 0.4f, cx - r * 0.3f, cy - r * 1.1f, cx, h * 0.06f)
            close()
        }
        drawPath(p, color)
        if (on) drawCircle(color.copy(alpha = 1f - k), w * 0.06f, Offset(cx, cy + r + (h - cy - r) * k))
    }
}

@Composable
fun BulbIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "glow")
    val g by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Offset(w / 2, h * 0.42f); val r = w * 0.28f
        if (on) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .5f * g), Color.Transparent), c, r * 2.1f), r * 2.1f, c)
        drawCircle(color, r, c, style = if (on) Fill else Stroke(w * 0.07f))
        drawRoundRect(color, Offset(w / 2 - r * 0.55f, h * 0.7f), Size(r * 1.1f, h * 0.17f), CornerRadius(w * 0.04f))
    }
}

@Composable
fun FlameIcon(on: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val t = rememberInfiniteTransition(label = "flame")
    val f by t.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "f")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        scale(1f, if (on) f else 0.92f, Offset(w / 2, h * 0.92f)) {
            val p = Path().apply {
                moveTo(w / 2, h * 0.06f)
                cubicTo(w * 0.95f, h * 0.45f, w * 0.85f, h * 0.92f, w / 2, h * 0.92f)
                cubicTo(w * 0.15f, h * 0.92f, w * 0.05f, h * 0.5f, w * 0.38f, h * 0.3f)
                cubicTo(w * 0.36f, h * 0.48f, w * 0.48f, h * 0.55f, w / 2, h * 0.06f)
                close()
            }
            drawPath(p, color)
            if (on) drawCircle(Color(0xFFFFE27A), w * 0.11f, Offset(w / 2, h * 0.72f))
        }
    }
}

@Composable
fun VentIcon(open: Boolean, color: Color, modifier: Modifier = Modifier.size(34.dp)) {
    val a by animateFloatAsState(if (open) 50f else 0f, spring(dampingRatio = 0.55f), label = "vent")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(color, Offset(w * 0.06f, h * 0.1f), Size(w * 0.88f, h * 0.8f), CornerRadius(w * 0.14f), style = Stroke(w * 0.08f))
        for (i in 0 until 3) {
            val y = h * (0.32f + i * 0.18f)
            rotate(-a, Offset(w / 2, y)) { drawLine(color, Offset(w * 0.26f, y), Offset(w * 0.74f, y), w * 0.08f, cap = StrokeCap.Round) }
        }
    }
}

@Composable
fun PowerIcon(color: Color, modifier: Modifier = Modifier.size(30.dp)) {
    Canvas(modifier) {
        val w = size.width
        val s = Stroke(w * 0.1f, cap = StrokeCap.Round)
        drawArc(color, -55f, 290f, false, Offset(w * 0.16f, w * 0.2f), Size(w * 0.68f, w * 0.68f), style = s)
        drawLine(color, Offset(w / 2, w * 0.1f), Offset(w / 2, w * 0.48f), w * 0.1f, cap = StrokeCap.Round)
    }
}

@Composable
fun LeafMark(modifier: Modifier = Modifier.size(56.dp)) {
    val c = Neon
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.84f, h * 0.12f)
            cubicTo(w * 0.44f, h * 0.12f, w * 0.19f, h * 0.34f, w * 0.19f, h * 0.66f)
            cubicTo(w * 0.19f, h * 0.73f, w * 0.2f, h * 0.8f, w * 0.23f, h * 0.84f)
            cubicTo(w * 0.28f, h * 0.62f, w * 0.42f, h * 0.47f, w * 0.64f, h * 0.38f)
            cubicTo(w * 0.47f, h * 0.5f, w * 0.36f, h * 0.66f, w * 0.31f, h * 0.86f)
            cubicTo(w * 0.38f, h * 0.89f, w * 0.44f, h * 0.9f, w * 0.5f, h * 0.9f)
            cubicTo(w * 0.81f, h * 0.9f, w * 0.97f, h * 0.62f, w * 0.84f, h * 0.12f)
            close()
        }
        drawPath(p, c)
    }
}

@Composable
fun CloudIcon(color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(color, w * 0.22f, Offset(w * 0.34f, h * 0.58f))
        drawCircle(color, w * 0.27f, Offset(w * 0.56f, h * 0.46f))
        drawCircle(color, w * 0.18f, Offset(w * 0.78f, h * 0.62f))
        drawRoundRect(color, Offset(w * 0.14f, h * 0.58f), Size(w * 0.8f, h * 0.24f), CornerRadius(h * 0.12f))
    }
}

/** Weather symbol for a [wxKind]. */
@Composable
fun WxIcon(kind: Int, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val s = Stroke(w * 0.08f, cap = StrokeCap.Round)
        if (kind == 0) {
            drawCircle(color, w * 0.2f, center, style = s)
            for (i in 0 until 8) rotate(i * 45f, center) { drawLine(color, Offset(w / 2, h * 0.04f), Offset(w / 2, h * 0.16f), w * 0.08f, cap = StrokeCap.Round) }
            return@Canvas
        }
        val p = Path().apply {
            moveTo(w * 0.3f, h * 0.66f)
            cubicTo(w * 0.1f, h * 0.66f, w * 0.1f, h * 0.36f, w * 0.3f, h * 0.38f)
            cubicTo(w * 0.34f, h * 0.18f, w * 0.66f, h * 0.16f, w * 0.7f, h * 0.36f)
            cubicTo(w * 0.92f, h * 0.36f, w * 0.92f, h * 0.66f, w * 0.72f, h * 0.66f)
            close()
        }
        drawPath(p, color, style = s)
        if (kind in 4..7 && kind != 6) for (i in 0 until 3) drawLine(color, Offset(w * (0.36f + i * 0.14f), h * 0.76f), Offset(w * (0.32f + i * 0.14f), h * 0.92f), w * 0.07f, cap = StrokeCap.Round)
        if (kind == 6) for (i in 0 until 3) drawCircle(color, w * 0.04f, Offset(w * (0.34f + i * 0.16f), h * 0.84f))
        if (kind == 8) drawLine(color, Offset(w * 0.52f, h * 0.7f), Offset(w * 0.44f, h * 0.92f), w * 0.07f, cap = StrokeCap.Round)
    }
}

/* ---------------- radar (while scanning) ---------------- */
@Composable
fun Radar(modifier: Modifier = Modifier.size(180.dp)) {
    val t = rememberInfiniteTransition(label = "radar")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "a")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "k")
    val n = Neon; val l = Leaf
    Canvas(modifier) {
        val r = size.minDimension / 2
        for (i in 1..3) drawCircle(l.copy(alpha = .35f), r * i / 3f, style = Stroke(1.dp.toPx()))
        drawCircle(n.copy(alpha = (1 - k) * .5f), r * k, style = Stroke(2.dp.toPx()))
        rotate(a) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, n.copy(alpha = .45f)), center), -60f, 60f, true) }
        drawCircle(n, 5.dp.toPx())
    }
}

/* ---------------- water tank with a moving surface ---------------- */
@Composable
fun TankGlass(level: Int?, modifier: Modifier = Modifier.size(64.dp, 92.dp)) {
    val fill by animateFloatAsState(((level ?: 0) / 100f).coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "tank")
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val blue = Blue; val glass = CardHi; val border = Border
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val shape = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(w * 0.24f))) }
        clipPath(shape) {
            drawRect(glass)
            val top = h * (1 - fill)
            val amp = if (fill in 0.02f..0.98f) h * 0.035f else 0f
            val wave = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w) { lineTo(x, top + amp * sin(phase + x / w * 2 * PI.toFloat())); x += 2f }
                lineTo(w, h); close()
            }
            drawPath(wave, Brush.verticalGradient(listOf(blue, blue.copy(alpha = .6f)), top, h))
        }
        drawPath(shape, border, style = Stroke(2.dp.toPx()))
    }
}

/* ---------------- sparkline + line chart ---------------- */
@Composable
fun Spark(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        var lo = values.min(); var hi = values.max()
        if (hi - lo < 1f) { hi += .5f; lo -= .5f }
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

data class Series(val name: String, val color: Color, val values: List<Float>, val dashed: Boolean = false)

@Composable
fun LineChart(series: List<Series>, modifier: Modifier, empty: String) {
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(color = Muted, fontSize = 10.sp)
    val grid = Border
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val reveal = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(series.size) { if (!still) { reveal.snapTo(0f); reveal.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) } }
    Canvas(modifier) {
        val all = series.flatMap { it.values }
        if (all.size < 4) { drawText(measurer, empty, Offset(0f, size.height / 2 - 8.dp.toPx()), axis); return@Canvas }
        var lo = all.min(); var hi = all.max()
        if (hi - lo < 2f) { hi += 1f; lo -= 1f }
        val pad = 30.dp.toPx()
        val left = if (rtl) 0f else pad
        val w = size.width - pad
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            val v = hi - (hi - lo) * i / 3f
            drawText(measurer, v.f0(), Offset(if (rtl) size.width - pad + 6.dp.toPx() else 0f, (y - 7.dp.toPx()).coerceIn(0f, h - 14.dp.toPx())), axis)
        }
        clipPath(Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width * reveal.value, h)) }) {
            series.forEach { s ->
                if (s.values.size < 2) return@forEach
                val n = s.values.size
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val f = i.toFloat() / (n - 1)
                    val x = left + w * (if (rtl) 1 - f else f)
                    val y = h - (v - lo) / (hi - lo) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color.copy(alpha = if (s.dashed) .5f else 1f), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (s.dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 12f)) else null))
            }
        }
    }
}

@Composable
fun Legend(series: List<Series>) {
    Chips {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp, 4.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(6.dp))
                Text(s.name, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Value with a band showing the healthy range (Night Garden style). */
@Composable
fun RangeBar(label: String, value: String, x: Float, lo: Float, hi: Float, min: Float, max: Float, color: Color, lim: String) {
    val pos by animateFloatAsState(((x - min) / (max - min)).coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "mark")
    val track = Track
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(104.dp)) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = TextC, fontSize = 23.sp, style = Tnum)
        }
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val y = size.height / 2
            val bx = { f: Float -> if (rtl) size.width * (1 - f) else size.width * f }
            drawLine(track, Offset(0f, y), Offset(size.width, y), 8.dp.toPx(), cap = StrokeCap.Round)
            val a = ((lo - min) / (max - min)).coerceIn(0f, 1f); val b = ((hi - min) / (max - min)).coerceIn(0f, 1f)
            drawLine(color.copy(alpha = .38f), Offset(bx(a), y), Offset(bx(b), y), 8.dp.toPx(), cap = StrokeCap.Round)
            val mx = bx(pos)
            drawLine(color.copy(alpha = .35f), Offset(mx, 0f), Offset(mx, size.height), 9.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(mx, 0f), Offset(mx, size.height), 4.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(lim, Modifier.width(62.dp).padding(start = 8.dp), color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Border))

@Composable
fun Credits() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.credits), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text("Mostafa Mesbah Aabedi", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Milad Ahmadi", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.team), color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun Fill(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
