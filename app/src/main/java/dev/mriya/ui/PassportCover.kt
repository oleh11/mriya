package dev.mriya.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GoldBright = Color(0xFFF6DC92)
private val GoldMid = Color(0xFFE0B85A)
private val GoldDeep = Color(0xFFA87B24)

/**
 * Midnight-navy & gold-foil biometric passport hardcover featuring an embossed Antonov An-225 Mriya
 * + Ukrainian vyshyvanka emblem with tilt-reactive metallic sheen. Reads [tiltProvider] strictly
 * inside Canvas draw phase.
 */
@Composable
fun PassportCoverCard(
    year: Int?,
    countryCount: Int,
    flightCount: Int,
    tiltProvider: () -> Offset,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 28.dp, bottomEnd = 28.dp)
    val diamond = remember { Path() }
    val mriyaFoil = remember { Path() }
    val rayDiamond = remember { Path() }
    var lastMedallionW = remember { 0f }
    var lastMedallionH = remember { 0f }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp)
            .clip(coverShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1A3B), Color(0xFF132B5E), Color(0xFF0E214A), Color(0xFF091530))
                )
            )
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Open Mriya passport" },
    ) {
        // Booklet surface: right page-edge stack, left spine binding + stitching, embossed double
        // gold border, corner vyshyvanka diamonds, and tilt-reactive specular sheen.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val tilt = tiltProvider()

            // Staggered cream page edges tucked along the right booklet rim.
            val edgeX1 = w - 3.5f * density
            val edgeX2 = w - 5.5f * density
            drawLine(
                color = Color(0x38F6EFE0),
                start = Offset(edgeX1, 28f * density),
                end = Offset(edgeX1, h - 28f * density),
                strokeWidth = 1.6f * density,
            )
            drawLine(
                color = Color(0x24F6EFE0),
                start = Offset(edgeX2, 34f * density),
                end = Offset(edgeX2, h - 34f * density),
                strokeWidth = 1.1f * density,
            )

            // Left spine shadow + recessed vertical crease & stitching.
            drawRect(
                brush = Brush.horizontalGradient(
                    0.00f to Color(0x70030814),
                    0.55f to Color(0x28030814),
                    1.00f to Color.Transparent,
                    startX = 0f,
                    endX = 22f * density,
                ),
                size = Size(22f * density, h),
            )
            val creaseX = 14f * density
            drawLine(
                color = Color(0x45030712),
                start = Offset(creaseX, 0f),
                end = Offset(creaseX, h),
                strokeWidth = 1.8f * density,
            )
            drawLine(
                color = GoldMid.copy(alpha = 0.24f),
                start = Offset(creaseX + 2.2f * density, 16f * density),
                end = Offset(creaseX + 2.2f * density, h - 16f * density),
                strokeWidth = 0.9f * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density)),
            )

            // Embossed double gold-foil inner border.
            val shiftX = tilt.x * w * 0.22f
            val shiftY = tilt.y * h * 0.18f
            val foilBorderBrush = Brush.linearGradient(
                listOf(GoldDeep, GoldBright, GoldMid, GoldBright, GoldDeep),
                start = Offset(shiftX, shiftY),
                end = Offset(w + shiftX, h + shiftY),
            )
            val leftInset = 24f * density
            val rightInset = 18f * density
            val vertInset = 14f * density
            drawRoundRect(
                brush = foilBorderBrush,
                topLeft = Offset(leftInset, vertInset),
                size = Size(w - leftInset - rightInset, h - vertInset * 2f),
                cornerRadius = CornerRadius(18f * density, 18f * density),
                style = Stroke(width = 1.4f * density),
            )
            drawRoundRect(
                color = GoldMid.copy(alpha = 0.42f),
                topLeft = Offset(leftInset + 4f * density, vertInset + 4f * density),
                size = Size(w - leftInset - rightInset - 8f * density, h - (vertInset + 4f * density) * 2f),
                cornerRadius = CornerRadius(14f * density, 14f * density),
                style = Stroke(width = 0.7f * density),
            )

            // Corner vyshyvanka diamond accents inside the outer frame.
            val corners = listOf(
                Offset(leftInset + 13f * density, vertInset + 13f * density),
                Offset(w - rightInset - 13f * density, vertInset + 13f * density),
                Offset(leftInset + 13f * density, h - vertInset - 13f * density),
                Offset(w - rightInset - 13f * density, h - vertInset - 13f * density),
            )
            val dr = 4.2f * density
            for (c in corners) {
                diamond.reset()
                diamond.moveTo(c.x, c.y - dr)
                diamond.lineTo(c.x + dr, c.y)
                diamond.lineTo(c.x, c.y + dr)
                diamond.lineTo(c.x - dr, c.y)
                diamond.close()
                drawPath(diamond, GoldBright.copy(alpha = 0.65f), style = Stroke(width = 0.9f * density))
                drawCircle(GoldBright.copy(alpha = 0.72f), radius = 1.1f * density, center = c)
            }

            // Tilt-reactive diagonal metallic gold specular sheen over the dark leather cover.
            val sheenX = tilt.x * w * 0.42f
            val sheenY = tilt.y * h * 0.30f
            drawRect(
                Brush.linearGradient(
                    0.00f to Color.Transparent,
                    0.34f to Color(0x0EE8C675),
                    0.48f to Color(0x22FFF3C4),
                    0.60f to Color(0x12E0B85A),
                    1.00f to Color.Transparent,
                    start = Offset(-w * 0.2f + sheenX, -h * 0.1f + sheenY),
                    end = Offset(w * 1.2f + sheenX, h * 1.1f + sheenY),
                )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 30.dp, end = 24.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top passport title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "MRIYA  •  МРІЯ",
                    color = GoldBright,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "TRAVEL PASSPORT  •  ПАСПОРТ",
                    color = GoldMid.copy(alpha = 0.85f),
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(0.7f))

            // Center embossed gold-foil An-225 & vyshyvanka crest medallion
            Canvas(Modifier.size(134.dp)) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas
                val tilt = tiltProvider()
                val cx = w / 2f
                val cy = h / 2f
                val r = minOf(w, h) / 2f

                if (w != lastMedallionW || h != lastMedallionH) {
                    lastMedallionW = w
                    lastMedallionH = h
                    buildAn225Path(mriyaFoil, cx, cy, unit = r * 0.088f)
                }

                val foilShiftX = tilt.x * r * 0.7f
                val foilShiftY = tilt.y * r * 0.7f
                val goldFoil = Brush.linearGradient(
                    listOf(Color(0xFFFFF3C4), Color(0xFFE8C675), Color(0xFFB5842A), Color(0xFFF4DC97)),
                    start = Offset(cx - r + foilShiftX, cy - r + foilShiftY),
                    end = Offset(cx + r + foilShiftX, cy + r + foilShiftY),
                )

                // Soft warm radial halo behind crest.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x2AE8C675), Color.Transparent),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )

                // Outer concentric compass & security rings.
                drawCircle(goldFoil, radius = r - 3f * density, center = center, style = Stroke(1.6f * density))
                drawCircle(GoldMid.copy(alpha = 0.55f), radius = r - 8f * density, center = center, style = Stroke(0.8f * density))
                drawCircle(
                    color = GoldBright.copy(alpha = 0.50f),
                    radius = r * 0.66f,
                    center = center,
                    style = Stroke(0.9f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density))),
                )

                // 8 vyshyvanka diamond rays around the compass perimeter.
                for (i in 0 until 8) {
                    val deg = i * 45f
                    val rad = deg * (PI.toFloat() / 180f)
                    val tipR = r - 10f * density
                    val innerR = r * 0.72f
                    val midR = (tipR + innerR) / 2f
                    val halfW = 3.2f * density
                    val rx = cx + cos(rad) * tipR
                    val ry = cy + sin(rad) * tipR
                    val ix = cx + cos(rad) * innerR
                    val iy = cy + sin(rad) * innerR
                    val perpX = -sin(rad) * halfW
                    val perpY = cos(rad) * halfW
                    val mx = cx + cos(rad) * midR
                    val my = cy + sin(rad) * midR

                    rayDiamond.reset()
                    rayDiamond.moveTo(rx, ry)
                    rayDiamond.lineTo(mx + perpX, my + perpY)
                    rayDiamond.lineTo(ix, iy)
                    rayDiamond.lineTo(mx - perpX, my - perpY)
                    rayDiamond.close()
                    drawPath(rayDiamond, GoldBright.copy(alpha = if (i % 2 == 0) 0.72f else 0.42f))
                }

                // Climbing Antonov An-225 Mriya embossed in metallic gold foil.
                rotate(-30f, center) {
                    drawPath(mriyaFoil, goldFoil)
                    drawPath(mriyaFoil, Color(0x664A3208), style = Stroke(0.7f * density))
                }
            }

            Spacer(Modifier.weight(0.7f))

            // Bottom edition info, ICAO chip & open prompt
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${year ?: "ALL-TIME"} EDITION",
                    color = GoldBright,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "$countryCount ${if (countryCount == 1) "COUNTRY" else "COUNTRIES"}  •  $flightCount ${if (flightCount == 1) "FLIGHT" else "FLIGHTS"}",
                    color = GoldMid.copy(alpha = 0.80f),
                    fontFamily = Mono,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(10.dp))
                BiometricChipIcon(GoldBright.copy(alpha = 0.88f), Modifier.size(width = 34.dp, height = 22.dp))
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = CircleShape,
                    color = GoldMid.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, GoldBright.copy(alpha = 0.48f)),
                ) {
                    Text(
                        "Tap to open passport",
                        color = GoldBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.weight(0.9f))
        }
    }
}
