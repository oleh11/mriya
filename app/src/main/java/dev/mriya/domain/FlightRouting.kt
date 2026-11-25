package dev.mriya.domain

import dev.mriya.data.LatLon
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Curated open-airspace bypass hubs modeled on real post-2022 international routing outside
 * Russian airspace (Baltic/Nordic, Black Sea–Caucasus–Central Asia, and North Pacific/Aleutian).
 */
private val bypassHubs = listOf(
    // Baltic / Kaliningrad / Nordic bypass
    LatLon(56.0, 17.5),
    LatLon(52.5, 23.5),
    LatLon(65.0, 14.0),
    // Black Sea -> Caucasus -> Central Asia -> China corridor (strictly south of RU)
    LatLon(41.0, 32.0),
    LatLon(40.3, 49.8),
    LatLon(40.5, 60.0),
    LatLon(41.5, 72.0),
    LatLon(40.5, 86.0),
    LatLon(38.0, 108.0),
    // North Atlantic / Polar / Alaska / North Pacific corridor
    LatLon(74.0, 10.0),
    LatLon(78.0, -40.0),
    LatLon(72.0, -135.0),
    LatLon(60.5, -152.0),
    LatLon(52.0, -172.0),
    LatLon(43.0, 160.0),
    LatLon(36.0, 144.0),
)

/**
 * 24 Feb 2022, 00:00 UTC. From then on airlines from most countries stopped crossing Russian airspace; flights before that
 * date really did fly over it, so they must be drawn as they were flown.
 */
const val RUSSIA_AIRSPACE_CLOSED_TS = 1_645_660_800L

/** The airspace to route around for a flight departing at [departTs], or null if it flew the direct route. */
fun airspaceToAvoid(departTs: Long, ru: CountryShape?): CountryShape? = if (departTs >= RUSSIA_AIRSPACE_CLOSED_TS) ru else null

private val routeCache = ConcurrentHashMap<String, List<LatLon>>()

/**
 * [routeAvoidingAirspace] with a cache: many flights repeat the same airports, and every detour costs a few
 * milliseconds. Keys round to about 100 m. Safe to call from several threads.
 */
fun cachedRouteAvoidingAirspace(from: LatLon, to: LatLon, avoid: CountryShape?): List<LatLon> {
    if (avoid == null) return listOf(from, to)
    val key = String.format(Locale.ROOT, "%s|%.3f,%.3f>%.3f,%.3f", avoid.iso, from.lat, from.lon, to.lat, to.lon)
    return routeCache.getOrPut(key) { routeAvoidingAirspace(from, to, avoid) }
}

private fun toCart(p: LatLon): DoubleArray {
    val la = Math.toRadians(p.lat)
    val lo = Math.toRadians(p.lon)
    return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
}

private fun slerpLatLon(va: DoubleArray, vb: DoubleArray, omega: Double, t: Double): LatLon {
    val p = if (omega < 1e-9) {
        va
    } else {
        val s = sin(omega)
        val k1 = sin((1.0 - t) * omega) / s
        val k2 = sin(t * omega) / s
        doubleArrayOf(k1 * va[0] + k2 * vb[0], k1 * va[1] + k2 * vb[1], k1 * va[2] + k2 * vb[2])
    }
    val lat = Math.toDegrees(atan2(p[2], sqrt(p[0] * p[0] + p[1] * p[1])))
    val lon = Math.toDegrees(atan2(p[1], p[0]))
    return LatLon(lat, lon)
}

/** Checks [lon]/[lat] against [avoid] with a small `0.6°` border buffer so routes do not graze the edge. */
fun isInAirspace(lon: Double, lat: Double, avoid: CountryShape): Boolean {
    val x = lon.toFloat()
    val y = lat.toFloat()
    val buf = 0.6f
    return avoid.contains(x, y) ||
        avoid.contains(x + buf, y) ||
        avoid.contains(x - buf, y) ||
        avoid.contains(x, y + buf) ||
        avoid.contains(x, y - buf)
}

/** Returns true if the spherical geodesic from [a] to [b] enters [avoid] airspace. */
fun segmentEntersAirspace(a: LatLon, b: LatLon, avoid: CountryShape, samples: Int = 40): Boolean {
    val va = toCart(a)
    val vb = toCart(b)
    val dot = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0)
    val omega = acos(dot)
    for (i in 1 until samples) {
        val pt = slerpLatLon(va, vb, omega, i.toDouble() / samples)
        if (isInAirspace(pt.lon, pt.lat, avoid)) return true
    }
    return false
}

/**
 * Computes a shortest-distance polyline of [LatLon] waypoints from [from] to [to] that avoids [avoid].
 * Returns `listOf(from, to)` directly when the great-circle path is already clear or when an endpoint
 * itself lies inside [avoid].
 */
