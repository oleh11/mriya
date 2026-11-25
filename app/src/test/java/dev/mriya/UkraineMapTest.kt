package dev.mriya

import dev.mriya.domain.CountryShape
import dev.mriya.domain.OfflineCountryLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UkraineMapTest {
    private fun loadShapes(): List<CountryShape> {
        val candidates = listOf(
            File("src/main/assets/countries.json"),
            File("app/src/main/assets/countries.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate countries.json from ${File(".").absolutePath}")
        return Json.parseToJsonElement(file.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            CountryShape(
                o["iso"]?.jsonPrimitive?.content,
                o["rings"]!!.jsonArray.map { ring ->
                    ring.jsonArray.map { pt ->
                        pt.jsonArray[0].jsonPrimitive.double.toFloat() to pt.jsonArray[1].jsonPrimitive.double.toFloat()
                    }
                },
            )
        }
    }

    @Test
    fun `crimea is part of ukraine and excluded from russia in countries json`() {
        val shapes = loadShapes()
        assertTrue(shapes.size > 150)

        val ua = shapes.firstOrNull { it.iso == "UA" }
        val ru = shapes.firstOrNull { it.iso == "RU" }
        assertNotNull(ua)
        assertNotNull(ru)
        assertEquals(1, ua!!.rings.size)
        val uaRing = ua.rings.single()
        assertEquals(uaRing.first(), uaRing.last())

        val lookup = OfflineCountryLookup(shapes)
        val ukrainianPoints = listOf(
            "Simferopol" to (45.0522 to 33.9751),
            "Sevastopol" to (44.6166 to 33.5254),
            "Yalta" to (44.4952 to 34.1663),
            "Kerch" to (45.35 to 36.40),
            "Kyiv" to (50.45 to 30.52),
            "Luhansk" to (48.57 to 39.31),
        )
        for ((city, coords) in ukrainianPoints) {
            val (lat, lon) = coords
            assertEquals("$city should resolve to UA", "UA", lookup.isoAt(lat, lon))
            assertTrue("UA shape should contain $city", ua.contains(lon.toFloat(), lat.toFloat()))
            assertFalse("RU shape must not contain $city", ru!!.contains(lon.toFloat(), lat.toFloat()))
        }
    }
}
