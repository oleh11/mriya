package dev.mriya

import dev.mriya.domain.Airport
import dev.mriya.domain.AirportDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirportSearchTest {
    private fun a(iata: String, name: String, country: String, tier: Int, city: String) = Airport(iata, name, 0.0, 0.0, country, tier, city)
    private val db = AirportDb.of(
        listOf(
            a("DPA", "Dupage Airport", "US", 0, "West Chicago"),
            a("MDW", "Chicago Midway International Airport", "US", 2, "Chicago"),
            a("ORD", "Chicago O'Hare International Airport", "US", 2, "Chicago"),
            a("JFK", "John F. Kennedy International Airport", "US", 2, "New York"),
            a("EWR", "Newark Liberty International Airport", "US", 2, "Newark"),
            a("TPE", "Taiwan Taoyuan International Airport", "TW", 2, "Taoyuan"),
            a("TSA", "Taipei Songshan Airport", "TW", 1, "Taipei"),
            a("KBP", "Boryspil International Airport", "UA", 2, "Boryspil"),
            a("IEV", "Igor Sikorsky Kyiv International Airport", "UA", 0, "Kyiv"),
            a("ZRH", "Zürich Airport", "CH", 2, "Zurich"),
            a("SGN", "Tan Son Nhat International Airport", "VN", 2, "Ho Chi Minh City"),
        )
    )

    private fun codes(q: String) = db.search(q).map { it.iata }

    @Test
    fun `a city finds its airports, big ones first and small fields last`() {
        val r = codes("chicago")
        assertEquals(setOf("MDW", "ORD"), r.take(2).toSet())
        assertEquals("DPA", r.last()) // the small field comes after both commercial airports
    }

    @Test
    fun `an exact code comes first`() {
        assertEquals("ORD", codes("ord").first())
        assertEquals("ORD", codes("ORD").first())
    }

    @Test
    fun `airport names and partial words work`() {
        assertEquals("ORD", codes("o'hare").first())
        assertEquals("JFK", codes("new y").first())
        assertEquals("JFK", codes("kennedy").first())
    }

    @Test
    fun `accents and case are ignored`() {
        assertEquals("ZRH", codes("zurich").first())
        assertEquals("ZRH", codes("ZÜRICH").first())
    }

    @Test
    fun `a name people use finds the airport even when the data says otherwise`() {
        assertTrue("TPE" in codes("taipei"))          // listed under Taoyuan
        assertTrue(codes("taipei").take(2).toSet() == setOf("TPE", "TSA"))
        assertTrue("KBP" in codes("kyiv"))            // listed under Boryspil
        assertTrue("KBP" in codes("kiev"))
        assertTrue("SGN" in codes("saigon"))
    }

    @Test
    fun `country and several words`() {
        assertTrue("TPE" in codes("taiwan"))
        assertTrue("SGN" in codes("ho chi minh"))
    }

    @Test
    fun `empty and nonsense queries give nothing`() {
        assertTrue(db.search("").isEmpty())
        assertTrue(db.search("   ").isEmpty())
        assertTrue(db.search("qqqqzzzz").isEmpty())
    }

    @Test
    fun `the bundled list has a city for most airports and the ones people look for`() {
        val rows = listOf(File("src/main/assets/airports.csv"), File("app/src/main/assets/airports.csv")).first { it.exists() }.readLines().filter { it.isNotBlank() }
        val real = AirportDb.of(rows.map { l -> l.split(',').let { c -> Airport(c[0], c[1], c[2].toDouble(), c[3].toDouble(), c[4], c[5].toInt(), c.getOrElse(6) { "" }) } })
        assertTrue(rows.size > 4000)
        assertTrue("share of airports with a city", rows.count { it.split(',').getOrElse(6) { "" }.isNotBlank() } > rows.size * 9 / 10)
        for ((query, expected) in listOf("chicago" to "ORD", "taipei" to "TPE", "kyiv" to "KBP", "new york" to "JFK", "ho chi minh" to "SGN", "tokyo" to "NRT", "london" to "LHR", "paris" to "CDG")) {
            assertTrue("$query should find $expected, found ${real.search(query, 10).map { it.iata }}", expected in real.search(query, 10).map { it.iata })
        }
    }
}
