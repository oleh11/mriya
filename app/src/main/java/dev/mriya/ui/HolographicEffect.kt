package dev.mriya.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.mriya.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun isReduceMotionEnabled(ctx: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

/**
 * Tracks device tilt using rotation-vector (or accelerometer fallback) for holographic sheen.
 * Returns a fixed static offset when [enabled] is false (e.g. "Remove animations" is on).
 */
@Composable
fun rememberTiltOffset(enabled: Boolean): State<Offset> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Offset(0.22f, -0.14f)) }

    DisposableEffect(ctx, enabled) {
        if (!enabled) {
            state.value = Offset(0.22f, -0.14f)
            return@DisposableEffect onDispose {}
        }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) return@DisposableEffect onDispose {}

        val rotMat = FloatArray(9)
        val orient = FloatArray(3)
        var sx = 0.22f
        var sy = -0.14f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (tx, ty) = if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    SensorManager.getOrientation(rotMat, orient)
                    (orient[2] / 0.8f).coerceIn(-1f, 1f) to (orient[1] / 0.8f).coerceIn(-1f, 1f)
                } else {
                    (-event.values[0] / 6.5f).coerceIn(-1f, 1f) to (event.values[1] / 6.5f).coerceIn(-1f, 1f)
                }
                sx += (tx - sx) * 0.16f
                sy += (ty - sy) * 0.16f
                if (abs(sx - state.value.x) + abs(sy - state.value.y) > 0.008f) {
                    state.value = Offset(sx, sy)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

private data class Glint(val nx: Float, val ny: Float, val phase: Float, val size: Float)

private val glints = listOf(
    Glint(0.14f, 0.20f, -0.65f, 5.5f),
    Glint(0.84f, 0.16f, 0.45f, 6.0f),
    Glint(0.26f, 0.42f, -0.25f, 4.8f),
    Glint(0.74f, 0.44f, 0.20f, 5.2f),
    Glint(0.90f, 0.62f, 0.75f, 4.6f),
    Glint(0.16f, 0.78f, -0.45f, 5.2f),
    Glint(0.55f, 0.74f, 0.05f, 5.6f),
    Glint(0.46f, 0.28f, -0.10f, 4.5f),
    Glint(0.64f, 0.56f, 0.58f, 4.4f),
    Glint(0.34f, 0.88f, -0.52f, 4.8f),
)

/**
 * ICAO e-Passport biometric chip symbol drawn crisply on Canvas.
 */
@Composable
fun BiometricChipIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = 1.4f * density)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, h * 0.14f),
            size = Size(w * 0.88f, h * 0.72f),
            cornerRadius = CornerRadius(2.5f * density, 2.5f * density),
            style = s,
        )
        drawLine(color, Offset(w * 0.06f, h * 0.5f), Offset(w * 0.34f, h * 0.5f), strokeWidth = 1.25f * density)
        drawLine(color, Offset(w * 0.66f, h * 0.5f), Offset(w * 0.94f, h * 0.5f), strokeWidth = 1.25f * density)
        drawCircle(color, radius = minOf(w, h) * 0.17f, center = Offset(w * 0.5f, h * 0.5f), style = s)
    }
}

/**
 * Procedural guilloche lathe lines, counter-shifting optical polycarbonate bands, rosette watermark,
 * and diffraction glints for passport pages. Reads [tiltProvider] strictly inside Canvas draw phase.
 */
