package dev.mriya

import dev.mriya.data.Point
import dev.mriya.data.LatLon
import dev.mriya.domain.FlightDetector
import dev.mriya.domain.haversineKm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightDetectorTest {
    private fun p(ts: Long, lat: Double, lon: Double, alt: Double? = null) =
        Point(latitude = lat, longitude = lon, timestamp = ts, altitude = alt)

    @Test
    fun `airplane-mode gap between airports is one flight`() {
        val d = FlightDetector()
        // Berlin ground points, then 2h gap, then London (~930 km)
        d.feed(p(0, 52.36, 13.50))
        d.feed(p(600, 52.36, 13.51))
        d.feed(p(600 + 7200, 51.47, -0.45))
        d.feed(p(600 + 7800, 51.47, -0.44))
        val flights = d.finish()
        assertEquals(1, flights.size)
        assertTrue(flights[0].distanceKm in 900.0..1000.0)
    }

    @Test
    fun `driving is not a flight`() {
        val d = FlightDetector()
        d.feed(p(0, 52.5, 13.4))
        d.feed(p(3600, 53.0, 14.0)) // ~70 km in 1 h
        assertEquals(0, d.finish().size)
    }

    @Test
    fun `dense track is merged into a single flight`() {
        val d = FlightDetector()
        var lon = 13.5
        var t = 0L
        d.feed(p(t, 52.36, lon))
        repeat(8) {
            t += 900; lon -= 1.5 // 15 min hops of ~100 km at ~400 km/h
            d.feed(p(t, 52.36, lon, 10000.0))
        }
        d.feed(p(t + 3600, 52.36, lon)) // landed, ground
        val flights = d.finish()
        assertEquals(1, flights.size)
    }

    @Test
    fun `short hop under threshold is ignored`() {
        val d = FlightDetector()
        d.feed(p(0, 50.0, 8.0))
        d.feed(p(1800, 50.5, 9.0)) // ~80 km
        assertEquals(0, d.finish().size)
    }
}

class FlightDetectorAirportTest {
    private fun p(ts: Long, lat: Double, lon: Double) = Point(latitude = lat, longitude = lon, timestamp = ts)
    private val airports = mapOf(LatLon(50.03, 8.57) to "FRA", LatLon(48.35, 11.78) to "MUC")
    private val near: (LatLon) -> String? = { q -> airports.entries.firstOrNull { haversineKm(q, it.key) < 25 }?.value }

    @Test
    fun `slow door-to-door short hop is a flight when both ends are airports`() {
        // Frankfurt -> Munich ~300 km with 2.5 h of airport dwell: ~135 km/h door to door.
        val d = FlightDetector(airportNear = near)
        d.feed(p(0, 50.03, 8.57)); d.feed(p(100, 50.03, 8.57))
        d.feed(p(100 + 8000, 48.35, 11.78)); d.feed(p(8200, 48.35, 11.78))
        assertEquals(1, d.finish().size)
    }

    @Test
    fun `same slow leg without airports is not a flight`() {
        val d = FlightDetector()
        d.feed(p(0, 50.03, 8.57)); d.feed(p(9100, 48.35, 11.78))
        assertEquals(0, d.finish().size)
    }

    @Test
    fun `single point teleport is discarded`() {
        val d = FlightDetector()
        d.feed(p(0, 52.5, 13.4))
        d.feed(p(300, 50.0, 17.0))   // glitch ~330 km away
        d.feed(p(900, 52.5, 13.41))  // snaps back
        assertEquals(0, d.finish().size)
    }

    @Test
    fun `phone off for days around a long flight is still found and back-dated`() {
        val tpe = LatLon(25.08, 121.23); val ord = LatLon(41.98, -87.9)
        val two = mapOf(tpe to "TPE", ord to "ORD")
        val d = FlightDetector(airportNear = { q -> two.entries.firstOrNull { haversineKm(q, it.key) < 25 }?.value })
        d.feed(p(0, 25.08, 121.23))
        d.feed(p(61 * 86400L, 41.98, -87.9)) // first point 61 days later, after landing
        val f = d.finish()
        assertEquals(1, f.size)
        val hours = (f[0].arriveTs - f[0].departTs) / 3600.0
        assertTrue("flight time should be a plausible cruise estimate, was $hours h", hours in 10.0..25.0)
    }

    @Test
    fun `a long hop without airports at both ends is not invented`() {
        val d = FlightDetector()
        d.feed(p(0, 25.08, 121.23)); d.feed(p(61 * 86400L, 41.98, -87.9))
        assertEquals(0, d.finish().size)
    }

    @Test
    fun `airport dwell is not counted as flight time`() {
        val fra = LatLon(50.03, 8.57); val muc = LatLon(48.35, 11.78)
        val m = mapOf(fra to "FRA", muc to "MUC")
        val d = FlightDetector(airportNear = { q -> m.entries.firstOrNull { haversineKm(q, it.key) < 25 }?.value })
        d.feed(p(0, 50.03, 8.57)); d.feed(p(8640L, 48.35, 11.78)) // 2.4 h door to door (~125 km/h) for a 300 km hop
        val f = d.finish()
        assertEquals(1, f.size)
        val h = (f[0].arriveTs - f[0].departTs) / 3600.0
        assertTrue("expected a ~0.9 h estimate, was $h h", h < 1.5)
    }
}
