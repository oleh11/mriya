package dev.mriya.imports

import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.PassportData
import dev.mriya.data.UserData
import dev.mriya.data.stableKey
import dev.mriya.domain.applyUserData
import dev.mriya.domain.imports.stampFlightCountries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ImportStampsTest {

    private fun sampleFlight(
        fromIata: String = "KBP",
        toIata: String = "WAW",
        depart: String = "2024-06-10T08:00:00Z",
        arrive: String = "2024-06-10T09:30:00Z",
        dismissed: Boolean = false,
    ) = Flight(
        from = LatLon(50.345, 30.8947),
        to = LatLon(52.1657, 20.9671),
        departTs = Instant.parse(depart).epochSecond,
        arriveTs = Instant.parse(arrive).epochSecond,
        distanceKm = 690.0,
        fromAirport = fromIata,
        toAirport = toIata,
        dismissed = dismissed,
        source = "imported",
    )

    private val iataCountries = mapOf(
        "KBP" to "UA",
        "WAW" to "PL",
        "ORD" to "US",
        "HND" to "JP",
    )

    @Test
    fun `imported flight adds stamps for departure and arrival countries`() {
        val f = sampleFlight()
        val stamps = stampFlightCountries(emptyMap(), listOf(f)) { iataCountries[it] }

        assertEquals(setOf("UA", "PL"), stamps.keys)
        val ua = stamps.getValue("UA")
        assertEquals("Ukraine", ua.name)
        assertEquals(f.departTs, ua.firstTs)
        assertEquals(setOf("2024-06-10"), ua.days)

        val pl = stamps.getValue("PL")
        assertEquals("Poland", pl.name)
        assertEquals(f.arriveTs, pl.lastTs)
        assertEquals(setOf("2024-06-10"), pl.days)
    }

    @Test
    fun `merges with existing stamp while preserving cities and expanding bounds`() {
        val existingTs = Instant.parse("2024-08-01T12:00:00Z").epochSecond
        val existing = mapOf(
            "PL" to CountryStamp("Poland", "PL", existingTs, existingTs, setOf("2024-08-01"), setOf("Krakow"))
        )
        val f = sampleFlight()
        val merged = stampFlightCountries(existing, listOf(f)) { iataCountries[it] }

        val pl = merged.getValue("PL")
        assertEquals(f.arriveTs, pl.firstTs)
        assertEquals(existingTs, pl.lastTs)
        assertEquals(setOf("2024-06-10", "2024-08-01"), pl.days)
        assertEquals(setOf("Krakow"), pl.cities)
    }

    @Test
    fun `dismissed imported flight does not create stamps`() {
        val dismissed = sampleFlight(dismissed = true)
        val keyDismissed = sampleFlight(fromIata = "ORD", toIata = "HND")

        val stamps = stampFlightCountries(
            emptyMap(),
            listOf(dismissed, keyDismissed),
            dismissedKeys = setOf(keyDismissed.stableKey()),
        ) { iataCountries[it] }

        assertTrue(stamps.isEmpty())
    }

    @Test
    fun `hidden country in user data still hides stamp from imported flight`() {
        val f = sampleFlight(fromIata = "ORD", toIata = "HND")
        val stamped = stampFlightCountries(emptyMap(), listOf(f)) { iataCountries[it] }
        val result = applyUserData(PassportData(stamps = stamped, flights = listOf(f)), UserData(hiddenCountries = setOf("US")))

        assertFalse("US" in result.stamps)
        assertNull(result.stamps["US"])
        assertTrue("JP" in result.stamps)
    }
}