@Composable
fun HolographicOverlay(
    tiltProvider: () -> Offset,
    modifier: Modifier = Modifier,
    detailedPassportMode: Boolean = false,
) {
    val guillochePrimary = remember { Path() }
    val guillocheSecondary = remember { Path() }
    val rosette = remember { Path() }
    val mriyaCrest = remember { Path() }
    val star = remember { Path() }
    var lastW = remember { 0f }
    var lastH = remember { 0f }

    Canvas(modifier) {
        val tilt = tiltProvider()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            guillochePrimary.reset()
            guillocheSecondary.reset()
            rosette.reset()
            mriyaCrest.reset()

            val rows = if (detailedPassportMode) 9 else 6
            val steps = 40
            for (r in 0 until rows) {
                val baseY = h * (0.09f + r * (0.82f / rows))
                val amp = 6.5f * density
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = t * w
                    val y1 = baseY + sin(t * 2f * PI.toFloat() * 3.5f + r * 0.85f) * amp
                    val y2 = baseY + cos(t * 2f * PI.toFloat() * 4.2f - r * 0.6f) * (amp * 0.72f)
                    if (i == 0) {
                        guillochePrimary.moveTo(x, y1)
                        guillocheSecondary.moveTo(x, y2)
                    } else {
                        guillochePrimary.lineTo(x, y1)
                        guillocheSecondary.lineTo(x, y2)
                    }
                }
            }

            val ax = w * 0.50f
            val ay = if (detailedPassportMode) h * 0.44f else h * 0.62f

            if (detailedPassportMode) {
                // Fine passport security rosette watermark behind identity biodata.
                val rCx = w * 0.77f
                val rCy = h * 0.68f
                val rRad = minOf(w, h) * 0.14f
                for (ringIdx in 0 until 12) {
                    val angOff = ringIdx * (PI.toFloat() / 6f)
                    for (i in 0..48) {
                        val a = i / 48f * 2f * PI.toFloat()
                        val rr = rRad * (0.65f + 0.35f * sin(a * 6f + angOff))
                        val px = rCx + cos(a) * rr
                        val py = rCy + sin(a) * rr
                        if (i == 0) rosette.moveTo(px, py) else rosette.lineTo(px, py)
                    }
                }
            }
            buildAn225Path(mriyaCrest, ax, ay, unit = minOf(w, h) * 0.017f)
        }

        drawPath(guillochePrimary, Color(0xFF1C1B4B).copy(alpha = 0.055f), style = Stroke(width = 0.85f * density))
        drawPath(guillocheSecondary, Color(0xFF8B5CF6).copy(alpha = 0.045f), style = Stroke(width = 0.7f * density))

        val shimmerStrength = (1f - abs(tilt.x * 0.75f + tilt.y * 0.45f)).coerceIn(0f, 1f)
        if (detailedPassportMode) {
            drawPath(
                rosette,
                Color(0xFF5B3FD0).copy(alpha = 0.045f + 0.065f * shimmerStrength),
                style = Stroke(width = 0.7f * density),
            )
        }
        val ax = w * 0.50f
        val ay = if (detailedPassportMode) h * 0.44f else h * 0.62f
        val crestFill = Brush.linearGradient(
            listOf(
                Color(0xFF38BDF8).copy(alpha = 0.04f + 0.07f * shimmerStrength),
                Color(0xFFA78BFA).copy(alpha = 0.04f + 0.07f * shimmerStrength),
                Color(0xFFF472B6).copy(alpha = 0.04f + 0.07f * shimmerStrength),
            ),
            start = Offset(w * 0.25f, ay - minOf(w, h) * 0.14f),
            end = Offset(w * 0.75f, ay + minOf(w, h) * 0.14f),
        )
        val crestStroke = Brush.linearGradient(
            listOf(
                Color(0xFF38BDF8).copy(alpha = 0.09f + 0.15f * shimmerStrength),
                Color(0xFFA78BFA).copy(alpha = 0.09f + 0.15f * shimmerStrength),
                Color(0xFFF472B6).copy(alpha = 0.09f + 0.15f * shimmerStrength),
            ),
            start = Offset(w * 0.25f, ay - minOf(w, h) * 0.14f),
            end = Offset(w * 0.75f, ay + minOf(w, h) * 0.14f),
        )
        rotate(-24f, Offset(ax, ay)) {
            drawPath(mriyaCrest, crestFill)
            drawPath(mriyaCrest, crestStroke, style = Stroke(width = 1.2f * density))
        }

        // Primary diagonal optical-variable iridescent band shifting with tilt.
        val shiftX = tilt.x * w * 0.48f
        val shiftY = tilt.y * h * 0.36f
        drawRect(
            Brush.linearGradient(
                0.00f to Color.Transparent,
                0.26f to Color(0x2A38BDF8),
                0.42f to Color(0x3CFFFFFF),
                0.55f to Color(0x32A78BFA),
                0.70f to Color(0x2BF472B6),
                0.84f to Color(0x24FBBF24),
                1.00f to Color.Transparent,
                start = Offset(-w * 0.25f + shiftX, -h * 0.15f + shiftY),
                end = Offset(w * 1.25f + shiftX, h * 1.15f + shiftY),
            )
        )

        // Subtle counter-tilted secondary reflection band.
        if (detailedPassportMode) {
            val cShiftX = -tilt.x * w * 0.32f
            val cShiftY = tilt.y * h * 0.28f
            drawRect(
                Brush.linearGradient(
                    0.00f to Color.Transparent,
                    0.38f to Color(0x182DD4BF),
                    0.50f to Color(0x24FEF08A),
                    0.62f to Color(0x1860A5FA),
                    1.00f to Color.Transparent,
                    start = Offset(w * 1.15f + cShiftX, -h * 0.1f + cShiftY),
                    end = Offset(-w * 0.15f + cShiftX, h * 1.1f + cShiftY),
                )
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                cornerRadius = CornerRadius(27f * density, 27f * density),
                style = Stroke(width = 1.2f * density),
            )
        }

        // Tiny 4-point diffraction glints lighting up as the tilt passes each phase.
        val sweep = (tilt.x * 0.8f + tilt.y * 0.5f).coerceIn(-1f, 1f)
        val count = if (detailedPassportMode) glints.size else 8
        for (idx in 0 until count) {
            val g = glints[idx]
            val intensity = (1f - abs(sweep - g.phase) * 2.2f).coerceIn(0f, 1f)
            if (intensity <= 0.05f) continue
            val cx = g.nx * w
            val cy = g.ny * h
            val r = g.size * density * (0.65f + 0.5f * intensity)
            val inner = r * 0.24f
            star.reset()
            star.moveTo(cx, cy - r)
            star.lineTo(cx + inner, cy - inner)
            star.lineTo(cx + r, cy)
            star.lineTo(cx + inner, cy + inner)
            star.lineTo(cx, cy + r)
            star.lineTo(cx - inner, cy + inner)
            star.lineTo(cx - r, cy)
            star.lineTo(cx - inner, cy - inner)
            star.close()
            drawPath(star, Color.White.copy(alpha = 0.70f * intensity))
        }
    }
}

