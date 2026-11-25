package dev.mriya.imports

import dev.mriya.domain.imports.FlightExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Layouts of real booking emails (travel portal, low-cost carrier, airline partner notice), with invented data. */
class AirlineEmailFormatsTest {
    private val airports = setOf("ORD", "DFW", "SCL", "AEP", "LIN", "LHR", "SJD", "MIA")
    private val ref = LocalDate.of(2026, 11, 25)
    private fun parse(text: String) = FlightExtractor.extract(text, ref) { it in airports }

    @Test
    fun `travel portal receipt with a p m time on separate lines`() {
        val text = """
            Pack your bags for Dallas!
            YOUR CONFIRMATION CODES
            American Airlines
            ABCDEF
            Outbound to Dallas
            Basic Economy
            June 25, 2026
            2h 34m Nonstop
            American Airlines - AA760
            Chicago
            ORD
            5:00 p.m.

            2h 34m
            Dallas
            DFW
            7:34 p.m.
            Return to Chicago
            Basic Economy
            June 26, 2026
            2h 32m Nonstop
            American Airlines - AA3075
            Dallas
            DFW
            6:00 a.m.

            2h 32m
            Chicago
            ORD
            8:32 a.m.
        """.trimIndent()
        val f = parse(text)
        assertEquals(listOf("ORD-DFW", "DFW-ORD"), f.map { "${it.from}-${it.to}" })
        assertEquals(listOf(LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 26)), f.map { it.date })
        assertEquals(listOf("760", "3075"), f.map { it.flightNumber })
        assertEquals(LocalTime.of(17, 0), f[0].departTime)
        assertEquals(LocalTime.of(6, 0), f[1].departTime)
    }

    @Test
    fun `low-cost carrier with day month year dates and two passengers`() {
        val text = """
            Booking code ABC123 Thank you for choosing us!
            PASSENGER 1
            PASSENGER NAME PASSENGER ID TICKET ISSUE DATE
            JANE DOE 2507159640576245 18/04/2025
            BOOKING DETAILS
            Date: 02/09/2025

            Santiago
            SCL
            Time of departure:21:54

            Buenos Aires, Aeroparque
            AEP
            Time of arrival:01:00

            *Flight JA3885 (WJ) - Operated by JetSMART Airlines SA
            PASSENGER 2
            PASSENGER NAME PASSENGER ID TICKET ISSUE DATE
            JOHN DOE 2507159640576246 18/04/2025
            BOOKING DETAILS
            Date: 02/09/2025

            Santiago
            SCL
            Time of departure:21:54

            Buenos Aires, Aeroparque
            AEP
            Time of arrival:01:00

            *Flight JA3885 (WJ) - Operated by JetSMART Airlines SA
            TRANSACTIONS
            18/04/2025 XXXX- USD 156,14 Approved
            30/08/2025 XXXX-8399 Credit CLP 92.924 Rejected
        """.trimIndent()
        val f = parse(text)
        assertEquals(1, f.size)
        assertEquals("SCL", f[0].from)
        assertEquals("AEP", f[0].to)
        assertEquals(LocalDate.of(2025, 9, 2), f[0].date)
        assertEquals(LocalTime.of(21, 54), f[0].departTime)
    }

    @Test
    fun `partner airline notice with codes and names on separate lines`() {
        val text = """
            Get ready for your trip with British Airways
            British Airways confirmation code: ABC12D
            Sunday, October 11, 2026

            LIN
            Milan-Linate
            11:00 AM
            LHR
            London Heathrow
            12:05 PM

            BA 0571

            Operated by
            British Airways
        """.trimIndent()
        val f = parse(text)
        assertEquals(1, f.size)
        assertEquals("LIN", f[0].from)
        assertEquals("LHR", f[0].to)
        assertEquals(LocalDate.of(2026, 10, 11), f[0].date)
        assertEquals("571", f[0].flightNumber)
    }
}
