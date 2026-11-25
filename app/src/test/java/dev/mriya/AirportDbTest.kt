package dev.mriya

import dev.mriya.data.LatLon
import dev.mriya.domain.Airport
import dev.mriya.domain.AirportDb
import org.junit.Assert.assertEquals
import org.junit.Test

class AirportDbTest {
    private val db = AirportDb.of(listOf(
        Airport("DPA", "DuPage", 41.9078, -88.2486, "US", 0),
        Airport("ORD", "O'Hare", 41.9786, -87.9048, "US", 2),
    ))

    @Test
    fun `a big airport beats a nearby general-aviation field`() {
        assertEquals("ORD", db.nearest(LatLon(41.92, -88.2), 40.0)?.iata) // ~5 km from DPA, ~30 km from ORD
    }

    @Test
    fun `the small field is still used when nothing else is in range`() {
        assertEquals("DPA", db.nearest(LatLon(41.92, -88.2), 10.0)?.iata)
    }
}
