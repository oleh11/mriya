package dev.mriya.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.mriya.data.Flight
import dev.mriya.data.LiveAircraft
import dev.mriya.domain.AirportDb
import dev.mriya.domain.CountryShape
import dev.mriya.domain.CountryShapes
import dev.mriya.domain.ProjectedArc
import dev.mriya.domain.hitTestArcs
import dev.mriya.domain.imports.MOSKVA_MESSAGE
import dev.mriya.domain.imports.MOSKVA_WRECK
import dev.mriya.domain.liftedRoutePoints
import dev.mriya.domain.pointInRing
import dev.mriya.domain.airspaceToAvoid
import dev.mriya.domain.cachedRouteAvoidingAirspace
import dev.mriya.domain.unitVec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native 3D globe: orthographic projection drawn on a Compose Canvas.
 * Drag to rotate, pinch to zoom, tap a country. Works fully offline; no map tiles, no WebView, no network.
 * Unit vectors: x = cos(lat)sin(lon), y = sin(lat), z = cos(lat)cos(lon).
 */
private class GShape(
    val iso: String?,
    val rings: List<FloatArray>,           // xyz unit vectors per vertex
    val lonLat: List<List<Pair<Float, Float>>>,
    val cx: Float, val cy: Float, val cz: Float,
    val radius: Float,                      // angular radius of the bounding cap, radians
)

private data class GlobeAssets(val shapes: List<GShape>, val ruShape: CountryShape?)

private fun arcFor(f: Flight, ruShape: CountryShape?, n: Int = 64): ProjectedArc =
    ProjectedArc(f.id, liftedRoutePoints(cachedRouteAvoidingAirspace(f.from, f.to, airspaceToAvoid(f.departTs, ruShape)), n))

/** Prepared sphere geometry, built once per process on a background thread (not on every tab switch). */
private object GlobeCache {
    @Volatile private var assets: GlobeAssets? = null
    suspend fun get(ctx: android.content.Context): GlobeAssets = assets ?: withContext(Dispatchers.Default) {
        assets ?: CountryShapes.get(ctx).let { raw ->
            GlobeAssets(prepare(raw), raw.firstOrNull { it.iso == "RU" })
        }.also { assets = it }
    }
}

private fun prepare(shapes: List<CountryShape>): List<GShape> = shapes.map { s ->
    val rings = s.rings.map { ring ->
        FloatArray(ring.size * 3).also { a ->
            ring.forEachIndexed { i, (lon, lat) ->
                val v = unitVec(lat.toDouble(), lon.toDouble())
                a[i * 3] = v[0]; a[i * 3 + 1] = v[1]; a[i * 3 + 2] = v[2]
            }
        }
    }
    var sx = 0.0; var sy = 0.0; var sz = 0.0
    rings.forEach { r -> for (i in 0 until r.size / 3) { sx += r[i * 3]; sy += r[i * 3 + 1]; sz += r[i * 3 + 2] } }
    val n = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-9)
    val cx = (sx / n).toFloat(); val cy = (sy / n).toFloat(); val cz = (sz / n).toFloat()
    var maxAng = 0f
    rings.forEach { r -> for (i in 0 until r.size / 3) {
        val d = (cx * r[i * 3] + cy * r[i * 3 + 1] + cz * r[i * 3 + 2]).coerceIn(-1f, 1f)
        maxAng = max(maxAng, acos(d))
    } }
    GShape(s.iso, rings, s.rings, cx, cy, cz, maxAng)
}

