package dev.mriya.imports

import dev.mriya.domain.imports.FlightExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** An airline app's "past flights" list: one trip per row, confirmation code first, year as a header. */
class FlightListImportTest {
    private val airports = setOf("ORD", "STL", "DFW", "IAH", "GDL", "SAN", "CUN")
    private val ref = LocalDate.of(2026, 11, 25)
    private fun legs(text: String) = FlightExtractor.extract(text, ref) { it in airports }.map { "${it.from}-${it.to} ${it.date}" }

    private val expected = listOf(
        "STL-ORD 2025-12-17", "ORD-STL 2025-12-15", "DFW-ORD 2025-12-04", "IAH-GDL 2025-11-10", "CUN-ORD 2025-01-12",
    )

    @Test
    fun `date on the row`() {
        val text = """
            Past flights
            2025   12 FLIGHTS
            J576LK • STL → ORD   Wed, Dec 17
            St. Louis to Chicago
            J576LK • ORD → STL   Mon, Dec 15
            Chicago to St. Louis
            IMHZ95 • DFW → ORD   Thu, Dec 4
            Dallas to Chicago
            J1CKN4 • IAH → GDL   Mon, Nov 10
            Houston to Guadalajara
            NB2ZL9 • CUN → ORD   Sun, Jan 12
            Cancún to Chicago
        """.trimIndent()
        assertEquals(expected, legs(text))
    }

    @Test
    fun `date on the line below`() {
        val text = """
            Past flights
            2025
            J576LK • STL → ORD
            Wed, Dec 17
            St. Louis to Chicago
            J576LK • ORD → STL
            Mon, Dec 15
            Chicago to St. Louis
            IMHZ95 • DFW → ORD
            Thu, Dec 4
            J1CKN4 • IAH → GDL
            Mon, Nov 10
            NB2ZL9 • CUN → ORD
            Sun, Jan 12
        """.trimIndent()
        assertEquals(expected, legs(text))
    }
}
