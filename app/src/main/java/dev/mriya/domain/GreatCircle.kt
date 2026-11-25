package dev.mriya.domain

import dev.mriya.data.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Points along the shortest path between [a] and [b], as lines of [lon, lat] pairs.
 * The path is split wherever it crosses the antimeridian so renderers don't draw a line across the whole map.
 * (MapLibre interpolates between vertices in map space, so the curve must be sampled here.)
 */
fun greatCircleLines(a: LatLon, b: LatLon, segments: Int = 64): List<List<DoubleArray>> {
    fun vec(p: LatLon): DoubleArray {
        val la = Math.toRadians(p.lat); val lo = Math.toRadians(p.lon)
        return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    }
    val va = vec(a); val vb = vec(b)
    val dot = (va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0)
    val omega = kotlin.math.acos(dot)
    val lines = mutableListOf<MutableList<DoubleArray>>(mutableListOf())
    var prevLon: Double? = null
    for (i in 0..segments) {
        val t = i.toDouble() / segments
        val p = if (omega < 1e-9) va else {
            val s = sin(omega)
            val k1 = sin((1 - t) * omega) / s; val k2 = sin(t * omega) / s
            doubleArrayOf(k1 * va[0] + k2 * vb[0], k1 * va[1] + k2 * vb[1], k1 * va[2] + k2 * vb[2])
        }
        val lat = Math.toDegrees(atan2(p[2], sqrt(p[0] * p[0] + p[1] * p[1])))
        val lon = Math.toDegrees(atan2(p[1], p[0]))
        if (prevLon != null && abs(lon - prevLon) > 180) lines.add(mutableListOf())
        lines.last() += doubleArrayOf(lon, lat)
        prevLon = lon
    }
    return lines.filter { it.size >= 2 }
}
