package dev.mriya.takeout

import com.google.gson.stream.JsonReader
import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.PassportData
import dev.mriya.data.TakeoutData
import dev.mriya.data.UserData
import dev.mriya.data.stableKey
import dev.mriya.domain.applyUserData
import dev.mriya.domain.haversineKm
import dev.mriya.domain.takeout.TakeoutEngine
import dev.mriya.domain.takeout.TakeoutParser
import dev.mriya.domain.takeout.TakeoutSink
import dev.mriya.domain.takeout.mergeTakeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class TakeoutTest {
    private class Rec : TakeoutSink {
        val points = mutableListOf<Triple<Long, Double, Double>>()
        val flights = mutableListOf<List<Double>>()
        override fun point(ts: Long, lat: Double, lon: Double, altitude: Double?) { points += Triple(ts, lat, lon) }
        override fun flight(startTs: Long, endTs: Long, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
            flights += listOf(startTs.toDouble(), endTs.toDouble(), fromLat, fromLon, toLat, toLon)
        }
    }

    private fun parse(json: String) = Rec().also { TakeoutParser.stream(JsonReader(StringReader(json)), it) }

    @Test
    fun `legacy Records json drops coarse fixes and reads ISO and millisecond times`() {
        val r = parse("""{"locations":[
            {"latitudeE7":419786000,"longitudeE7":-879048000,"accuracy":20,"timestamp":"2019-08-19T10:00:00.000Z"},
            {"latitudeE7":419786000,"longitudeE7":-879048000,"accuracy":5000,"timestamp":"2019-08-19T10:05:00.000Z"},
            {"latitudeE7":500300000,"longitudeE7":85700000,"timestampMs":"1566210000000"}]}""")
        assertEquals(2, r.points.size)
        assertEquals(41.9786, r.points[0].second, 1e-6); assertEquals(-87.9048, r.points[0].third, 1e-6)
        assertEquals(1566210000L, r.points[1].first)
    }

    @Test
    fun `new Timeline export gives points and a flight for FLYING activities`() {
        val r = parse("""{"semanticSegments":[
            {"startTime":"2025-03-12T08:30:00.000-05:00","endTime":"2025-03-12T11:05:00.000-07:00",
             "activity":{"start":{"latLng":"41.9786°, -87.9048°"},"end":{"latLng":"37.6213°, -122.379°"},"topCandidate":{"type":"FLYING"}}},
            {"startTime":"2025-03-13T08:00:00.000Z","endTime":"2025-03-13T09:00:00.000Z",
             "timelinePath":[{"point":"37.7°, -122.4°","time":"2025-03-13T08:10:00.000Z"},{"point":"37.8°, -122.5°","time":"2025-03-13T08:40:00.000Z"}]},
            {"startTime":"2025-03-13T10:00:00.000Z","endTime":"2025-03-13T12:00:00.000Z",
             "visit":{"topCandidate":{"placeLocation":{"latLng":"37.75°, -122.45°"}}}}],
          "rawSignals":[{"position":{"LatLng":"37.7°, -122.4°","accuracyMeters":30,"timestamp":"2025-03-13T08:00:00.000Z"}}]}""")
        assertEquals(1, r.flights.size)
        assertEquals(41.9786, r.flights[0][2], 1e-6); assertEquals(-122.379, r.flights[0][5], 1e-6)
        assertEquals("activity ends 2 + path 2 + visit 2 + raw signal 1", 7, r.points.size)
    }

    @Test
    fun `monthly Semantic Location History is read`() {
        val r = parse("""{"timelineObjects":[
            {"placeVisit":{"location":{"latitudeE7":481000000,"longitudeE7":115000000},"duration":{"startTimestamp":"2020-01-03T10:00:00Z","endTimestamp":"2020-01-03T12:00:00Z"}}},
            {"activitySegment":{"startLocation":{"latitudeE7":481000000,"longitudeE7":115000000},"endLocation":{"latitudeE7":504000000,"longitudeE7":305000000},
              "duration":{"startTimestamp":"2020-01-03T13:00:00Z","endTimestamp":"2020-01-03T15:00:00Z"},"activityType":"FLYING"}}]}""")
        assertEquals(1, r.flights.size)
        assertEquals(4, r.points.size) // visit start and end, activity start and end
    }

    @Test
    fun `a bare array of segments and unknown shapes do not break the parse`() {
        val r = parse("""[{"startTime":"2025-03-13T10:00:00.000Z","endTime":"2025-03-13T12:00:00.000Z","visit":{"topCandidate":{"placeLocation":{"latLng":"37.75°, -122.45°"}}}},
            {"somethingNew":true},{"position":{"LatLng":"not a position","timestamp":"x"}}]""")
        assertEquals(2, r.points.size)
    }

    // ---- engine ---------------------------------------------------------------------------

    private val berlin = LatLon(52.36, 13.50); private val london = LatLon(51.47, -0.45)
    private fun near(p: LatLon) = listOf(berlin to "BER", london to "LHR").firstOrNull { haversineKm(p, it.first) < 25 }?.second
    private fun iso(lat: Double, lon: Double) = if (lon > 5) "DE" else "GB"
    private fun airportCountry(p: LatLon) = near(p)?.let { if (it == "BER") "DE" else "GB" }

    @Test
    fun `engine finds a flight and both countries from points alone`() {
        val e = TakeoutEngine(::iso, ::near, ::airportCountry)
        e.beginFile()
        e.point(0, 52.36, 13.50, null); e.point(600, 52.36, 13.51, null)
        e.point(600 + 7200, 51.47, -0.45, null); e.point(600 + 7800, 51.47, -0.44, null)
        e.endFile()
        val r = e.result(1)
        assertEquals(1, r.flights.size)
        assertEquals("BER", r.flights[0].fromAirport); assertEquals("LHR", r.flights[0].toAirport); assertEquals("takeout", r.flights[0].source)
        assertEquals(setOf("DE", "GB"), r.stamps.keys)
        assertEquals(4L, r.points)
    }

    @Test
    fun `a FLYING label and the detector do not double count the same flight`() {
        val e = TakeoutEngine(::iso, ::near, ::airportCountry)
        e.beginFile()
        e.point(0, 52.36, 13.50, null)
        e.point(7800, 51.47, -0.45, null)
        e.flight(600, 7800, berlin.lat, berlin.lon, london.lat, london.lon)
        e.endFile()
        assertEquals(1, e.result(1).flights.size)
    }

    @Test
    fun `a flight alone, with no points in the destination, still stamps that country`() {
        val e = TakeoutEngine(::iso, ::near, ::airportCountry)
        e.beginFile(); e.flight(0, 7200, berlin.lat, berlin.lon, london.lat, london.lon); e.endFile()
        assertEquals(setOf("DE", "GB"), e.result(1).stamps.keys)
    }

    // ---- merge ----------------------------------------------------------------------------

    private fun stamp(iso: String, first: Long, last: Long, vararg days: String) = CountryStamp(iso, iso, first, last, days.toSet(), emptySet())
    private fun flight(from: String, to: String, ts: Long) = Flight(berlin, london, ts, ts + 7200, 900.0, fromAirport = from, toAirport = to)

    @Test
    fun `takeout stamps are united and duplicate flights are skipped`() {
        val d = PassportData(stamps = mapOf("DE" to stamp("DE", 100, 200, "2024-01-01")), flights = listOf(flight("BER", "LHR", 1_000_000)))
        val t = TakeoutData(
            stamps = mapOf("DE" to stamp("DE", 50, 150, "2023-05-05"), "FR" to stamp("FR", 10, 20, "2022-02-02")),
            flights = listOf(flight("BER", "LHR", 1_000_000 + 3600).copy(source = "takeout"), flight("LHR", "BER", 9_000_000).copy(source = "takeout")),
        )
        val m = mergeTakeout(d, t)
        assertEquals(setOf("DE", "FR"), m.stamps.keys)
        assertEquals(setOf("2024-01-01", "2023-05-05"), m.stamps.getValue("DE").days)
        assertEquals(50L, m.stamps.getValue("DE").firstTs); assertEquals(200L, m.stamps.getValue("DE").lastTs)
        assertEquals(2, m.flights.size)
    }

    @Test
    fun `user removals still hide takeout stamps and dismiss takeout flights across re-imports`() {
        val tf = flight("LHR", "BER", 9_000_000).copy(source = "takeout")
        val t = TakeoutData(
            stamps = mapOf("FR" to stamp("FR", 10, 20, "2022-02-02"), "DE" to stamp("DE", 50, 150, "2023-05-05")),
            flights = listOf(tf),
        )
        val ud = UserData(hiddenCountries = setOf("FR"), dismissedFlights = setOf(tf.stableKey()))
        val viewed = applyUserData(mergeTakeout(PassportData(), t), ud)
        assertEquals(setOf("DE"), viewed.stamps.keys)
        assertTrue(viewed.flights.single().dismissed)
    }
}
