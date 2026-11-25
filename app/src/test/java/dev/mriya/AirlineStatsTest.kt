package dev.mriya

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.domain.AirlineDirectory
import dev.mriya.domain.AirlineRankBy
import dev.mriya.domain.computeTopAirlines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AirlineStatsTest {

    private fun flight(
        airline: String?,
        distanceKm: Double,
        dismissed: Boolean = false,
        ts: Long = 1_700_000_000L,
    ) = Flight(
        from = LatLon(50.0, 30.0),
        to = LatLon(52.0, 21.0),
        departTs = ts,
        arriveTs = ts + 7200,
        distanceKm = distanceKm,
        airline = airline,
        dismissed = dismissed,
    )

    @Test
    fun `empty or untagged flights report zero airlines and track untagged count`() {
        val empty = computeTopAirlines(emptyList())
        assertEquals(0, empty.totalAirlines)
        assertEquals(0, empty.taggedFlights)
        assertEquals(0, empty.untaggedFlights)

        val untaggedOnly = computeTopAirlines(
            listOf(
                flight(airline = null, distanceKm = 1200.0),
                flight(airline = "  ", distanceKm = 900.0),
                flight(airline = "UA", distanceKm = 5000.0, dismissed = true),
            )
        )
        assertEquals(0, untaggedOnly.totalAirlines)
        assertEquals(0, untaggedOnly.taggedFlights)
        assertEquals(2, untaggedOnly.untaggedFlights)
    }

    @Test
    fun `aggregates normalized airline codes and ignores dismissed flights`() {
        val flights = listOf(
            flight("ua", 4_000.0, ts = 1),
            flight(" UA ", 2_500.0, ts = 2),
            flight("ca", 10_000.0, ts = 3),
            flight("ca", 1_000.0, dismissed = true, ts = 4),
            flight(null, 800.0, ts = 5),
        )
        val summary = computeTopAirlines(flights, AirlineRankBy.DISTANCE)

        assertEquals(2, summary.totalAirlines)
        assertEquals(3, summary.taggedFlights)
        assertEquals(1, summary.untaggedFlights)

        assertEquals("CA", summary.ranked[0].code)
        assertEquals("Air China", summary.ranked[0].name)
        assertEquals(1, summary.ranked[0].flights)
        assertEquals(10_000.0, summary.ranked[0].distanceKm, 0.01)

        assertEquals("UA", summary.ranked[1].code)
        assertEquals("United Airlines", summary.ranked[1].name)
        assertEquals(2, summary.ranked[1].flights)
        assertEquals(6_500.0, summary.ranked[1].distanceKm, 0.01)
    }

    @Test
    fun `ranking mode switches cleanly between distance and flight count`() {
        val flights = listOf(
            flight("LO", 700.0, ts = 1),
            flight("LO", 700.0, ts = 2),
            flight("LO", 700.0, ts = 3),
            flight("CA", 9_000.0, ts = 4),
        )

        val byDistance = computeTopAirlines(flights, AirlineRankBy.DISTANCE)
        assertEquals(listOf("CA", "LO"), byDistance.ranked.map { it.code })

        val byCount = computeTopAirlines(flights, AirlineRankBy.FLIGHTS)
        assertEquals(listOf("LO", "CA"), byCount.ranked.map { it.code })
    }

    @Test
    fun `directory resolves known codes and deterministic fallback for unknown codes`() {
        val ca = AirlineDirectory.lookup("ca")
        assertEquals("CA", ca.code)
        assertEquals("Air China", ca.name)

        val ps = AirlineDirectory.lookup("PS")
        assertEquals("UIA", ps.name)

        val unknown1 = AirlineDirectory.lookup("zz")
        val unknown2 = AirlineDirectory.lookup("ZZ")
        assertEquals("ZZ", unknown1.code)
        assertEquals("ZZ", unknown1.name)
        assertEquals(unknown1.accentHex, unknown2.accentHex)
        assertNotEquals(0L, unknown1.accentHex)
    }
}
