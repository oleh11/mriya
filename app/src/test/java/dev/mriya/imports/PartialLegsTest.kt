package dev.mriya.imports

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.domain.imports.FlightExtractor
import dev.mriya.domain.imports.completePartialLegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A travel-portal itinerary names only one airport per flight; the other end comes from a flight already known. */
class PartialLegsTest {
    private val airports = setOf("ORD", "SJD", "DFW")
    private val ref = LocalDate.of(2026, 11, 25)
    private val email = """
        Booking Date:August 20, 2026
        Departure Date:November 13, 2026
        Return Date:November 18, 2026
        Transportation
        Arrival Transfer

        Flight Details:

        Arrival to Los Cabos (SJD)
        Nov 13, 2026 at 12:45 PM, Flight AA 1649

        Transfer Details:
        Nov 13, 2026 - 12:45 PM
        Arrival Transfer - From Airport (SJD) to Hotel in a Shared Vehicle

        Departure Transfer

        Flight Details:

        Depart from Los Cabos (SJD)
        Nov 18, 2026 at 01:45 PM, Flight AA 2636

        Transfer Details:
        Departure Transfer - From Hotel to Airport (SJD) in a Shared Vehicle
    """.trimIndent()

    private fun flight(from: String, to: String, day: LocalDate) = LocalDateTime.of(day, java.time.LocalTime.of(9, 0)).toEpochSecond(ZoneOffset.UTC).let {
        Flight(LatLon(0.0, 0.0), LatLon(1.0, 1.0), it, it + 14_000, 2800.0, fromAirport = from, toAirport = to)
    }

    @Test
    fun `each flight is kept with one end blank and its own date`() {
        val legs = FlightExtractor.extract(email, ref) { it in airports }
        assertEquals(2, legs.size)
        val arrive = legs.first { it.to == "SJD" }
        assertEquals("", arrive.from)
        assertEquals(LocalDate.of(2026, 11, 13), arrive.date)
        assertEquals("1649", arrive.flightNumber)
        val depart = legs.first { it.from == "SJD" }
        assertEquals("", depart.to)
        assertEquals(LocalDate.of(2026, 11, 18), depart.date)
        assertEquals("2636", depart.flightNumber)
    }

    @Test
    fun `the other end is taken from a known flight on that day`() {
        val legs = FlightExtractor.extract(email, ref) { it in airports }
        val known = listOf(flight("ORD", "SJD", LocalDate.of(2026, 11, 13)), flight("SJD", "ORD", LocalDate.of(2026, 11, 18)))
        val done = completePartialLegs(legs, known, ZoneOffset.UTC)
        assertEquals(setOf("ORD-SJD", "SJD-ORD"), done.map { "${it.from}-${it.to}" }.toSet())
        assertTrue(done.all { it.source == "email-resolved" })
    }

    @Test
    fun `no matching flight or several different ones drops the leg`() {
        val legs = FlightExtractor.extract(email, ref) { it in airports }
        assertTrue(completePartialLegs(legs, emptyList(), ZoneOffset.UTC).isEmpty())
        val two = listOf(flight("ORD", "SJD", LocalDate.of(2026, 11, 13)), flight("DFW", "SJD", LocalDate.of(2026, 11, 13)))
        assertTrue(completePartialLegs(legs, two, ZoneOffset.UTC).none { it.to == "SJD" })
    }
}
