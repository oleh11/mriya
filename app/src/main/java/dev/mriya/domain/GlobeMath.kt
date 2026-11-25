package dev.mriya.domain

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** 3D lifted great-circle polyline points (`xyz` triples on or slightly above the unit sphere). */
data class ProjectedArc(val id: String, val pts: FloatArray)

fun unitVec(lat: Double, lon: Double): FloatArray {
    val la = Math.toRadians(lat)
    val lo = Math.toRadians(lon)
    return floatArrayOf(
        (cos(la) * sin(lo)).toFloat(),
        sin(la).toFloat(),
        (cos(la) * cos(lo)).toFloat(),
    )
}

fun liftedArcPoints(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, segments: Int = 48): FloatArray {
    val a = unitVec(fromLat, fromLon)
    val b = unitVec(toLat, toLon)
    val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)
    val omega = acos(dot)
    val lift = 0.04 + 0.16 * (omega / PI)
    val out = FloatArray((segments + 1) * 3)
    for (i in 0..segments) {
        val t = i.toDouble() / segments
        val s = sin(omega).takeIf { it > 1e-9 } ?: 1.0
        val k1 = if (omega < 1e-9) 1.0 else sin((1 - t) * omega) / s
        val k2 = if (omega < 1e-9) 0.0 else sin(t * omega) / s
        val h = 1.0 + lift * sin(PI * t)
        out[i * 3] = ((k1 * a[0] + k2 * b[0]) * h).toFloat()
        out[i * 3 + 1] = ((k1 * a[1] + k2 * b[1]) * h).toFloat()
        out[i * 3 + 2] = ((k1 * a[2] + k2 * b[2]) * h).toFloat()
    }
    return out
}

/** Squared Euclidean distance from point `(px, py)` to segment `(ax, ay)-(bx, by)`. */
fun pointToSegmentDistSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq <= 1e-6f) {
        val ex = px - ax
        val ey = py - ay
        return ex * ex + ey * ey
    }
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
    val qx = ax + t * dx
    val qy = ay + t * dy
    val rx = px - qx
    val ry = py - qy
    return rx * rx + ry * ry
}

/**
 * Returns the id of the closest visible flight arc within [maxDistPx] of `(tapX, tapY)`, or `null`.
 * Uses the exact orthographic projection parameters from `Globe`: `sl/cl = sin/cos(lat0)`, `so/co = sin/cos(lon0)`.
 */
fun hitTestArcs(
    tapX: Float,
    tapY: Float,
    arcs: List<ProjectedArc>,
    sl: Float,
    cl: Float,
    so: Float,
    co: Float,
    cx: Float,
    cy: Float,
    r: Float,
    maxDistPx: Float,
): String? {
    var bestId: String? = null
    var bestDistSq = maxDistPx * maxDistPx

    for (arc in arcs) {
        val pts = arc.pts
        var hasPrev = false
        var prevX = 0f
        var prevY = 0f
        var i = 0
        while (i + 2 < pts.size) {
            val vx = pts[i]
            val vy = pts[i + 1]
            val vz = pts[i + 2]
            i += 3

            val x1 = vx * co - vz * so
            val z1 = vx * so + vz * co
            val y2 = vy * cl - z1 * sl
            val z2 = vy * sl + z1 * cl
            val visible = z2 > 0f || (x1 * x1 + y2 * y2) > 1f
            if (!visible) {
                hasPrev = false
                continue
            }
            val sx = cx + x1 * r
            val sy = cy - y2 * r
            if (hasPrev) {
                val dSq = pointToSegmentDistSq(tapX, tapY, prevX, prevY, sx, sy)
                if (dSq <= bestDistSq) {
                    bestDistSq = dSq
                    bestId = arc.id
                }
            }
            prevX = sx
            prevY = sy
            hasPrev = true
        }
    }
    return bestId
}
