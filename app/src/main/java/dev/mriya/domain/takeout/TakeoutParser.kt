package dev.mriya.domain.takeout

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.time.Instant
import java.time.OffsetDateTime

/** Receives what the parser finds. Points are positions with a time; flights are legs Google itself labelled FLYING. */
interface TakeoutSink {
    fun point(ts: Long, lat: Double, lon: Double, altitude: Double?)
    fun flight(startTs: Long, endTs: Long, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double)
}

/**
 * Reads Google location history exports without loading them whole (they can be gigabytes). Handles:
 * - legacy `Records.json` (`locations` with latitudeE7/longitudeE7),
 * - the on-device Timeline export (`semanticSegments`, `rawSignals`, or a bare array of segments),
 * - monthly `Semantic Location History` files (`timelineObjects` with placeVisit / activitySegment).
 * Google has changed these formats several times, so unknown shapes are skipped, never fatal.
 */
object TakeoutParser {
    private val latLng = Regex("""(-?\d+(?:\.\d+)?)\s*°?\s*,\s*(-?\d+(?:\.\d+)?)\s*°?""")
    private val maxAccuracyM = 2_000

    /** Streams the top-level structure, handing each big-array element to [handle] one at a time. */
    fun stream(reader: JsonReader, sink: TakeoutSink, onElement: () -> Unit = {}) {
        reader.strictness = Strictness.LENIENT
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> elements(reader) { handle(it, sink); onElement() }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name in ARRAYS && reader.peek() == JsonToken.BEGIN_ARRAY) elements(reader) { handle(it, sink); onElement() }
                    else reader.skipValue()
                }
                reader.endObject()
            }
            else -> {}
        }
    }

    private val ARRAYS = setOf("locations", "semanticSegments", "timelineObjects", "rawSignals")

    private fun elements(reader: JsonReader, each: (JsonObject) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            val el = JsonParser.parseReader(reader)
            if (el.isJsonObject) each(el.asJsonObject)
        }
        reader.endArray()
    }

    /** One element of any of the arrays above. The shape is recognised by its keys. */
    fun handle(o: JsonObject, sink: TakeoutSink) {
        runCatching {
            when {
                o.has("latitudeE7") -> legacyRecord(o, sink)
                o.has("placeVisit") || o.has("activitySegment") -> semanticObject(o, sink)
                o.has("timelinePath") || o.has("visit") || o.has("activity") -> segment(o, sink)
                o.has("position") -> signal(o, sink)
                else -> {}
            }
        } // a malformed element must not abort a multi-gigabyte import
    }

    // ---- shapes -----------------------------------------------------------------------------

    private fun legacyRecord(o: JsonObject, sink: TakeoutSink) {
        val acc = o.get("accuracy")?.takeIf { it.isJsonPrimitive }?.asInt
        if (acc != null && acc > maxAccuracyM) return
        val ts = time(o.get("timestamp")) ?: time(o.get("timestampMs")) ?: return
        sink.point(ts, o.get("latitudeE7").asDouble / 1e7, o.get("longitudeE7").asDouble / 1e7, o.get("altitude")?.takeIf { it.isJsonPrimitive }?.asDouble)
    }

    private fun segment(o: JsonObject, sink: TakeoutSink) {
        val start = time(o.get("startTime")); val end = time(o.get("endTime"))
        o.getAsJsonArray("timelinePath")?.forEach { p ->
            val po = p.asJsonObject
            val ll = latLng(po.get("point")?.asString); val t = time(po.get("time"))
            if (ll != null && t != null) sink.point(t, ll.first, ll.second, null)
        }
        o.getAsJsonObject("visit")?.getAsJsonObject("topCandidate")?.getAsJsonObject("placeLocation")?.get("latLng")?.asString?.let(::latLng)?.let { ll ->
            start?.let { sink.point(it, ll.first, ll.second, null) }; end?.let { sink.point(it, ll.first, ll.second, null) }
        }
        o.getAsJsonObject("activity")?.let { a ->
            val from = latLng(a.getAsJsonObject("start")?.get("latLng")?.asString)
            val to = latLng(a.getAsJsonObject("end")?.get("latLng")?.asString)
            if (from != null && start != null) sink.point(start, from.first, from.second, null)
            if (to != null && end != null) sink.point(end, to.first, to.second, null)
            val type = a.getAsJsonObject("topCandidate")?.get("type")?.asString
            if (type.equals("FLYING", true) && from != null && to != null && start != null && end != null)
                sink.flight(start, end, from.first, from.second, to.first, to.second)
        }
    }

    private fun semanticObject(o: JsonObject, sink: TakeoutSink) {
        o.getAsJsonObject("placeVisit")?.let { v ->
            val loc = e7(v.getAsJsonObject("location"), "latitudeE7", "longitudeE7") ?: return@let
            val d = v.getAsJsonObject("duration")
            time(d?.get("startTimestamp") ?: d?.get("startTimestampMs"))?.let { sink.point(it, loc.first, loc.second, null) }
            time(d?.get("endTimestamp") ?: d?.get("endTimestampMs"))?.let { sink.point(it, loc.first, loc.second, null) }
        }
        o.getAsJsonObject("activitySegment")?.let { a ->
            val d = a.getAsJsonObject("duration")
            val st = time(d?.get("startTimestamp") ?: d?.get("startTimestampMs")); val en = time(d?.get("endTimestamp") ?: d?.get("endTimestampMs"))
            val from = e7(a.getAsJsonObject("startLocation"), "latitudeE7", "longitudeE7")
            val to = e7(a.getAsJsonObject("endLocation"), "latitudeE7", "longitudeE7")
            if (from != null && st != null) sink.point(st, from.first, from.second, null)
            if (to != null && en != null) sink.point(en, to.first, to.second, null)
            a.getAsJsonObject("simplifiedRawPath")?.getAsJsonArray("points")?.forEach { p ->
                val po = p.asJsonObject
                val ll = e7(po, "latE7", "lngE7"); val t = time(po.get("timestamp") ?: po.get("timestampMs"))
                if (ll != null && t != null) sink.point(t, ll.first, ll.second, null)
            }
            if (a.get("activityType")?.asString.equals("FLYING", true) && from != null && to != null && st != null && en != null)
                sink.flight(st, en, from.first, from.second, to.first, to.second)
        }
    }

    private fun signal(o: JsonObject, sink: TakeoutSink) {
        val p = o.getAsJsonObject("position") ?: return
        val acc = p.get("accuracyMeters")?.takeIf { it.isJsonPrimitive }?.asDouble
        if (acc != null && acc > maxAccuracyM) return
        val ll = latLng(p.get("LatLng")?.asString) ?: return
        sink.point(time(p.get("timestamp")) ?: return, ll.first, ll.second, null)
    }

    // ---- helpers ----------------------------------------------------------------------------

    fun latLng(s: String?): Pair<Double, Double>? {
        val m = latLng.find(s ?: return null) ?: return null
        val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun e7(o: JsonObject?, latKey: String, lonKey: String): Pair<Double, Double>? {
        o ?: return null
        val la = o.get(latKey)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val lo = o.get(lonKey)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        return la / 1e7 to lo / 1e7
    }

    /** ISO-8601 (with or without offset) or epoch seconds / milliseconds, as seconds. */
    fun time(e: JsonElement?): Long? {
        if (e == null || !e.isJsonPrimitive) return null
        val s = e.asString.trim()
        s.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000 else it }
        return runCatching { OffsetDateTime.parse(s).toInstant().epochSecond }.getOrNull()
            ?: runCatching { Instant.parse(s).epochSecond }.getOrNull()
    }
}