/** Multi-ring rainbow kinegram framing the Vyshyvanka compass seal. */
@Composable
fun KinegramSeal(tiltProvider: () -> Offset, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val tilt = tiltProvider()
            val r = size.minDimension / 2f
            val angle = tilt.x * 115f + tilt.y * 68f
            rotate(angle, center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0xAAF472B6),
                            Color(0xAAFBBF24),
                            Color(0xAA34D399),
                            Color(0xAA38BDF8),
                            Color(0xAAA78BFA),
                            Color(0xAAF472B6),
                        ),
                        center = center,
                    ),
                    radius = r - 1.5f * density,
                    style = Stroke(width = 2.6f * density),
                )
            }
            rotate(-angle * 0.65f, center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0x8838BDF8),
                            Color(0x88FFFFFF),
                            Color(0x88FBBF24),
                            Color(0x8838BDF8),
                        ),
                        center = center,
                    ),
                    radius = r - 5f * density,
                    style = Stroke(width = 1.1f * density),
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.ic_vyshyvanka_compass),
            contentDescription = "Mriya compass seal",
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}

/**
 * Tilt-reactive holographic foil medallion showcasing the Antonov An-225 Mriya silhouette
 * (6 underwing engines + twin H-tail). Reads [tiltProvider] strictly during Canvas draw phase.
 */
@Composable
fun HolographicMriyaIcon(tiltProvider: () -> Offset, modifier: Modifier = Modifier) {
    val planePath = remember { Path() }
    val contrailPath = remember { Path() }
    val starPath = remember { Path() }
    var lastW = remember { 0f }
    var lastH = remember { 0f }

    Canvas(modifier) {
        val tilt = tiltProvider()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            buildAn225Path(planePath, cx, cy, unit = r * 0.078f)
            contrailPath.reset()
            contrailPath.moveTo(cx - r * 0.52f, cy + r * 0.34f)
            contrailPath.quadraticTo(cx - r * 0.34f, cy + r * 0.24f, cx - r * 0.18f, cy + r * 0.11f)
        }

        // Translucent indigo-glass circular base + tilt-reactive radial iridescent glow.
        drawCircle(
            color = Color(0xFF1C1B4B).copy(alpha = 0.12f),
            radius = r - 1.5f * density,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x5538BDF8), Color(0x33A78BFA), Color.Transparent),
                center = Offset(cx + tilt.x * r * 0.35f, cy + tilt.y * r * 0.35f),
                radius = r * 0.92f,
            ),
            radius = r - 1.5f * density,
            center = center,
        )

        // Outer rotating rainbow diffraction ring + inner counter-rotating silver-cyan ring.
        val angle = tilt.x * 130f + tilt.y * 75f
        rotate(angle, center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xCCF472B6),
                        Color(0xCCFBBF24),
                        Color(0xCC34D399),
                        Color(0xCC22D3EE),
                        Color(0xCC818CF8),
                        Color(0xCCF472B6),
                    ),
                    center = center,
                ),
                radius = r - 1.4f * density,
                style = Stroke(width = 2.2f * density),
            )
        }
        rotate(-angle * 0.7f, center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0x9922D3EE),
                        Color(0xAAFFFFFF),
                        Color(0x88818CF8),
                        Color(0x9922D3EE),
                    ),
                    center = center,
                ),
                radius = r - 4.4f * density,
                style = Stroke(width = 0.9f * density),
            )
        }

        // Subtle curved climbing contrail behind the tail.
        drawPath(
            path = contrailPath,
            brush = Brush.linearGradient(
                listOf(Color.Transparent, Color(0xCCFFFFFF)),
                start = Offset(cx - r * 0.52f, cy + r * 0.34f),
                end = Offset(cx - r * 0.18f, cy + r * 0.11f),
            ),
            style = Stroke(width = 1.2f * density, cap = StrokeCap.Round),
        )

        // Prismatic foil An-225 silhouette.
        val shiftX = tilt.x * r * 0.9f
        val shiftY = tilt.y * r * 0.9f
        val foilBrush = Brush.linearGradient(
            listOf(
                Color(0xFF22D3EE),
                Color(0xFFFFFFFF),
                Color(0xFF818CF8),
                Color(0xFFF472B6),
                Color(0xFFFBBF24),
                Color(0xFF34D399),
            ),
            start = Offset(cx - r + shiftX, cy - r + shiftY),
            end = Offset(cx + r + shiftX, cy + r + shiftY),
        )
        rotate(-32f, center) {
            drawPath(planePath, foilBrush)
            drawPath(
                planePath,
                color = Color(0xFF1C1B4B).copy(alpha = 0.45f),
                style = Stroke(width = 0.65f * density),
            )
        }

        // Small 4-point diffraction star glint near the nose/upper wing.
        val glintIntensity = (0.35f + 0.65f * (1f - abs(tilt.x * 0.7f - tilt.y * 0.5f)).coerceIn(0f, 1f))
        val gx = cx + r * 0.28f
        val gy = cy - r * 0.26f
        val gr = (2.8f + 1.4f * glintIntensity) * density
        val gInner = gr * 0.24f
        starPath.reset()
        starPath.moveTo(gx, gy - gr)
        starPath.lineTo(gx + gInner, gy - gInner)
        starPath.lineTo(gx + gr, gy)
        starPath.lineTo(gx + gInner, gy + gInner)
        starPath.lineTo(gx, gy + gr)
        starPath.lineTo(gx - gInner, gy + gInner)
        starPath.lineTo(gx - gr, gy)
        starPath.lineTo(gx - gInner, gy - gInner)
        starPath.close()
        drawPath(starPath, Color.White.copy(alpha = 0.85f * glintIntensity))
    }
}

