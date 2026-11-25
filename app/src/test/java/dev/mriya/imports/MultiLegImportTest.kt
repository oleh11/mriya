package dev.mriya.imports

import dev.mriya.domain.imports.FlightExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** An email ticket must give every flight in it, not just the first one found. */
class MultiLegImportTest {
    private val airports = setOf("ORD", "DEN", "SFO", "LAX", "JFK", "LHR", "FRA", "MUC", "YUL", "HEL", "ICN", "TPE", "SGN")
    private val ref = LocalDate.of(2026, 3, 1)
    private fun legs(text: String) = FlightExtractor.extract(text, ref) { it in airports }
    private fun routes(text: String) = legs(text).map { "${it.from}-${it.to}" }

    @Test
    fun `markup that covers only the first flight does not hide the second`() {
        val email = """
            <html><body>
            <script type="application/ld+json">
            {"@type":"FlightReservation","reservationFor":{"@type":"Flight","flightNumber":"123","airline":{"iataCode":"UA"},
             "departureAirport":{"iataCode":"ORD"},"arrivalAirport":{"iataCode":"DEN"},"departureTime":"2026-03-12T08:30:00-05:00"}}
            </script>
            Your itinerary
            Flight 1: UA 123 Chicago (ORD) to Denver (DEN) Thu, Mar 12, 2026 8:30 AM
            Flight 2: UA 456 Denver (DEN) to San Francisco (SFO) Thu, Mar 12, 2026 12:10 PM
            </body></html>
        """.trimIndent()
        val f = legs(email)
        assertEquals(listOf("ORD-DEN", "DEN-SFO"), f.map { "${it.from}-${it.to}" })
        assertEquals("json-ld", f[0].source)
        assertEquals("text", f[1].source) // not backed by markup, so it will be shown as unverified
        assertEquals("456", f[1].flightNumber)
    }

    @Test
    fun `the same flight in markup and in the text is listed once`() {
        val email = """
            <script type="application/ld+json">
            {"@type":"FlightReservation","reservationFor":{"flightNumber":"123","airline":{"iataCode":"UA"},
             "departureAirport":{"iataCode":"ORD"},"arrivalAirport":{"iataCode":"DEN"},"departureTime":"2026-03-12T08:30:00-05:00"}}
            </script>
            UA 123 Chicago (ORD) to Denver (DEN) Thu, Mar 12, 2026 8:30 AM
        """.trimIndent()
        assertEquals(listOf("ORD-DEN"), routes(email))
    }

    @Test
    fun `a chain of airports is every leg of the chain`() {
        assertEquals(listOf("ORD-DEN", "DEN-SFO"), routes("Your route: ORD - DEN - SFO on 12 Mar 2026"))
        assertEquals(listOf("MUC-FRA", "FRA-ORD"), routes("Routing MUC → FRA → ORD, 3 Apr 2026"))
    }

    @Test
    fun `an airport in a header does not shift the pairs`() {
        val f = legs(
            "Your trip to SFO is booked. Flight 1: UA 123 ORD to DEN, 12 Mar 2026. Flight 2: UA 456 DEN to SFO, 12 Mar 2026."
        )
        assertEquals(listOf("ORD-DEN", "DEN-SFO"), f.map { "${it.from}-${it.to}" })
        assertEquals(listOf("123", "456"), f.map { it.flightNumber })
    }

    @Test
    fun `table rows with the airports before the flight number`() {
        val f = legs(
            """
            Departs  Arrives  Flight  Date
            ORD      DEN      UA 123  12 Mar 2026
            DEN      SFO      UA 456  12 Mar 2026
            SFO      ORD      UA 789  16 Mar 2026
            """.trimIndent()
        )
        assertEquals(listOf("ORD-DEN", "DEN-SFO", "SFO-ORD"), f.map { "${it.from}-${it.to}" })
        assertEquals(listOf("123", "456", "789"), f.map { it.flightNumber })
        assertEquals(LocalDate.of(2026, 3, 16), f[2].date)
    }

    @Test
    fun `a four leg multi-city trip gives four flights`() {
        val f = legs(
            """
            Outbound Fri, 3 Apr 2026
            LH 100 Munich (MUC) 07:10 to Frankfurt (FRA) 08:05
            UA 200 Frankfurt (FRA) 10:40 to Chicago (ORD) 13:15
            Return Sun, 12 Apr 2026
            UA 201 Chicago (ORD) 16:20 to Frankfurt (FRA) 08:10
            LH 101 Frankfurt (FRA) 09:50 to Munich (MUC) 10:45
            """.trimIndent()
        )
        assertEquals(listOf("MUC-FRA", "FRA-ORD", "ORD-FRA", "FRA-MUC"), f.map { "${it.from}-${it.to}" })
        assertEquals(LocalDate.of(2026, 4, 12), f[3].date)
    }

    @Test
    fun `a boarding pass barcode plus the rest of the connection in the text`() {
        val f = legs("M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 226F001A0025 100\nNext: LH 400 Frankfurt (FRA) to Munich (MUC) 15 Aug 2026")
        assertEquals(listOf("YUL-FRA", "FRA-MUC"), f.map { "${it.from}-${it.to}" })
        assertEquals("boarding-pass", f[0].source)
    }

    @Test
    fun `AM or PM before a date is not a flight number`() {
        val f = legs("Pickup 8:30 PM 12 Mar 2026 then ORD to DEN")
        assertEquals(1, f.size)
        assertNull(f[0].flightNumber)
        assertTrue(routes("see you at the gate at 8:30 PM 12 Mar 2026").isEmpty())
    }
}