fun routeAvoidingAirspace(from: LatLon, to: LatLon, avoid: CountryShape?): List<LatLon> {
    if (avoid == null) return listOf(from, to)
    if (avoid.contains(from.lon.toFloat(), from.lat.toFloat()) ||
        avoid.contains(to.lon.toFloat(), to.lat.toFloat())
    ) {
        return listOf(from, to)
    }
    if (!segmentEntersAirspace(from, to, avoid)) {
        return listOf(from, to)
    }

    val nodes = ArrayList<LatLon>(2 + bypassHubs.size).apply {
        add(from)
        addAll(bypassHubs)
        add(to)
    }
    val target = nodes.lastIndex
    val n = nodes.size

    val dist = DoubleArray(n) { Double.POSITIVE_INFINITY }
    val prev = IntArray(n) { -1 }
    val visited = BooleanArray(n)
    val pq = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })

    dist[0] = 0.0
    pq.add(0 to 0.0)

    while (pq.isNotEmpty()) {
        val (u, d) = pq.poll() ?: break
        if (visited[u]) continue
        visited[u] = true
        if (u == target) break
        if (d > dist[u]) continue

        for (v in 0 until n) {
            if (visited[v] || u == v) continue
            if (u == 0 && v == target) continue // already tested direct great-circle above
            if (segmentEntersAirspace(nodes[u], nodes[v], avoid)) continue
            val nd = d + haversineKm(nodes[u], nodes[v])
            if (nd < dist[v]) {
                dist[v] = nd
                prev[v] = u
                pq.add(v to nd)
            }
        }
    }

    if (prev[target] == -1) return listOf(from, to)

    val rawPath = ArrayList<LatLon>()
    var cur = target
    while (cur != -1) {
        rawPath += nodes[cur]
        cur = prev[cur]
    }
    rawPath.reverse()
    return smoothWaypoints(rawPath, avoid)
}

private fun smoothWaypoints(path: List<LatLon>, avoid: CountryShape): List<LatLon> {
    if (path.size <= 2) return path
    val out = ArrayList<LatLon>(path.size * 2)
    out += path.first()
    for (i in 1 until path.lastIndex) {
        val prev = path[i - 1]
        val curr = path[i]
        val next = path[i + 1]
        val q1 = interpPoint(prev, curr, 0.72)
        val q2 = interpPoint(curr, next, 0.28)
        if (!segmentEntersAirspace(q1, q2, avoid, samples = 20)) {
            out += q1
            out += q2
        } else {
            out += curr
        }
    }
    out += path.last()
    return out
}

private fun interpPoint(a: LatLon, b: LatLon, t: Double): LatLon {
    val va = toCart(a)
    val vb = toCart(b)
    val dot = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0)
    return slerpLatLon(va, vb, acos(dot), t)
}

/**
 * Builds 2D map polylines (`[lon, lat]` pairs split cleanly at antimeridian crossings) between [a] and [b],
 * detouring around [avoid] whenever needed.
 */
fun routeLinesAvoidingAirspace(
    a: LatLon,
    b: LatLon,
    avoid: CountryShape?,
    segmentsPerLeg: Int = 28,
): List<List<DoubleArray>> {
    val waypoints = cachedRouteAvoidingAirspace(a, b, avoid)
    if (waypoints.size <= 2) return greatCircleLines(a, b, segmentsPerLeg * 2)

    val lines = mutableListOf<MutableList<DoubleArray>>(mutableListOf())
    var prevLon: Double? = null
    for (leg in 0 until waypoints.lastIndex) {
        val va = toCart(waypoints[leg])
        val vb = toCart(waypoints[leg + 1])
        val dot = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0)
        val omega = acos(dot)
        val startIdx = if (leg == 0) 0 else 1
        for (i in startIdx..segmentsPerLeg) {
            val pt = slerpLatLon(va, vb, omega, i.toDouble() / segmentsPerLeg)
            if (prevLon != null && abs(pt.lon - prevLon) > 180.0) {
                lines.add(mutableListOf())
            }
            lines.last() += doubleArrayOf(pt.lon, pt.lat)
            prevLon = pt.lon
        }
    }
    return lines.filter { it.size >= 2 }
}

/**
 * Computes lifted 3D globe coordinates (`xyz` triples matching `GlobeMath.unitVec`) along [waypoints],
 * rising in a single continuous arch from start to finish without dipping at intermediate turns.
 */
fun liftedRoutePoints(waypoints: List<LatLon>, totalSegments: Int = 64): FloatArray {
    if (waypoints.size <= 2) {
        val a = waypoints.firstOrNull() ?: LatLon(0.0, 0.0)
        val b = waypoints.lastOrNull() ?: a
        return liftedArcPoints(a.lat, a.lon, b.lat, b.lon, totalSegments)
    }

    val legCount = waypoints.size - 1
    val legAngles = DoubleArray(legCount)
    var totalAngle = 0.0
    for (i in 0 until legCount) {
        val va = toCart(waypoints[i])
        val vb = toCart(waypoints[i + 1])
        val dot = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0)
        legAngles[i] = acos(dot)
        totalAngle += legAngles[i]
    }
    if (totalAngle < 1e-9) {
        return liftedArcPoints(waypoints.first().lat, waypoints.first().lon, waypoints.last().lat, waypoints.last().lon, totalSegments)
    }

    val legSegs = IntArray(legCount) { i ->
        ((legAngles[i] / totalAngle) * totalSegments).roundToInt().coerceAtLeast(2)
    }
    val actualTotalSegs = legSegs.sum()
    val lift = 0.04 + 0.16 * (totalAngle / PI).coerceAtMost(1.0)
    val out = FloatArray((actualTotalSegs + 1) * 3)

    var outIdx = 0
    var segSoFar = 0
    for (leg in 0 until legCount) {
        val va = toCart(waypoints[leg])
        val vb = toCart(waypoints[leg + 1])
        val omega = legAngles[leg]
        val segs = legSegs[leg]
        val startIdx = if (leg == 0) 0 else 1
        for (i in startIdx..segs) {
            val pt = slerpLatLon(va, vb, omega, i.toDouble() / segs)
            val v = unitVec(pt.lat, pt.lon)
            val globalT = segSoFar.toDouble() / actualTotalSegs
            val h = (1.0 + lift * sin(PI * globalT)).toFloat()
            out[outIdx++] = v[0] * h
            out[outIdx++] = v[1] * h
            out[outIdx++] = v[2] * h
            segSoFar++
        }
    }
    return out
}