/**
 * Populates [target] with the authentic Antonov An-225 Mriya geometry (swept wings, 6 underwing
 * engines, and twin H-tail endplates), scaled by [unit] around ([cx], [cy]).
 */
internal fun buildAn225Path(target: Path, cx: Float, cy: Float, unit: Float) {
    fun px(x: Float) = cx + x * unit
    fun py(y: Float) = cy + y * unit

    target.reset()
    target.moveTo(px(7.8f), py(0f))
    target.cubicTo(px(7.8f), py(-0.75f), px(6.4f), py(-1.05f), px(4.8f), py(-1.05f))
    target.lineTo(px(2.4f), py(-1.05f))
    target.lineTo(px(-2.6f), py(-8.0f))
    target.lineTo(px(-3.8f), py(-7.6f))
    target.lineTo(px(-1.0f), py(-1.05f))
    target.lineTo(px(-5.2f), py(-0.85f))
    target.lineTo(px(-6.8f), py(-3.85f))
    target.lineTo(px(-7.7f), py(-3.65f))
    target.lineTo(px(-6.8f), py(-0.6f))
    target.lineTo(px(-7.4f), py(0f))
    target.lineTo(px(-6.8f), py(0.6f))
    target.lineTo(px(-7.7f), py(3.65f))
    target.lineTo(px(-6.8f), py(3.85f))
    target.lineTo(px(-5.2f), py(0.85f))
    target.lineTo(px(-1.0f), py(1.05f))
    target.lineTo(px(-3.8f), py(7.6f))
    target.lineTo(px(-2.6f), py(8.0f))
    target.lineTo(px(2.4f), py(1.05f))
    target.lineTo(px(4.8f), py(1.05f))
    target.cubicTo(px(6.4f), py(1.05f), px(7.8f), py(0.75f), px(7.8f), py(0f))
    target.close()

    val engines = arrayOf(
        1.4f to -2.5f, 0.1f to -4.4f, -1.2f to -6.3f,
        1.4f to 2.5f, 0.1f to 4.4f, -1.2f to 6.3f,
    )
    for ((ex, ey) in engines) {
        target.addOval(Rect(px(ex - 1.2f), py(ey - 0.55f), px(ex + 1.2f), py(ey + 0.55f)))
    }

    target.moveTo(px(-5.2f), py(-3.4f))
    target.lineTo(px(-8.3f), py(-3.4f))
    target.lineTo(px(-8.5f), py(-4.35f))
    target.lineTo(px(-5.6f), py(-4.35f))
    target.close()

    target.moveTo(px(-5.2f), py(3.4f))
    target.lineTo(px(-5.6f), py(4.35f))
    target.lineTo(px(-8.5f), py(4.35f))
    target.lineTo(px(-8.3f), py(3.4f))
    target.close()
}
