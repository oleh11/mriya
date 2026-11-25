package dev.mriya.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.R
import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.domain.CountryShape
import dev.mriya.domain.CountryShapes
import dev.mriya.domain.pointInRing
import dev.mriya.domain.airspaceToAvoid
import dev.mriya.domain.routeLinesAvoidingAirspace
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val Ink = Color(0xFF1C1B4B)
private val Purple = Color(0xFF5B3FD0)
private val Magenta = Color(0xFFC04FD8)

private const val COLS = 130
private const val TOP = 84f
private const val BOTTOM = -58f

private class Dot(val col: Int, val row: Int, val iso: String?)

/** Rasterises country outlines into a halftone dot grid once; reused by every card. */
private suspend fun buildDots(shapes: List<CountryShape>): List<Dot> = withContext(Dispatchers.Default) {
    val step = 360f / COLS
    val rows = ((TOP - BOTTOM) / step).toInt()
    val out = ArrayList<Dot>(4000)
    val taken = HashSet<Int>()
    for (s in shapes) for (ring in s.rings) {
        val minLon = ring.minOf { it.first }; val maxLon = ring.maxOf { it.first }
        val minLat = ring.minOf { it.second }; val maxLat = ring.maxOf { it.second }
        val c0 = ((minLon + 180f) / step).toInt().coerceAtLeast(0)
        val c1 = ((maxLon + 180f) / step).toInt().coerceAtMost(COLS - 1)
        val r0 = ((TOP - maxLat) / step).toInt().coerceAtLeast(0)
        val r1 = ((TOP - minLat) / step).toInt().coerceAtMost(rows - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val lon = -180f + (c + 0.5f) * step
            val lat = TOP - (r + 0.5f) * step
            if (taken.contains(r * COLS + c)) continue
            if (pointInRing(ring, lon, lat)) { taken += r * COLS + c; out += Dot(c, r, s.iso) }
        }
    }
    out
}

private class DotAssets(val dots: List<Dot>, val ruShape: CountryShape?)

/** Dot grid & airspace exclusion shape are loaded once per process on a background thread. */
private object DotCache {
    @Volatile private var cache: DotAssets? = null
    suspend fun get(ctx: android.content.Context): DotAssets = cache ?: CountryShapes.get(ctx).let { shapes ->
        DotAssets(buildDots(shapes), shapes.firstOrNull { it.iso == "RU" })
    }.also { cache = it }
}

/** Halftone dot-matrix world with curved flight arcs avoiding Russian airspace, like a printed passport page. */
@Composable
fun DotMap(visitedIso: Set<String>, flights: List<Flight>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val assets by produceState<DotAssets?>(null) { value = DotCache.get(ctx) }
    val dots = assets?.dots.orEmpty()
    val ruShape = assets?.ruShape
    val flightLines by produceState(emptyList<List<DoubleArray>>(), flights, ruShape) {
        value = withContext(Dispatchers.Default) {
            flights.flatMap { routeLinesAvoidingAirspace(it.from, it.to, airspaceToAvoid(it.departTs, ruShape), segmentsPerLeg = 24) }
        }
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(360f / (TOP - BOTTOM))) {
        val step = size.width / COLS
        val r = step * 0.34f
        fun x(lon: Double) = ((lon + 180.0) / 360.0 * size.width).toFloat()
        fun y(lat: Double) = ((TOP - lat) / (TOP - BOTTOM) * size.height).toFloat()
        dots.forEach { d ->
            val cx = (d.col + 0.5f) * step
            val cy = (d.row + 0.5f) * step
            val visited = d.iso != null && d.iso in visitedIso
            if (visited) drawCircle(lerp(Magenta, Purple, d.col / COLS.toFloat()), r * 1.25f, Offset(cx, cy))
            else drawCircle(Color.White.copy(alpha = 0.62f), r, Offset(cx, cy))
        }
        val nodes = HashSet<Pair<Float, Float>>()
        val path = Path()
        flightLines.forEach { line ->
            line.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt[0]), y(pt[1])) else path.lineTo(x(pt[0]), y(pt[1])) }
        }
        flights.forEach { f ->
            nodes += x(f.from.lon) to y(f.from.lat); nodes += x(f.to.lon) to y(f.to.lat)
        }
        drawPath(path, Purple.copy(alpha = 0.85f), style = Stroke(width = 2.2f * density, cap = StrokeCap.Round))
        nodes.forEach { (nx, ny) ->
            drawCircle(Color.White, 4.6f * density, Offset(nx, ny))
            drawCircle(Purple, 3.2f * density, Offset(nx, ny))
        }
    }
}