@Composable
fun Globe(
    visitedIso: Set<String>,
    flights: List<Flight>,
    selectedIso: String?,
    onCountryTap: (iso: String?, name: String?) -> Unit,
    modifier: Modifier = Modifier,
    selectedFlightId: String? = null,
    onFlightTap: (Flight?) -> Unit = {},
    liveAircraft: LiveAircraft? = null,
    hideRussia: Boolean = false,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val assets by produceState<GlobeAssets?>(null) { value = GlobeCache.get(ctx) }
    val shapes = assets?.shapes.orEmpty()
    val ruShape = assets?.ruShape
    val airportDb by produceState<AirportDb?>(null) { value = AirportDb.get(ctx) }
    val visibleShapes = remember(shapes, hideRussia) {
        if (hideRussia) shapes.filter { it.iso != "RU" } else shapes
    }
    val visibleVisitedIso = remember(visitedIso, hideRussia) {
        if (hideRussia) visitedIso - "RU" else visitedIso
    }
    val live = remember(flights) { flights.filter { !it.dismissed } }
    val displayLive = remember(live, hideRussia, airportDb) {
        val db = airportDb
        if (!hideRussia || db == null) {
            live
        } else {
            live.map { f ->
                val fromIsRu = f.fromAirport?.let { db.byIata(it)?.country.equals("RU", ignoreCase = true) } == true
                val toIsRu = f.toAirport?.let { db.byIata(it)?.country.equals("RU", ignoreCase = true) } == true
                if (!fromIsRu && !toIsRu) f
                else f.copy(
                    from = if (fromIsRu) MOSKVA_WRECK else f.from,
                    to = if (toIsRu) MOSKVA_WRECK else f.to,
                )
            }
        }
    }
    // Detours are computed on a background thread (and cached per route), never while the map is opening.
    val arcs by produceState(emptyList<ProjectedArc>(), displayLive, ruShape) {
        value = withContext(Dispatchers.Default) { displayLive.map { arcFor(it, ruShape) } }
    }
    // Airport endpoints as unit vectors, computed once instead of every frame.
    val nodes = remember(displayLive) {
        val seen = HashSet<Pair<Int, Int>>(); val out = ArrayList<Float>()
        displayLive.forEach { f -> listOf(f.from, f.to).forEach { p ->
            if (seen.add((p.lat * 20).toInt() to (p.lon * 20).toInt())) { val v = unitVec(p.lat, p.lon); out += v[0]; out += v[1]; out += v[2] }
        } }
        out.toFloatArray()
    }
    val base = remember { Path() }; val vis = remember { Path() }; val sel = remember { Path() }
    val ringPath = remember { Path() }; val arcPath = remember { Path() }; val selArcPath = remember { Path() }
    val brushes = remember { GlobeBrushes() }
    val tapCb by rememberUpdatedState(onCountryTap)
    val flightTapCb by rememberUpdatedState(onFlightTap)
    val liveState by rememberUpdatedState(displayLive)
    val arcsState by rememberUpdatedState(arcs)

    // Start looking at the latest flight's destination, or Europe.
    var lat0 by remember { mutableFloatStateOf(displayLive.maxByOrNull { it.arriveTs }?.to?.lat?.toFloat()?.let { Math.toRadians(it.toDouble()).toFloat() } ?: 0.6f) }
    var lon0 by remember { mutableFloatStateOf(displayLive.maxByOrNull { it.arriveTs }?.to?.lon?.toFloat()?.let { Math.toRadians(it.toDouble()).toFloat() } ?: 0.25f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var touched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Center once on a newly tracked live aircraft without fighting manual globe panning.
    LaunchedEffect(liveAircraft?.callsign) {
        val ac = liveAircraft ?: return@LaunchedEffect
        touched = true
        lat0 = Math.toRadians(ac.lat).toFloat().coerceIn(-1.4f, 1.4f)
        lon0 = Math.toRadians(ac.lon).toFloat()
    }

    // Gentle intro spin that stops on first touch or after a few seconds (saves battery).
    LaunchedEffect(Unit) {
        var last = 0L; var elapsed = 0f
        while (!touched && elapsed < 6f) {
            withFrameNanos { t ->
                if (last != 0L) { val dt = (t - last) / 1e9f; lon0 += dt * 0.12f; elapsed += dt }
                last = t
            }
        }
    }

    val ocean = if (dark) listOf(Color(0xFF123248), Color(0xFF071826)) else listOf(Color(0xFFCFE3FF), Color(0xFF8DB4EE))
    val land = if (dark) Color(0xFF3A4757) else Color(0xFFF6F8FB)
    val border = if (dark) Color(0xFF55657A) else Color(0xFFB9C6D8)
    val purple = Color(0xFF6B4EE6)
    val arcColor = Color(0xFFFFB74D)

    Canvas(
        modifier.fillMaxSize()
            .semantics {
                contentDescription =
                    "Interactive 3D globe showing ${visibleVisitedIso.size} visited countries and ${displayLive.size} flight routes. Use the visited countries button to browse by text."
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    touched = true
                    tracker.resetTracking()
                    var multi = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) multi = true
                        val r = min(size.width, size.height) / 2f * 0.86f * zoom
                        val pan = ev.calculatePan()
                        lon0 -= pan.x / r
                        lat0 = (lat0 + pan.y / r).coerceIn(-1.4f, 1.4f)
                        zoom = (zoom * ev.calculateZoom()).coerceIn(0.8f, 3.5f)
                        ev.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (ev.changes.any { it.pressed })
                    if (!multi) {
                        // Momentum: keep spinning with the release velocity and ease out.
                        val v = tracker.calculateVelocity()
                        val r = min(size.width, size.height) / 2f * 0.86f * zoom
                        fling = scope.launch {
                            var vx = v.x; var vy = v.y; var last = 0L
                            while (kotlin.math.abs(vx) + kotlin.math.abs(vy) > 40f) {
                                withFrameNanos { t ->
                                    if (last != 0L) {
                                        val dt = (t - last) / 1e9f
                                        lon0 -= vx * dt / r
                                        lat0 = (lat0 + vy * dt / r).coerceIn(-1.4f, 1.4f)
                                        val k = kotlin.math.exp(-2.6f * dt)
                                        vx *= k; vy *= k
                                    }
                                    last = t
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(visibleShapes, hideRussia) {
                detectTapGestures { p ->
                    touched = true
                    val cxp = size.width / 2f; val cyp = size.height / 2f
                    val r = min(size.width, size.height) / 2f * 0.86f * zoom
                    val hitArcId = hitTestArcs(
                        tapX = p.x, tapY = p.y, arcs = arcsState,
                        sl = sin(lat0), cl = cos(lat0), so = sin(lon0), co = cos(lon0),
                        cx = cxp, cy = cyp, r = r, maxDistPx = 16f * density,
                    )
                    if (hitArcId != null) {
                        val hitFlight = liveState.firstOrNull { it.id == hitArcId }
                        if (hitFlight != null) {
                            flightTapCb(hitFlight)
                            tapCb(null, null)
                            return@detectTapGestures
                        }
                    }
                    flightTapCb(null)
                    val x = (p.x - cxp) / r
                    val y = -(p.y - cyp) / r
                    val d2 = x * x + y * y
                    if (d2 > 1f) { tapCb(null, null); return@detectTapGestures }
                    val z = sqrt(1f - d2)
                    val z1 = z * cos(lat0) - y * sin(lat0)
                    val vy = y * cos(lat0) + z * sin(lat0)
                    val vx = x * cos(lon0) + z1 * sin(lon0)
                    val vz = -x * sin(lon0) + z1 * cos(lon0)
                    val lat = Math.toDegrees(asin(vy.toDouble().coerceIn(-1.0, 1.0))).toFloat()
                    var lon = Math.toDegrees(atan2(vx.toDouble(), vz.toDouble())).toFloat()
                    val hit = visibleShapes.firstOrNull { s -> s.lonLat.any { ring -> pointInRing(ring, lon, lat) } }
                    val iso = hit?.iso?.takeIf { it.length == 2 }
                    if (hit == null && hideRussia && shapes.any { it.iso == "RU" && it.lonLat.any { ring -> pointInRing(ring, lon, lat) } }) {
                        tapCb("MOSKVA", MOSKVA_MESSAGE)
                    } else {
                        tapCb(iso, iso?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) })
                    }
                }
            }
    ) {
        val cxp = size.width / 2f; val cyp = size.height / 2f
        val R = min(size.width, size.height) / 2f * 0.86f * zoom
        val c = Offset(cxp, cyp)
        val sl = sin(lat0); val cl = cos(lat0); val so = sin(lon0); val co = cos(lon0)

        // Atmosphere glow, then the ocean sphere.
        brushes.update(cxp, cyp, R, dark, ocean, purple)
        drawCircle(brushes.atmosphere!!, R * 1.22f, c)
        drawCircle(brushes.ocean!!, R, c)

        // Countries, batched into three paths (base / visited / selected).
        base.reset(); vis.reset(); sel.reset()
        for (s in visibleShapes) {
            val dzv = s.cy * sl + (s.cx * so + s.cz * co) * cl // depth of the centroid
            if (s.radius < 1.5708f && dzv < -sin(s.radius)) continue
            val target = when { s.iso != null && s.iso == selectedIso -> sel; s.iso != null && s.iso in visibleVisitedIso -> vis; else -> base }
            for (ring in s.rings) {
                var any = false
                ringPath.reset()
                for (i in 0 until ring.size / 3) {
                    val vx = ring[i * 3]; val vy = ring[i * 3 + 1]; val vz = ring[i * 3 + 2]
                    val x1 = vx * co - vz * so
                    val z1 = vx * so + vz * co
                    val y2 = vy * cl - z1 * sl
                    val z2 = vy * sl + z1 * cl
                    var px = x1; var py = y2
                    if (z2 < 0f) { val l = hypot(px, py).coerceAtLeast(1e-4f); px /= l; py /= l } else any = true
                    val sx = cxp + px * R; val sy = cyp - py * R
                    if (i == 0) ringPath.moveTo(sx, sy) else ringPath.lineTo(sx, sy)
                }
                ringPath.close()
                // Rings entirely behind the globe would collapse onto the limb as stray slivers: skip them.
                if (any) target.addPath(ringPath)
            }
        }
        drawPath(base, land)
        drawPath(base, border, style = Stroke(0.6f * density))
        drawPath(vis, purple)
        drawPath(vis, Color.White.copy(alpha = 0.35f), style = Stroke(0.7f * density))
        drawPath(sel, Color(0xFFFF6FB5))
        drawPath(sel, Color.White, style = Stroke(1.6f * density))

        // Flight arcs, lifted above the surface and hidden when behind the globe.
        val glow = Stroke(5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val core = Stroke(2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        arcPath.reset(); selArcPath.reset()
        for (a in arcs) {
            val targetArc = if (a.id == selectedFlightId) selArcPath else arcPath
            var pen = false
            for (i in 0 until a.pts.size / 3) {
                val vx = a.pts[i * 3]; val vy = a.pts[i * 3 + 1]; val vz = a.pts[i * 3 + 2]
                val x1 = vx * co - vz * so
                val z1 = vx * so + vz * co
                val y2 = vy * cl - z1 * sl
                val z2 = vy * sl + z1 * cl
                val visible = z2 > 0f || (x1 * x1 + y2 * y2) > 1f
                if (!visible) { pen = false; continue }
                val sx = cxp + x1 * R; val sy = cyp - y2 * R
                if (!pen) { targetArc.moveTo(sx, sy); pen = true } else targetArc.lineTo(sx, sy)
            }
        }
        drawPath(arcPath, arcColor.copy(alpha = 0.28f), style = glow)
        drawPath(arcPath, arcColor, style = core)
        if (selectedFlightId != null) {
            val selGlow = Stroke(8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val selCore = Stroke(3.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(selArcPath, Color(0xFFFF6FB5).copy(alpha = 0.48f), style = selGlow)
            drawPath(selArcPath, Color.White, style = selCore)
        }

        // Airport nodes at arc endpoints (surface level).
        var ni = 0
        while (ni < nodes.size) {
            val vx = nodes[ni]; val vy = nodes[ni + 1]; val vz = nodes[ni + 2]; ni += 3
            val x1 = vx * co - vz * so
            val z1 = vx * so + vz * co
            val y2 = vy * cl - z1 * sl
            val z2 = vy * sl + z1 * cl
            if (z2 > 0.02f) {
                val o = Offset(cxp + x1 * R, cyp - y2 * R)
                drawCircle(Color.White, 4.4f * density, o)
                drawCircle(purple, 3f * density, o)
            }
        }

        // Live ADS-B aircraft marker when active.
        liveAircraft?.let { ac ->
            val v = unitVec(ac.lat, ac.lon)
            val lift = 1.035f
            val vx = v[0] * lift; val vy = v[1] * lift; val vz = v[2] * lift
            val x1 = vx * co - vz * so
            val z1 = vx * so + vz * co
            val y2 = vy * cl - z1 * sl
            val z2 = vy * sl + z1 * cl
            if (z2 > 0f) {
                val o = Offset(cxp + x1 * R, cyp - y2 * R)
                drawCircle(Color(0x4016B364), 16f * density, o)
                drawCircle(Color.White, 7.5f * density, o)
                drawCircle(Color(0xFF16B364), 5.8f * density, o)
                val hdg = ac.headingDeg ?: 0f
                rotate(hdg, o) {
                    val s = 6f * density
                    val p = Path().apply {
                        moveTo(o.x, o.y - s * 1.1f)
                        lineTo(o.x + s * 0.72f, o.y + s * 0.75f)
                        lineTo(o.x, o.y + s * 0.35f)
                        lineTo(o.x - s * 0.72f, o.y + s * 0.75f)
                        close()
                    }
                    drawPath(p, Color.White)
                }
            }
        }

        // Lighting: soft highlight top-left, darkening toward the limb, thin rim.
        drawCircle(brushes.light!!, R, c)
        drawCircle(Color.White.copy(alpha = if (dark) 0.12f else 0.55f), R, c, style = Stroke(1.2f * density))
    }
}

/** Gradient brushes depend only on size and theme: rebuild them when those change, not every frame. */
private class GlobeBrushes {
    var atmosphere: Brush? = null; var ocean: Brush? = null; var light: Brush? = null
    private var key: List<Any>? = null

    fun update(cx: Float, cy: Float, r: Float, dark: Boolean, oceanColors: List<Color>, purple: Color) {
        val k = listOf(cx, cy, r, dark)
        if (k == key) return
        key = k
        atmosphere = Brush.radialGradient(listOf(purple.copy(alpha = 0.30f), Color.Transparent), Offset(cx, cy), r * 1.22f)
        ocean = Brush.radialGradient(oceanColors, Offset(cx - r * 0.3f, cy - r * 0.35f), r * 1.5f)
        light = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.22f), 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = if (dark) 0.45f else 0.22f),
            center = Offset(cx - r * 0.35f, cy - r * 0.4f), radius = r * 1.45f,
        )
    }
}
