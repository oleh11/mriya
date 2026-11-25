package dev.mriya.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.domain.StampDesign
import dev.mriya.domain.StampEmblem
import dev.mriya.domain.StampMood
import dev.mriya.domain.StampShape
import dev.mriya.domain.StampStyle
import kotlin.math.cos
import kotlin.math.sin

private val Cream = Color(0xFFF7F0E1)
private val StitchRed = Color(0xFFC8102E)
private val StitchBlack = Color(0xFF1B1B1B)

// Vyshyvanka "rozha" (rhombus) motif, one stitch per cell.
private val rhombus = listOf(
    "...#...",
    "..###..",
    ".#####.",
    "..###..",
    "...#...",
)
private const val MOTIF_W = 7
private const val GAP = 1

/** Custom inked passport stamp with bespoke country landmarks, local-language entry labels, and optional ink wear. */
@Composable
fun PassportStamp(
    flag: String,
    country: String,
    firstVisit: String,
    detail: String,
    style: StampStyle,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val showWear = remember { !isReduceMotionEnabled(ctx) }
    val ink = Color(style.ink.argb)
    val isGold = style.mood == StampMood.FIRST_VISIT
    val goldBrush = remember {
        Brush.linearGradient(listOf(Color(0xFFD9A21B), Color(0xFF9E6E05), Color(0xFFE7B83B)))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(178.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Cream)
            .border(1.dp, StitchBlack.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = style.rotationDeg },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 2.1f * density, cap = StrokeCap.Round)
                val thin = Stroke(width = 1.0f * density, cap = StrokeCap.Round)
                val pad = 5f * density
                val w = size.width - pad * 2
                val h = size.height - pad * 2
                val cx = size.width / 2f
                val cy = size.height / 2f

                fun drawFrame(s: Stroke, inset: Float = 0f) {
                    val l = pad + inset
                    val t = pad + inset
                    val fw = w - inset * 2
                    val fh = h - inset * 2
                    when (style.shape) {
                        StampShape.CIRCLE -> {
                            val r = minOf(fw, fh) / 2f
                            if (isGold) drawCircle(goldBrush, r, Offset(cx, cy), style = s)
                            else drawCircle(ink, r, Offset(cx, cy), style = s)
                        }
                        StampShape.DOUBLE_RING -> {
                            val r = minOf(fw, fh) / 2f
                            if (isGold) drawCircle(goldBrush, r, Offset(cx, cy), style = s)
                            else drawCircle(ink, r, Offset(cx, cy), style = s)
                            if (inset == 0f) {
                                for (k in 0 until 16) {
                                    val ang = k * (Math.PI * 2.0 / 16.0)
                                    val r1 = r - 4f * density
                                    val r2 = r - 1.5f * density
                                    drawLine(
                                        ink.copy(alpha = 0.7f),
                                        Offset(cx + (cos(ang) * r1).toFloat(), cy + (sin(ang) * r1).toFloat()),
                                        Offset(cx + (cos(ang) * r2).toFloat(), cy + (sin(ang) * r2).toFloat()),
                                        strokeWidth = 1f * density,
                                    )
                                }
                            }
                        }
                        StampShape.OVAL -> {
                            if (isGold) drawOval(goldBrush, Offset(l, t + 6f * density), Size(fw, fh - 12f * density), style = s)
                            else drawOval(ink, Offset(l, t + 6f * density), Size(fw, fh - 12f * density), style = s)
                        }
                        StampShape.ROUNDED_RECT -> {
                            val cr = 10f * density
                            if (isGold) drawRoundRect(goldBrush, Offset(l, t), Size(fw, fh), CornerRadius(cr, cr), style = s)
                            else drawRoundRect(ink, Offset(l, t), Size(fw, fh), CornerRadius(cr, cr), style = s)
                        }
                        StampShape.OCTAGON -> {
                            val c = minOf(fw, fh) * 0.22f
                            val p = Path().apply {
                                moveTo(l + c, t); lineTo(l + fw - c, t)
                                lineTo(l + fw, t + c); lineTo(l + fw, t + fh - c)
                                lineTo(l + fw - c, t + fh); lineTo(l + c, t + fh)
                                lineTo(l, t + fh - c); lineTo(l, t + c); close()
                            }
                            if (isGold) drawPath(p, goldBrush, style = s) else drawPath(p, ink, style = s)
                        }
                        StampShape.SHIELD_ARCH -> {
                            val arch = fw * 0.34f
                            val p = Path().apply {
                                moveTo(l, t + arch)
                                quadraticTo(cx, t - arch * 0.35f, l + fw, t + arch)
                                lineTo(l + fw, t + fh - 12f * density)
                                quadraticTo(cx, t + fh + 6f * density, l, t + fh - 12f * density)
                                close()
                            }
                            if (isGold) drawPath(p, goldBrush, style = s) else drawPath(p, ink, style = s)
                        }
                    }
                }

                drawFrame(stroke, 0f)
                val innerStroke = if (style.shape == StampShape.CIRCLE || style.shape == StampShape.ROUNDED_RECT) {
                    Stroke(
                        width = 1.0f * density,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density)),
                    )
                } else thin
                drawFrame(innerStroke, 5.5f * density)

                // Schengen EU corner stars or rectangular corner brackets.
                if (style.isSchengen) {
                    val starR = 2.6f * density
                    val dx = w * 0.34f
                    val dy = h * 0.34f
                    listOf(
                        Offset(cx - dx, cy - dy),
                        Offset(cx + dx, cy - dy),
                        Offset(cx - dx, cy + dy),
                        Offset(cx + dx, cy + dy),
                    ).forEach { pt -> drawTinyStar(ink.copy(alpha = 0.72f), pt, starR) }
                }

                // Country-specific landmark or continent emblem at top of stamp.
                drawCountryEmblem(style, ink, cx, pad + 24f * density, 11.5f * density)

                // Subtle ink-wear dropouts so it reads like a stamped impression.
                if (showWear) {
                    var seed = style.wearSeed + 17
                    repeat(18) {
                        seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                        val wx = pad + (seed % 1000) / 1000f * w
                        seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                        val wy = pad + (seed % 1000) / 1000f * h
                        val wr = (0.9f + (seed % 3) * 0.5f) * density
                        drawCircle(Cream.copy(alpha = 0.70f), wr, Offset(wx, wy))
                    }
                }
            }

            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(25.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(flag, fontSize = 17.sp)
                        Text(
                            style.entryLabel,
                            fontFamily = Mono,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ink,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        country.uppercase(),
                        fontWeight = FontWeight.Black,
                        fontSize = 13.5.sp,
                        lineHeight = 15.5.sp,
                        color = ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        firstVisit.uppercase(),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = ink.copy(alpha = 0.90f),
                    )
                    Text(
                        detail,
                        fontSize = 10.sp,
                        color = ink.copy(alpha = 0.76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${style.isoCode ?: "MRY"} • ${style.mood.label}",
                    fontFamily = Mono,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink.copy(alpha = 0.62f),
                    letterSpacing = 0.9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun DrawScope.drawTinyStar(ink: Color, center: Offset, r: Float) {
    val inner = r * 0.42f
    val p = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else inner
        val ang = -Math.PI / 2.0 + i * Math.PI / 5.0
        val x = center.x + (cos(ang) * rad).toFloat()
        val y = center.y + (sin(ang) * rad).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, ink)
}

private fun DrawScope.drawCountryEmblem(style: StampStyle, ink: Color, cx: Float, cy: Float, r: Float) {
    val stroke = Stroke(width = 1.3f * density, cap = StrokeCap.Round)
    val c = Offset(cx, cy)
    when (style.emblem) {
        StampEmblem.TRIDENT -> {
            // Ukrainian Tryzub geometric crest
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx, cy + r * 0.85f)
                moveTo(cx - r * 0.72f, cy - r * 0.75f); lineTo(cx - r * 0.72f, cy + r * 0.35f)
                lineTo(cx, cy + r * 0.7f); lineTo(cx + r * 0.72f, cy + r * 0.35f)
                lineTo(cx + r * 0.72f, cy - r * 0.75f)
                moveTo(cx - r * 0.4f, cy - r * 0.1f); lineTo(cx + r * 0.4f, cy - r * 0.1f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.TORII -> {
            // Japanese Torii gate silhouette
            val p = Path().apply {
                moveTo(cx - r * 0.95f, cy - r * 0.55f); quadraticTo(cx, cy - r * 0.35f, cx + r * 0.95f, cy - r * 0.55f)
                moveTo(cx - r * 0.75f, cy - r * 0.15f); lineTo(cx + r * 0.75f, cy - r * 0.15f)
                moveTo(cx - r * 0.52f, cy - r * 0.42f); lineTo(cx - r * 0.52f, cy + r * 0.75f)
                moveTo(cx + r * 0.52f, cy - r * 0.42f); lineTo(cx + r * 0.52f, cy + r * 0.75f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.TAIPEI_SPIRE -> {
            // Tiered bamboo tower spire (Taipei 101 inspired)
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx, cy - r * 0.55f)
                moveTo(cx - r * 0.25f, cy - r * 0.55f); lineTo(cx + r * 0.25f, cy - r * 0.55f)
                lineTo(cx + r * 0.4f, cy - r * 0.05f); lineTo(cx - r * 0.4f, cy - r * 0.05f); close()
                moveTo(cx - r * 0.48f, cy - r * 0.05f); lineTo(cx + r * 0.48f, cy - r * 0.05f)
                lineTo(cx + r * 0.56f, cy + r * 0.72f); lineTo(cx - r * 0.56f, cy + r * 0.72f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.EIFFEL -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx - r * 0.18f, cy - r * 0.2f); lineTo(cx - r * 0.72f, cy + r * 0.75f)
                moveTo(cx, cy - r); lineTo(cx + r * 0.18f, cy - r * 0.2f); lineTo(cx + r * 0.72f, cy + r * 0.75f)
                moveTo(cx - r * 0.45f, cy + r * 0.25f); lineTo(cx + r * 0.45f, cy + r * 0.25f)
                moveTo(cx - r * 0.35f, cy + r * 0.75f); quadraticTo(cx, cy + r * 0.32f, cx + r * 0.35f, cy + r * 0.75f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.BIG_BEN -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r * 0.32f, cy - r * 0.45f)
                lineTo(cx + r * 0.32f, cy + r * 0.75f); lineTo(cx - r * 0.32f, cy + r * 0.75f)
                lineTo(cx - r * 0.32f, cy - r * 0.45f); close()
            }
            drawPath(p, ink, style = stroke)
            drawCircle(ink, r * 0.20f, Offset(cx, cy - r * 0.15f), style = stroke)
        }
        StampEmblem.LIBERTY_STAR -> {
            drawTinyStar(ink, Offset(cx, cy), r * 0.72f)
            drawLine(ink, Offset(cx - r, cy + r * 0.75f), Offset(cx + r, cy + r * 0.75f), strokeWidth = 1.2f * density)
        }
        StampEmblem.COLOSSEUM -> {
            val p = Path().apply {
                moveTo(cx - r * 0.85f, cy + r * 0.65f); lineTo(cx - r * 0.85f, cy - r * 0.35f)
                lineTo(cx + r * 0.15f, cy - r * 0.35f); lineTo(cx + r * 0.45f, cy + r * 0.05f)
                lineTo(cx + r * 0.85f, cy + r * 0.05f); lineTo(cx + r * 0.85f, cy + r * 0.65f); close()
            }
            drawPath(p, ink, style = stroke)
            drawCircle(ink, r * 0.16f, Offset(cx - r * 0.45f, cy + r * 0.25f), style = stroke)
            drawCircle(ink, r * 0.16f, Offset(cx, cy + r * 0.25f), style = stroke)
        }
        StampEmblem.BRANDENBURG -> {
            val top = cy - r * 0.35f
            val bot = cy + r * 0.72f
            drawRect(ink, Offset(cx - r * 0.88f, top - r * 0.28f), Size(r * 1.76f, r * 0.28f), style = stroke)
            for (k in -2..2) {
                val px = cx + k * r * 0.36f
                drawLine(ink, Offset(px, top), Offset(px, bot), strokeWidth = 1.25f * density)
            }
            drawLine(ink, Offset(cx - r * 0.95f, bot), Offset(cx + r * 0.95f, bot), strokeWidth = 1.3f * density)
        }
        StampEmblem.CROWN_SHIELD -> {
            val p = Path().apply {
                moveTo(cx - r * 0.72f, cy + r * 0.55f); lineTo(cx - r * 0.72f, cy - r * 0.45f)
                lineTo(cx - r * 0.32f, cy); lineTo(cx, cy - r * 0.65f)
                lineTo(cx + r * 0.32f, cy); lineTo(cx + r * 0.72f, cy - r * 0.45f)
                lineTo(cx + r * 0.72f, cy + r * 0.55f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.CRESCENT_BRIDGE -> {
            drawArc(ink, 200f, 140f, false, Offset(cx - r * 0.9f, cy + r * 0.05f), Size(r * 1.8f, r * 1.1f), style = stroke)
            drawCircle(ink, r * 0.32f, Offset(cx - r * 0.18f, cy - r * 0.35f), style = stroke)
            drawTinyStar(ink, Offset(cx + r * 0.32f, cy - r * 0.35f), r * 0.24f)
        }
        StampEmblem.MAPLE_LEAF -> {
            val p = Path().apply {
                moveTo(cx, cy - r * 0.85f)
                lineTo(cx + r * 0.25f, cy - r * 0.25f); lineTo(cx + r * 0.78f, cy - r * 0.35f)
                lineTo(cx + r * 0.45f, cy + r * 0.22f); lineTo(cx + r * 0.72f, cy + r * 0.42f)
                lineTo(cx, cy + r * 0.35f); lineTo(cx, cy + r * 0.85f)
                lineTo(cx, cy + r * 0.35f); lineTo(cx - r * 0.72f, cy + r * 0.42f)
                lineTo(cx - r * 0.45f, cy + r * 0.22f); lineTo(cx - r * 0.78f, cy - r * 0.35f)
                lineTo(cx - r * 0.25f, cy - r * 0.25f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.PAGODA -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r * 0.5f, cy - r * 0.4f); lineTo(cx - r * 0.5f, cy - r * 0.4f); close()
                moveTo(cx - r * 0.7f, cy + r * 0.1f); lineTo(cx + r * 0.7f, cy + r * 0.1f)
                moveTo(cx - r * 0.88f, cy + r * 0.65f); lineTo(cx + r * 0.88f, cy + r * 0.65f)
                moveTo(cx - r * 0.38f, cy - r * 0.4f); lineTo(cx - r * 0.5f, cy + r * 0.65f)
                moveTo(cx + r * 0.38f, cy - r * 0.4f); lineTo(cx + r * 0.5f, cy + r * 0.65f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.TAEGEUK_GATE -> {
            drawCircle(ink, r * 0.62f, c, style = stroke)
            drawArc(ink, 0f, 180f, false, Offset(cx - r * 0.62f, cy - r * 0.31f), Size(r * 0.62f, r * 0.62f), style = stroke)
            drawArc(ink, 180f, 180f, false, Offset(cx, cy - r * 0.31f), Size(r * 0.62f, r * 0.62f), style = stroke)
        }
        StampEmblem.PYRAMID -> {
            val p = Path().apply {
                moveTo(cx - r * 0.9f, cy + r * 0.65f); lineTo(cx - r * 0.2f, cy - r * 0.65f); lineTo(cx + r * 0.45f, cy + r * 0.65f); close()
                moveTo(cx + r * 0.1f, cy + r * 0.65f); lineTo(cx + r * 0.55f, cy - r * 0.25f); lineTo(cx + r * 0.95f, cy + r * 0.65f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.OPERA_SAILS -> {
            val p = Path().apply {
                moveTo(cx - r * 0.85f, cy + r * 0.6f); quadraticTo(cx - r * 0.6f, cy - r * 0.65f, cx - r * 0.1f, cy + r * 0.6f)
                moveTo(cx - r * 0.25f, cy + r * 0.6f); quadraticTo(cx + r * 0.05f, cy - r * 0.78f, cx + r * 0.5f, cy + r * 0.6f)
                moveTo(cx + r * 0.35f, cy + r * 0.6f); quadraticTo(cx + r * 0.62f, cy - r * 0.35f, cx + r * 0.9f, cy + r * 0.6f)
                moveTo(cx - r * 0.95f, cy + r * 0.6f); lineTo(cx + r * 0.95f, cy + r * 0.6f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.SWISS_CROSS -> {
            drawCircle(ink, r * 0.72f, c, style = stroke)
            drawLine(ink, Offset(cx, cy - r * 0.42f), Offset(cx, cy + r * 0.42f), strokeWidth = 2.4f * density)
            drawLine(ink, Offset(cx - r * 0.42f, cy), Offset(cx + r * 0.42f, cy), strokeWidth = 2.4f * density)
        }
        StampEmblem.WINDMILL -> {
            drawLine(ink, Offset(cx - r * 0.65f, cy - r * 0.65f), Offset(cx + r * 0.65f, cy + r * 0.65f), strokeWidth = 1.3f * density)
            drawLine(ink, Offset(cx - r * 0.65f, cy + r * 0.65f), Offset(cx + r * 0.65f, cy - r * 0.65f), strokeWidth = 1.3f * density)
            drawRect(ink, Offset(cx - r * 0.28f, cy), Size(r * 0.56f, r * 0.7f), style = stroke)
        }
        StampEmblem.PARTHENON -> {
            val p = Path().apply {
                moveTo(cx - r * 0.9f, cy - r * 0.28f); lineTo(cx, cy - r * 0.85f); lineTo(cx + r * 0.9f, cy - r * 0.28f); close()
                moveTo(cx - r * 0.9f, cy + r * 0.68f); lineTo(cx + r * 0.9f, cy + r * 0.68f)
            }
            drawPath(p, ink, style = stroke)
            for (k in -1..1) {
                val px = cx + k * r * 0.52f
                drawLine(ink, Offset(px, cy - r * 0.28f), Offset(px, cy + r * 0.68f), strokeWidth = 1.3f * density)
            }
        }
        StampEmblem.ALPINE_PEAKS -> {
            val p = Path().apply {
                moveTo(cx - r * 0.95f, cy + r * 0.65f); lineTo(cx - r * 0.35f, cy - r * 0.65f)
                lineTo(cx + r * 0.08f, cy + r * 0.2f); lineTo(cx + r * 0.5f, cy - r * 0.45f)
                lineTo(cx + r * 0.95f, cy + r * 0.65f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.BURJ_SPIRE -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx, cy + r * 0.72f)
                moveTo(cx - r * 0.2f, cy - r * 0.1f); lineTo(cx + r * 0.2f, cy - r * 0.1f)
                moveTo(cx - r * 0.42f, cy + r * 0.3f); lineTo(cx + r * 0.42f, cy + r * 0.3f)
                moveTo(cx - r * 0.68f, cy + r * 0.72f); lineTo(cx + r * 0.68f, cy + r * 0.72f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.SUN_ARCH -> {
            drawArc(ink, 180f, 180f, false, Offset(cx - r * 0.6f, cy - r * 0.45f), Size(r * 1.2f, r * 1.2f), style = stroke)
            drawLine(ink, Offset(cx - r * 0.92f, cy + r * 0.25f), Offset(cx + r * 0.92f, cy + r * 0.25f), strokeWidth = 1.3f * density)
            for (k in -2..2) {
                val ax = cx + k * r * 0.38f
                drawLine(ink, Offset(cx, cy - r * 0.72f), Offset(ax, cy - r * 0.55f), strokeWidth = 1.0f * density)
            }
        }
        StampEmblem.SHAMROCK -> {
            drawCircle(ink, r * 0.28f, Offset(cx, cy - r * 0.34f), style = stroke)
            drawCircle(ink, r * 0.28f, Offset(cx - r * 0.34f, cy + r * 0.14f), style = stroke)
            drawCircle(ink, r * 0.28f, Offset(cx + r * 0.34f, cy + r * 0.14f), style = stroke)
            drawLine(ink, Offset(cx, cy + r * 0.12f), Offset(cx, cy + r * 0.78f), strokeWidth = 1.3f * density)
        }
        StampEmblem.VOLCANO_WAVE -> {
            val p = Path().apply {
                moveTo(cx - r * 0.85f, cy + r * 0.45f); lineTo(cx - r * 0.22f, cy - r * 0.45f)
                lineTo(cx + r * 0.22f, cy - r * 0.45f); lineTo(cx + r * 0.85f, cy + r * 0.45f)
                moveTo(cx - r * 0.95f, cy + r * 0.65f); quadraticTo(cx, cy + r * 0.35f, cx + r * 0.95f, cy + r * 0.65f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.THREE_CROWNS -> {
            drawTinyStar(ink, Offset(cx, cy - r * 0.36f), r * 0.26f)
            drawTinyStar(ink, Offset(cx - r * 0.45f, cy + r * 0.28f), r * 0.26f)
            drawTinyStar(ink, Offset(cx + r * 0.45f, cy + r * 0.28f), r * 0.26f)
        }
        StampEmblem.TAJ_DOME -> {
            drawArc(ink, 180f, 180f, false, Offset(cx - r * 0.48f, cy - r * 0.62f), Size(r * 0.96f, r * 0.96f), style = stroke)
            drawRect(ink, Offset(cx - r * 0.56f, cy - r * 0.14f), Size(r * 1.12f, r * 0.72f), style = stroke)
            drawLine(ink, Offset(cx - r * 0.88f, cy - r * 0.35f), Offset(cx - r * 0.88f, cy + r * 0.58f), strokeWidth = 1.2f * density)
            drawLine(ink, Offset(cx + r * 0.88f, cy - r * 0.35f), Offset(cx + r * 0.88f, cy + r * 0.58f), strokeWidth = 1.2f * density)
        }
        StampEmblem.PALM_ISLAND -> {
            drawLine(ink, Offset(cx, cy + r * 0.72f), Offset(cx, cy - r * 0.12f), strokeWidth = 1.4f * density)
            val p = Path().apply {
                moveTo(cx - r * 0.75f, cy - r * 0.25f); quadraticTo(cx, cy - r * 0.55f, cx + r * 0.75f, cy - r * 0.25f)
                moveTo(cx - r * 0.55f, cy + r * 0.1f); quadraticTo(cx, cy - r * 0.28f, cx + r * 0.55f, cy + r * 0.1f)
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.CASTLE_SPIRES -> {
            val p = Path().apply {
                moveTo(cx - r * 0.72f, cy + r * 0.68f); lineTo(cx - r * 0.72f, cy - r * 0.12f)
                lineTo(cx - r * 0.5f, cy - r * 0.58f); lineTo(cx - r * 0.28f, cy - r * 0.12f)
                lineTo(cx, cy - r * 0.72f); lineTo(cx + r * 0.28f, cy - r * 0.12f)
                lineTo(cx + r * 0.5f, cy - r * 0.58f); lineTo(cx + r * 0.72f, cy - r * 0.12f)
                lineTo(cx + r * 0.72f, cy + r * 0.68f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        StampEmblem.SOUTHERN_CROSS -> {
            drawTinyStar(ink, Offset(cx, cy - r * 0.55f), r * 0.24f)
            drawTinyStar(ink, Offset(cx, cy + r * 0.55f), r * 0.24f)
            drawTinyStar(ink, Offset(cx - r * 0.52f, cy), r * 0.22f)
            drawTinyStar(ink, Offset(cx + r * 0.52f, cy - r * 0.12f), r * 0.22f)
        }
        StampEmblem.CONTINENT_FALLBACK -> drawContinentEmblem(style.continent, ink, cx, cy, r)
    }
}

private fun DrawScope.drawContinentEmblem(continent: String?, ink: Color, cx: Float, cy: Float, r: Float) {
    val stroke = Stroke(width = 1.25f * density, cap = StrokeCap.Round)
    val c = Offset(cx, cy)
    when (continent) {
        "Europe" -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r * 0.35f, cy - r * 0.35f)
                lineTo(cx + r, cy); lineTo(cx + r * 0.35f, cy + r * 0.35f)
                lineTo(cx, cy + r); lineTo(cx - r * 0.35f, cy + r * 0.35f)
                lineTo(cx - r, cy); lineTo(cx - r * 0.35f, cy - r * 0.35f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        "North America" -> {
            val p = Path().apply {
                moveTo(cx - r, cy + r * 0.6f); lineTo(cx - r * 0.35f, cy - r * 0.6f)
                lineTo(cx + r * 0.1f, cy + r * 0.25f); lineTo(cx + r * 0.5f, cy - r * 0.35f)
                lineTo(cx + r, cy + r * 0.6f)
            }
            drawPath(p, ink, style = stroke)
        }
        "South America" -> {
            val p = Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r * 0.75f, cy - r * 0.15f)
                lineTo(cx, cy + r); lineTo(cx - r * 0.65f, cy - r * 0.15f); close()
            }
            drawPath(p, ink, style = stroke)
        }
        "Asia" -> {
            drawArc(ink, 180f, 180f, false, Offset(cx - r * 0.7f, cy - r * 0.3f), Size(r * 1.4f, r * 1.4f), style = stroke)
            drawLine(ink, Offset(cx - r, cy + r * 0.4f), Offset(cx + r, cy + r * 0.4f), strokeWidth = 1.25f * density)
        }
        "Africa" -> {
            drawCircle(ink, r * 0.48f, c, style = stroke)
            for (k in 0 until 4) {
                rotate(k * 90f, c) {
                    drawLine(ink, Offset(cx, cy - r), Offset(cx, cy - r * 0.65f), strokeWidth = 1.2f * density)
                }
            }
        }
        "Oceania" -> {
            val p = Path().apply {
                moveTo(cx - r, cy - r * 0.15f)
                quadraticTo(cx - r * 0.5f, cy - r * 0.65f, cx, cy - r * 0.15f)
                quadraticTo(cx + r * 0.5f, cy + r * 0.35f, cx + r, cy - r * 0.15f)
                moveTo(cx - r * 0.8f, cy + r * 0.45f)
                quadraticTo(cx - r * 0.3f, cy, cx + r * 0.2f, cy + r * 0.45f)
                quadraticTo(cx + r * 0.6f, cy + r * 0.75f, cx + r * 0.85f, cy + r * 0.45f)
            }
            drawPath(p, ink, style = stroke)
        }
        "Antarctica" -> {
            for (k in 0 until 3) {
                rotate(k * 60f, c) {
                    drawLine(ink, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1.15f * density)
                }
            }
        }
        else -> {
            drawCircle(ink, r * 0.65f, c, style = stroke)
            drawLine(ink, Offset(cx - r * 0.65f, cy), Offset(cx + r * 0.65f, cy), strokeWidth = 1f * density)
            drawLine(ink, Offset(cx, cy - r * 0.65f), Offset(cx, cy + r * 0.65f), strokeWidth = 1f * density)
        }
    }
}

/** Legacy wrapper kept for callers that pass no explicit [StampStyle]. */
@Composable
fun VyshyvankaStamp(
    flag: String,
    country: String,
    firstVisit: String,
    detail: String,
    modifier: Modifier = Modifier,
) = PassportStamp(
    flag = flag,
    country = country,
    firstVisit = firstVisit,
    detail = detail,
    style = StampDesign.styleForCountry(country, continent = null, isEarliest = false, dayCount = 2),
    modifier = modifier,
)

/** A single row of vyshyvanka rhombus stitching, used as a decorative band. */
@Composable
fun EmbroideryBand(modifier: Modifier = Modifier) {
    val c = 4.dp
    Canvas(modifier.fillMaxWidth().height(c * rhombus.size)) {
        val cs = c.toPx()
        val total = ((size.width / (cs * (MOTIF_W + GAP))).toInt()).coerceAtLeast(1)
        var x = (size.width - total * (MOTIF_W + GAP) * cs + GAP * cs) / 2
        repeat(total) {
            rhombus.forEachIndexed { r, row ->
                row.forEachIndexed { col, ch ->
                    if (ch == '#') drawRect(
                        if (col == 3 && r == 2) StitchBlack else StitchRed,
                        Offset(x + col * cs, r * cs), Size(cs - 0.5f, cs - 0.5f),
                    )
                }
            }
            x += (MOTIF_W + GAP) * cs
        }
    }
}
