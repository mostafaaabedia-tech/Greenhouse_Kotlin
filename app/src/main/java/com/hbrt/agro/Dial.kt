package com.hbrt.agro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

/** Time of day for the sky: 0 night, 1 dawn, 2 day, 3 dusk. */
fun timeOfDay(): Int {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when { h < 5 || h >= 20 -> 0; h < 7 -> 1; h < 17 -> 2; else -> 3 }
}

fun skyColors(tod: Int): List<Color> = when (tod) {
    0 -> if (Pal.dark) listOf(Color(0xFF040914), Color(0xFF0D1631), Color(0xFF111C3B)) else listOf(Color(0xFF0B1736), Color(0xFF1D2B5A), Color(0xFF2A3A70))
    1 -> listOf(Color(0xFF3D3A7A), Color(0xFFF39C6B), Color(0xFFFFD09A))
    3 -> listOf(Color(0xFF2C2458), Color(0xFF9A4E8C), Color(0xFFF08A5D))
    else -> if (Pal.dark) listOf(Color(0xFF123B5E), Color(0xFF2D6F9E), Color(0xFF4D8FB8)) else listOf(Color(0xFF6EC2FF), Color(0xFFBFE6FF), Color(0xFFE3F4FF))
}

/** True when text over this sky should be dark. */
fun skyIsLight(tod: Int) = !Pal.dark && (tod == 2 || tod == 1)

/** The sky behind the hero: sun or moon and stars, drifting clouds, rain when the forecast says so. */
@Composable
fun Sky(tod: Int, wxKindNow: Int, shape: Shape, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "sky")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(46_000, easing = LinearEasing)), label = "drift")
    val rain by t.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing)), label = "rain")
    val twinkle by t.animateFloat(.35f, .85f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "tw")
    val cols = skyColors(tod)
    val clouds = if (wxKindNow >= 2) 3 else if (wxKindNow >= 1) 2 else 1
    val rainy = wxKindNow in 4..8 && wxKindNow != 6
    Box(modifier.clip(shape).background(Brush.verticalGradient(cols))) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            if (tod == 0) {
                val stars = listOf(.12f to .18f, .3f to .08f, .52f to .22f, .7f to .12f, .86f to .3f, .08f to .42f, .4f to .36f, .64f to .05f, .93f to .1f)
                stars.forEachIndexed { i, (x, y) -> drawCircle(Color.White.copy(alpha = if (i % 2 == 0) twinkle else 1.1f - twinkle), 1.6.dp.toPx(), Offset(w * x, h * y)) }
                drawCircle(Color(0xFFF4F1D0), 19.dp.toPx(), Offset(w - 46.dp.toPx(), 40.dp.toPx()))
                drawCircle(cols[0], 17.dp.toPx(), Offset(w - 38.dp.toPx(), 34.dp.toPx()))
            } else {
                val c = Offset(w - 48.dp.toPx(), 42.dp.toPx())
                drawCircle(Brush.radialGradient(listOf(Color(0x88FFC93C), Color.Transparent), c, 60.dp.toPx()), 60.dp.toPx(), c)
                drawCircle(Color(0xFFFFC93C), 23.dp.toPx(), c)
            }
            val cloudCol = if (tod == 0 || tod == 3) Color(0x59BEC8E6) else Color(0xD9FFFFFF)
            for (i in 0 until clouds) {
                val f = (drift + i * 0.37f) % 1f
                val x = -140.dp.toPx() + (w + 280.dp.toPx()) * f
                val y = (26 + i * 34).dp.toPx()
                val s = 1f + i * 0.25f
                drawCircle(cloudCol, 15.dp.toPx() * s, Offset(x + 30.dp.toPx() * s, y))
                drawCircle(cloudCol, 12.dp.toPx() * s, Offset(x + 50.dp.toPx() * s, y + 4.dp.toPx()))
                drawRoundRect(cloudCol, Offset(x + 10.dp.toPx() * s, y), Size(64.dp.toPx() * s, 20.dp.toPx() * s), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx() * s))
            }
            if (rainy) {
                val gap = 22.dp.toPx()
                var x = 0f
                var k = 0
                while (x < w) {
                    val y0 = ((rain + (k % 5) * 0.2f) % 1f) * h
                    drawLine(Color(0x8CC8E1FF), Offset(x, y0), Offset(x - 3.dp.toPx(), y0 + 14.dp.toPx()), 1.6.dp.toPx(), cap = StrokeCap.Round)
                    x += gap; k++
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x47000000)), h * 0.78f, h), Offset(0f, h * 0.78f), Size(w, h * 0.22f))
        }
        content()
    }
}


/**
 * Four rings (air, humidity, soil, tank) around a living plant: it sways, droops when the soil is
 * dry, flowers and fruits with its growth stage, drinks while the pump runs, and glows under the lights.
 */
