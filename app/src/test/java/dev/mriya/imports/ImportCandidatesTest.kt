package dev.mriya.imports

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.domain.imports.ConvertedFlight
import dev.mriya.domain.imports.buildImportCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ImportCandidatesTest {

    private fun flight(
        from: String,
        to: String,
        departIso: String,
        dismissed: Boolean = false,
    ): Flight {
        val dep = Instant.parse(departIso).epochSecond
        return Flight(
            from = LatLon(41.9742, -87.9073),
            to = LatLon(37.6213, -122.3790),
            departTs = dep,
            arriveTs = dep + 14_400,
            distanceKm = 2970.0,
            fromAirport = from,
            toAirport = to,
            dismissed = dismissed,
            source = "imported",
        )
    }

    @Test
    fun `structured non-duplicate import is selected while unverified text match starts unticked`() {
        val structured = ConvertedFlight(flight("ORD", "SFO", "2026-03-12T10:00:00Z"), unverified = false)
        val textMatch = ConvertedFlight(flight("JFK", "LHR", "2026-04-01T18:00:00Z"), unverified = true)

        val candidates = buildImportCandidates(listOf(textMatch, structured), emptyList())
        assertEquals(2, candidates.size)

        assertEquals("ORD", candidates[0].flight.fromAirport)
        assertTrue(candidates[0].selected)
        assertFalse(candidates[0].unverified)

        assertEquals("JFK", candidates[1].flight.fromAirport)
        assertFalse(candidates[1].selected)
        assertTrue(candidates[1].unverified)
        assertFalse(candidates[1].duplicate)
    }

    @Test
    fun `duplicate is unticked and dismissed match sets restoreId unless unverified`() {
        val existingVisible = flight("ORD", "SFO", "2026-03-12T12:00:00Z", dismissed = false)
        val existingDismissed = flight("WAW", "CDG", "2026-05-10T09:00:00Z", dismissed = true)

        val dupImport = ConvertedFlight(flight("ORD", "SFO", "2026-03-12T10:00:00Z"), unverified = false)
        val restoreStructured = ConvertedFlight(flight("WAW", "CDG", "2026-05-10T11:00:00Z"), unverified = false)
        val restoreText = ConvertedFlight(flight("WAW", "CDG", "2026-05-10T13:00:00Z"), unverified = true)

        val candidates = buildImportCandidates(
            listOf(dupImport, restoreStructured, restoreText),
            listOf(existingVisible, existingDismissed),
        )

        assertTrue(candidates[0].duplicate)
        assertFalse(candidates[0].selected)
        assertNull(candidates[0].restoreId)

        assertFalse(candidates[1].duplicate)
        assertTrue(candidates[1].selected)
        assertEquals(existingDismissed.id, candidates[1].restoreId)

        assertFalse(candidates[2].duplicate)
        assertFalse(candidates[2].selected)
        assertTrue(candidates[2].unverified)
        assertEquals(existingDismissed.id, candidates[2].restoreId)
    }

    @Test
    fun `import with airline and flight number enriches untagged existing GPS flight`() {
        val existingGps = flight("SJD", "ORD", "2026-11-18T14:00:00Z")
        val ocrWithCarrier = ConvertedFlight(
            flight("SJD", "ORD", "2026-11-18T12:00:00Z").copy(airline = "AA", flightNumber = "2636"),
            unverified = false,
        )

        val candidates = buildImportCandidates(listOf(ocrWithCarrier), listOf(existingGps))
        val c = candidates.single()
        assertFalse(c.duplicate)
        assertTrue(c.enrichesExisting)
        assertTrue(c.selected)
    }
}
