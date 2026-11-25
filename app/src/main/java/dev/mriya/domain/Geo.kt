package dev.mriya.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outer rings of one country as (lon, lat) pairs. */
class CountryShape(val iso: String?, val rings: List<List<Pair<Float, Float>>>) {
    private val boxes: List<FloatArray> = rings.map { r ->
        floatArrayOf(r.minOf { it.first }, r.maxOf { it.first }, r.minOf { it.second }, r.maxOf { it.second })
    }

    fun contains(lon: Float, lat: Float): Boolean {
        for (i in rings.indices) {
            val b = boxes[i]
            if (lon < b[0] || lon > b[1] || lat < b[2] || lat > b[3]) continue
            if (pointInRing(rings[i], lon, lat)) return true
        }
        return false
    }
}

fun pointInRing(ring: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val (xi, yi) = ring[i]; val (xj, yj) = ring[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** Parsed once per process, off the main thread, then shared by sync, the globe and the dot map. */
object CountryShapes {
    @Volatile private var cache: List<CountryShape>? = null

    suspend fun get(ctx: Context): List<CountryShape> = cache ?: withContext(Dispatchers.IO) {
        cache ?: parse(ctx.applicationContext).also { cache = it }
    }

    private fun parse(ctx: Context): List<CountryShape> {
        val text = ctx.assets.open("countries.json").bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            CountryShape(
                o["iso"]?.jsonPrimitive?.content,
                o["rings"]!!.jsonArray.map { ring ->
                    ring.jsonArray.map { pt -> pt.jsonArray[0].jsonPrimitive.double.toFloat() to pt.jsonArray[1].jsonPrimitive.double.toFloat() }
                },
            )
        }
    }
}

/** Resolves a point to an ISO-2 country code with the bundled polygons (works even if Dawarich has no reverse geocoding). */
class OfflineCountryLookup(private val shapes: List<CountryShape>) {
    private var last: CountryShape? = null
    private val cells = HashMap<Long, String?>()

    fun isoAt(lat: Double, lon: Double): String? {
        val key = ((lat * 20).toLong() shl 32) xor (lon * 20).toLong().and(0xFFFFFFFFL)
        cells[key]?.let { return it }
        if (cells.containsKey(key)) return null
        val x = lon.toFloat(); val y = lat.toFloat()
        val hit = last?.takeIf { it.contains(x, y) } ?: shapes.firstOrNull { it.contains(x, y) }
        last = hit ?: last
        val iso = hit?.iso?.takeIf { it.length == 2 }
        cells[key] = iso
        return iso
    }
}
