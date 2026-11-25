package dev.mriya

import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.PassportData
import dev.mriya.data.UserData
import dev.mriya.data.stableKey
import dev.mriya.domain.Trophies
import dev.mriya.domain.applyUserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophiesTest {
    private val continent = mapOf("US" to "North America", "DE" to "Europe", "AU" to "Oceania", "JP" to "Asia")
    private fun stamp(iso: String) = CountryStamp(iso, iso, 0, 0, setOf("2026-01-01"), emptySet())
    private fun flight(from: String, to: String, a: LatLon, b: LatLon, day: Int, km: Double = 1000.0, dismissed: Boolean = false) =
        Flight(a, b, day * 86_400L, day * 86_400L + 7200, km, fromAirport = from, toAirport = to, dismissed = dismissed)

    private val ord = LatLon(41.98, -87.9); private val fra = LatLon(50.03, 8.57)
    private val syd = LatLon(-33.94, 151.18); private val lax = LatLon(33.94, -118.4)

    private fun data(flights: List<Flight>, vararg iso: String) =
        PassportData(stamps = iso.associateWith { stamp(it) }, flights = flights)

    @Test
    fun `stats count only visible flights and stamps`() {
        val d = data(
            listOf(flight("ORD", "FRA", ord, fra, 1, 7000.0), flight("FRA", "ORD", fra, ord, 5, 7000.0, dismissed = true)),
            "US", "DE",
        )
        val s = Trophies.stats(d) { continent[it] }
        assertEquals(2, s.countries); assertEquals(2, s.continents); assertEquals(1, s.flights)
        assertEquals(7000.0, s.totalKm, 0.001); assertEquals(2, s.airports)
    }

    @Test
    fun `equator southern hemisphere and date line are detected`() {
        val s = Trophies.stats(data(listOf(flight("LAX", "SYD", lax, syd, 1, 12000.0)), "US", "AU")) { continent[it] }
        assertTrue(s.crossedEquator); assertTrue(s.southern); assertTrue(s.crossedDateLine)
        val north = Trophies.stats(data(listOf(flight("ORD", "FRA", ord, fra, 1)), "US")) { continent[it] }
        assertFalse(north.crossedEquator); assertFalse(north.southern); assertFalse(north.crossedDateLine)
    }

    @Test
    fun `achievements unlock at their thresholds`() {
        val flights = (1..10).map { flight("ORD", "FRA", ord, fra, it, 7000.0) }
        val ach = Trophies.achievements(Trophies.stats(data(flights, "US", "DE", "JP")) { continent[it] }).associateBy { it.id }
        assertTrue(ach.getValue("flights_10").unlocked); assertFalse(ach.getValue("flights_25").unlocked)
        assertTrue(ach.getValue("countries_1").unlocked); assertFalse(ach.getValue("countries_5").unlocked)
        assertTrue(ach.getValue("continents_3").unlocked)
        assertTrue(ach.getValue("km_40075").unlocked) // 10 flights x 7,000 km = 70,000 km
        assertTrue(ach.getValue("year_10").unlocked) // days 1..10 after the epoch are all in 1970
    }

    @Test
    fun `xp and ranks`() {
        val s = Trophies.stats(data(listOf(flight("ORD", "FRA", ord, fra, 1, 7000.0)), "US", "DE")) { continent[it] }
        assertEquals(2 * 100 + 2 * 150 + 10 + 7 * 5, Trophies.xp(s))
        assertEquals("Dreamer", Trophies.rank(0).title)
        assertEquals("Navigator", Trophies.rank(600).title)
        assertEquals("Wanderer", Trophies.rank(599).title)
        assertEquals("Mriya", Trophies.rank(99_999).title)
        assertEquals("Wanderer", Trophies.nextRank(0)?.title)
        assertEquals(null, Trophies.nextRank(99_999))
    }

    @Test
    fun `achievement ids are unique`() {
        val ids = Trophies.achievements(Trophies.stats(PassportData()) { null }).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `hidden stamps and dismissed flight keys are applied on top of the data`() {
        val f = flight("ORD", "FRA", ord, fra, 3)
        val d = data(listOf(f), "US", "DE")
        val applied = applyUserData(d, UserData(dismissedFlights = setOf(f.stableKey()), hiddenCountries = setOf("DE")))
        assertTrue(applied.flights.single().dismissed)
        assertEquals(setOf("US"), applied.stamps.keys)
        assertEquals(setOf("DE"), applied.hiddenCountries)
    }

    @Test
    fun `stable key ignores the exact timestamps within a day`() {
        val a = flight("ORD", "FRA", ord, fra, 3)
        val b = a.copy(departTs = a.departTs + 5000, arriveTs = a.arriveTs + 9000)
        assertEquals(a.stableKey(), b.stableKey())
    }
}