private fun flag(iso: String?): String =
    if (iso == null || iso.length != 2) "🌐"
    else iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

private fun pad(s: String, n: Int = 44) = (s.uppercase().take(n)).padEnd(n, '<')

/**
 * The shareable yearly passport page. [year] null = all time.
 * Flight time is estimated from departure/arrival timestamps, so it includes airport time when the phone was offline.
 */
@Composable
fun PassportCard(
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    modifier: Modifier = Modifier,
    tiltProvider: (() -> Offset)? = null,
) {
    val zone = ZoneId.systemDefault()
    val yFlights = if (year == null) flights else flights.filter { Instant.ofEpochSecond(it.departTs).atZone(zone).year == year }
    val yStamps = if (year == null) stamps else stamps.filter { s -> s.days.any { it.startsWith("$year-") } }
    val km = yFlights.sumOf { it.distanceKm }
    val seconds = yFlights.sumOf { it.arriveTs - it.departTs }
    val airports = yFlights.flatMap { listOfNotNull(it.fromAirport, it.toAirport) }.toSet()
    val origin = yFlights.mapNotNull { it.fromAirport }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—"
    val since = stamps.minOfOrNull { it.firstTs }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
    val fmt = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)
    val issued = LocalDate.now().format(fmt).uppercase()
    val title = (year?.toString() ?: "ALL-TIME") + " MRIYA PASSPORT"
    val hh = seconds / 3600; val mm = (seconds % 3600) / 60

    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
            Brush.verticalGradient(listOf(Color(0xFFB9D7FF), Color(0xFFD3CBFF), Color(0xFFEBCBF3), Color(0xFFD9E4FF)))
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            EmbroideryBand(Modifier.padding(top = 14.dp))
            DotMap(yStamps.mapNotNull { it.iso2 }.toSet(), yFlights, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.Center) {
                yStamps.sortedBy { it.firstTs }.take(12).forEach { Text(flag(it.iso2), fontSize = 18.sp, modifier = Modifier.padding(horizontal = 2.dp)) }
            }
            Column(Modifier.background(Color.White.copy(alpha = 0.38f)).padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Ink, fontWeight = FontWeight.Black, fontSize = 19.sp, letterSpacing = 0.5.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("PASSPORT • PASS • TRAVEL RECORD", color = Ink.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 10.sp)
                    }
                    if (tiltProvider != null) {
                        KinegramSeal(tiltProvider, Modifier.size(46.dp))
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_vyshyvanka_compass),
                            contentDescription = "Mriya compass seal",
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(0.9f)) {
                        Text("${yFlights.size}", color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 58.sp)
                        Text(if (yFlights.size == 1) "flight" else "flights", color = Ink.copy(alpha = 0.8f), fontSize = 28.sp, fontWeight = FontWeight.Light)
                    }
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Meta("Authority", "Mriya App")
                        Meta("Place of issue", origin)
                        Meta("Date of issue", issued)
                        Meta("Member since", since?.format(fmt)?.uppercase() ?: "—")
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Distance", "${"%,d".format(km.roundToInt())} km")
                    Stat("Flight time", "${hh}h ${"%02d".format(mm)}m")
                    Stat("Airports", "${airports.size}")
                    Stat("Countries", "${yStamps.size}")
                }
            }
            val since8 = since?.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH))?.uppercase() ?: ""
            PassportMrzStrip(
                line1 = pad("${year ?: "ALL"}<<<TRAVELER<<<<<<MEMBER$since8<<"),
                line2 = pad("ISSUED${issued.replace(" ", "")}<<$origin<<<<<<<MRIYA.APP<<<<"),
            )
        }
        if (tiltProvider != null) {
            HolographicOverlay(tiltProvider, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append("$label "); pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(value); pop()
        },
        color = Ink.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 15.sp,
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Ink.copy(alpha = 0.75f), fontSize = 11.sp)
        Text(value, color = Ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
