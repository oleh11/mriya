package dev.mriya.imports

import dev.mriya.domain.imports.EventFlights
import dev.mriya.domain.imports.FlightExtractor
import dev.mriya.domain.imports.Ics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Flight numbers with a single digit (QF1, BA9) are real; stray "AB 1" in prose is not. */
class OneDigitFlightNumberTest {
    private val airports = setOf("SYD", "SIN", "LHR", "JFK")
    private val ref = LocalDate.of(2026, 11, 25)
    private val isAirport = { c: String -> c in airports }

    @Test
    fun `calendar event keeps a one-digit flight number`() {
        val ics = "BEGIN:VEVENT\nSUMMARY:Flight QF1 SYD-SIN\nDTSTART:20250108T080000Z\nDTEND:20250108T150000Z\nEND:VEVENT"
        val f = EventFlights.fromEvent(Ics.parse(ics).single(), ref, isAirport).single()
        assertEquals("1", f.flightNumber)
        assertEquals("QF", f.airline)
    }

    @Test
    fun `plain text keeps a one-digit flight number`() {
        val f = FlightExtractor.extract("Flight BA9 LHR to JFK on 12 March 2026", ref, isAirport).single()
        assertEquals("9", f.flightNumber)
    }

    @Test
    fun `spaced single digit in prose is not a flight number`() {
        val f = FlightExtractor.extract("Flight LHR to JFK on 12 March 2026. See page AB 1 of the terms.", ref, isAirport)
        f.forEach { assertNull(it.flightNumber) }
    }
}
