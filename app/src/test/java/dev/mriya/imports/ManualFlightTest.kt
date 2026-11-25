package dev.mriya.imports

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.domain.Airport
import dev.mriya.domain.imports.MOSKVA_WRECK
import dev.mriya.domain.imports.ManualFlightInput
import dev.mriya.domain.imports.ManualFlightResult
import dev.mriya.domain.imports.filterFlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ManualFlightTest {

    private val airports = mapOf(
        "ORD" to Airport("ORD", "Chicago O'Hare", 41.9786, -87.9048, "US", 2),
        "SFO" to Airport("SFO", "San Francisco", 37.6189, -122.3750, "US", 2),
        "WAW" to Airport("WAW", "Warsaw Chopin", 52.1657, 20.9671, "PL", 2),
        "SVO" to Airport("SVO", "Sheremetyevo International Airport", 55.9726, 37.4146, "RU", 2),
        "SIP" to Airport("SIP", "Simferopol International Airport", 45.0522, 33.9751, "UA", 2),
    )

    @Test
    fun `valid manual input builds flight with uppercase codes and estimated arrival`() {
        val res = ManualFlightInput.build(
            fromCode = " ord ",
            toCode = "sfo",
            dateIso = "2026-03-12",
            airline = "ua",
            flightNumber = "123",
            zone = ZoneOffset.UTC,
        ) { airports[it] }

        assertTrue(res is ManualFlightResult.Ok)
        val f = (res as ManualFlightResult.Ok).flight
        assertEquals("ORD", f.fromAirport)
        assertEquals("SFO", f.toAirport)
        assertEquals("UA", f.airline)
        assertEquals("123", f.flightNumber)
        assertEquals("imported", f.source)
        assertEquals("manual", f.importOrigin)
        assertTrue(f.distanceKm in 2800.0..3100.0)
        assertTrue(f.arriveTs > f.departTs)
    }

    @Test
    fun `invalid inputs return clear validation errors`() {
        val badCode = ManualFlightInput.build("US", "SFO", "2026-03-12") { airports[it] }
        assertTrue(badCode is ManualFlightResult.Error)

        val same = ManualFlightInput.build("ORD", "ord", "2026-03-12") { airports[it] }
        assertTrue(same is ManualFlightResult.Error)

        val unknown = ManualFlightInput.build("ZZZ", "SFO", "2026-03-12") { airports[it] }
        assertTrue(unknown is ManualFlightResult.Error)

        val badDate = ManualFlightInput.build("ORD", "SFO", "2025-02-29") { airports[it] }
        assertTrue(badDate is ManualFlightResult.Error)

        val leapDay = ManualFlightInput.build("ORD", "SFO", "2024-02-29") { airports[it] }
        assertTrue(leapDay is ManualFlightResult.Ok)
    }

    @Test
    fun `filterFlights filters by hidden flag, year, and query`() {
        val f1 = Flight(LatLon(0.0, 0.0), LatLon(1.0, 1.0), Instant.parse("2025-05-01T12:00:00Z").epochSecond, 0, 500.0, "ORD", "SFO", airline = "UA", flightNumber = "42", source = "detected")
        val f2 = Flight(LatLon(0.0, 0.0), LatLon(1.0, 1.0), Instant.parse("2026-06-01T12:00:00Z").epochSecond, 0, 500.0, "WAW", "ORD", airline = "LO", flightNumber = "3", source = "imported", importOrigin = "screenshot")
        val f3 = Flight(LatLon(0.0, 0.0), LatLon(1.0, 1.0), Instant.parse("2026-07-01T12:00:00Z").epochSecond, 0, 500.0, "SFO", "WAW", dismissed = true, source = "imported", importOrigin = "manual")
        val all = listOf(f1, f2, f3)

        assertEquals(2, filterFlights(all, showHidden = false, selectedYear = null, query = "", zone = ZoneOffset.UTC).size)
        assertEquals(3, filterFlights(all, showHidden = true, selectedYear = null, query = "", zone = ZoneOffset.UTC).size)
        assertEquals(listOf(f2), filterFlights(all, showHidden = false, selectedYear = 2026, query = "", zone = ZoneOffset.UTC))
        assertEquals(listOf(f1), filterFlights(all, showHidden = false, selectedYear = null, query = "ua 42", zone = ZoneOffset.UTC))
        assertEquals(listOf(f1), filterFlights(all, showHidden = false, selectedYear = null, query = "ord->sfo", zone = ZoneOffset.UTC))
        assertEquals(listOf(f1), filterFlights(all, showHidden = false, selectedYear = null, query = "2025", zone = ZoneOffset.UTC))
        assertEquals(listOf(f2), filterFlights(all, showHidden = false, selectedYear = null, query = "waw", zone = ZoneOffset.UTC))
        assertEquals(listOf(f1), filterFlights(all, showHidden = false, selectedYear = null, query = "dawarich", zone = ZoneOffset.UTC))
        assertEquals(listOf(f2), filterFlights(all, showHidden = false, selectedYear = null, query = "screenshot", zone = ZoneOffset.UTC))
        assertEquals(listOf(f3), filterFlights(all, showHidden = true, selectedYear = null, query = "manual", zone = ZoneOffset.UTC))
    }

    @Test
    fun `flights to Russian airports keep their real coordinates`() {
        val r = ManualFlightInput.build(
            fromCode = "ORD", toCode = "SVO", dateIso = "2026-04-14", zone = ZoneOffset.UTC,
        ) { airports[it] } as ManualFlightResult.Ok
        assertEquals(55.9726, r.flight.to.lat, 1e-4)
        assertEquals(41.9786, r.flight.from.lat, 1e-4)
        assertNotEquals(MOSKVA_WRECK, r.flight.to)
    }
}