@Composable
fun PlantDial(r: Reading?, cfg: Cfg?, modifier: Modifier) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val reveal = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
    val fT by animateFloatAsState(((r?.temperature ?: 0f) / 45f).coerceIn(0f, 1f), tween(1100), label = "t")
    val fH by animateFloatAsState(((r?.humidity ?: 0f) / 100f).coerceIn(0f, 1f), tween(1100), label = "h")
    val fS by animateFloatAsState(((r?.soil ?: 0) / 100f).coerceIn(0f, 1f), tween(1100), label = "s")
    val fW by animateFloatAsState(((r?.water ?: 0) / 100f).coerceIn(0f, 1f), tween(1100), label = "w")
    val l = cfg ?: Cfg()
    val droopT by animateFloatAsState(if (r != null && r.soil < l.soilMin) 1f else 0f, tween(1200), label = "droop")
    val t = rememberInfiniteTransition(label = "plant")
    val sway by t.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val drip by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "drip")
    val wind by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wind")
    val health = r?.health(cfg) ?: 100
    val leaf = when { health >= 80 -> Color(0xFF46B764) to Color(0xFF5BD27A); health >= 50 -> Color(0xFF8FB944) to Color(0xFFA6C95A); else -> Color(0xFFC0A03A) to Color(0xFFCDB256) }
    val stage = r?.stage ?: ""
    val flowering = stage in listOf("flowering", "fruiting", "harvest")
    val fruiting = stage in listOf("fruiting", "harvest")
    val colors = listOf(Orange, Cyan, Lime, Blue)
    val soilM = ((r?.soil ?: 0) / 100f).coerceIn(0f, 1f)
    Canvas(modifier) {
        val k = size.minDimension / 300f
        translate((size.width - 300f * k) / 2, (size.height - 300f * k) / 2) {
            scale(k, k, Offset.Zero) {
                // rings
                val fr = listOf(fT, fH, fS, fW)
                listOf(142f, 126f, 110f, 94f).forEachIndexed { i, rad ->
                    val tl = Offset(150 - rad, 150 - rad); val sz = Size(rad * 2, rad * 2)
                    drawArc(Color.White.copy(alpha = .16f), 0f, 360f, false, tl, sz, style = Stroke(10f))
                    drawArc(colors[i], -90f, 360f * fr[i] * reveal.value, false, tl, sz, style = Stroke(10f, cap = StrokeCap.Round))
                }
                // lights glow, heater warmth
                if (r?.lights == true) drawCircle(Brush.radialGradient(listOf(Color(0x8CFFD86B), Color.Transparent), Offset(150f, 112f), 74f), 74f, Offset(150f, 112f))
                if (r?.heater == true) drawOval(Brush.radialGradient(listOf(Color(0x99FF7A3C), Color.Transparent), Offset(150f, 226f), 74f), Offset(76f, 206f), Size(148f, 40f))
                // breeze when the fan or vent runs
                if (r?.fan == true || r?.servo == true) {
                    for (j in 0..1) {
                        val p = (wind + j * .5f) % 1f
                        val y = 118f + j * 22f
                        val x0 = 66f + 70f * p
                        drawLine(Color(0xCCE6F7FF), Offset(x0, y), Offset(x0 + 26f, y), 3f, cap = StrokeCap.Round)
                    }
                }
                val small = stage == "seedling"
                val plant: DrawScope.() -> Unit = {
                    rotate(sway, Offset(150f, 196f)) {
                        drawLine(Color(0xFF2E8B4A), Offset(150f, 192f), Offset(150f, 94f), 5f, cap = StrokeCap.Round)
                        rotate(18f * droopT, Offset(150f, 168f)) { drawPath(leafPath(150f, 168f, -54f, 0f), leaf.first) }
                        rotate(-18f * droopT, Offset(150f, 142f)) { drawPath(leafPath(150f, 142f, 56f, -2f), leaf.second) }
                        rotate(18f * droopT, Offset(150f, 118f)) { drawPath(leafPath(150f, 118f, -44f, -10f), leaf.first) }
                        rotate(-sway, Offset(150f, 98f)) { drawPath(leafPath(150f, 98f, 38f, -19f), leaf.second) }
                        if (flowering) { drawCircle(Color(0xFFFFE27A), 6f, Offset(150f, 92f)); drawCircle(Color(0xFFFFE27A), 4.5f, Offset(114f, 110f)) }
                        if (fruiting) {
                            drawCircle(Color(0xFFFF5E4A), 9f, Offset(186f, 152f)); drawCircle(Color(0xFFFF7A59), 7f, Offset(172f, 161f))
                            drawCircle(Color(0xFFFF8A4A), 7f, Offset(114f, 176f))
                        }
                    }
                }
                if (small) scale(.7f, .7f, Offset(150f, 192f)) { plant() } else plant()
                // water drops while the pump runs
                if (r?.pump == true || (r?.watering ?: 0) > 0) for (j in 0..2) {
                    val p = (drip + j / 3f) % 1f
                    drawCircle(Color(0xFF8FD2FF).copy(alpha = sin(p * PI).toFloat()), 3.5f, Offset(138f + j * 12.5f, 56f + 62f * p))
                }
                // pot
                val pot = Path().apply {
                    moveTo(118f, 192f); lineTo(182f, 192f); lineTo(174f, 226f)
                    quadraticBezierTo(173f, 231f, 167f, 231f); lineTo(133f, 231f); quadraticBezierTo(127f, 231f, 126f, 226f); close()
                }
                drawPath(pot, Color(0xFF1F8A84))
                drawRoundRect(Color(0xFF2BA89A), Offset(112f, 184f), Size(76f, 13f), androidx.compose.ui.geometry.CornerRadius(6f))
                drawOval(lerp(Color(0xFF966E46), Color(0xFF3C2814), soilM), Offset(117f, 184f), Size(66f, 8f))
            }
        }
    }
}

/** A leaf from (x, y) reaching out by (dx, dy). */
private fun leafPath(x: Float, y: Float, dx: Float, dy: Float): Path = Path().apply {
    moveTo(x, y)
    cubicTo(x + dx * .4f, y + dy - 14f, x + dx * .8f, y + dy - 12f, x + dx, y + dy)
    cubicTo(x + dx * .7f, y + dy + 10f, x + dx * .3f, y + 10f, x, y)
    close()
}
