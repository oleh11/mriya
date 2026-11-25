package dev.mriya

import dev.mriya.domain.StampDesign
import dev.mriya.domain.StampEmblem
import dev.mriya.domain.StampInk
import dev.mriya.domain.StampMood
import dev.mriya.domain.StampShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StampDesignTest {
    @Test
    fun `stamp style is deterministic and covers all six shapes`() {
        val a = StampDesign.styleForCountry("UA", "Europe", isEarliest = false, dayCount = 10)
        val b = StampDesign.styleForCountry("ua", "Europe", isEarliest = false, dayCount = 10)
        assertEquals(a, b)

        val sampleCountries = listOf("US", "UA", "GB", "FR", "DE", "JP", "BR", "AU", "CA", "MX", "IT", "ES", "TR", "PL", "NO", "KR", "ZA", "EG")
        val shapes = sampleCountries.map { StampDesign.styleForCountry(it, "Europe", false, 5).shape }.toSet()
        assertEquals(StampShape.entries.toSet(), shapes)
    }

    @Test
    fun `earliest stamp gets gold first visit mood and single-day gets transit`() {
        val earliest = StampDesign.styleForCountry("JP", "Asia", isEarliest = true, dayCount = 5)
        assertEquals(StampMood.FIRST_VISIT, earliest.mood)
        assertEquals(StampInk.GOLD, earliest.ink)

        val transit = StampDesign.styleForCountry("NL", "Europe", isEarliest = false, dayCount = 1)
        assertEquals(StampMood.TRANSIT, transit.mood)
        assertTrue(transit.ink != StampInk.GOLD)
    }

    @Test
    fun `rotation stays subtle within plus or minus 4 degrees`() {
        listOf("US", "UA", "JP", "BR", "AU", "FR").forEach { iso ->
            val s = StampDesign.styleForCountry(iso, null, false, 3)
            assertTrue(s.rotationDeg in -4f..4f)
        }
    }

    @Test
    fun `known countries receive country-specific emblems, entry labels, and Schengen flags`() {
        val ua = StampDesign.styleForCountry("UA", "Europe", false, 5)
        assertEquals(StampEmblem.TRIDENT, ua.emblem)
        assertEquals("В'ЇЗД • ENTRY", ua.entryLabel)
        assertFalse(ua.isSchengen)

        val jp = StampDesign.styleForCountry("JP", "Asia", false, 5)
        assertEquals(StampEmblem.TORII, jp.emblem)
        assertEquals("上陸許可", jp.entryLabel)

        val tw = StampDesign.styleForCountry("TW", "Asia", false, 5)
        assertEquals(StampEmblem.TAIPEI_SPIRE, tw.emblem)
        assertEquals("入境 • ENTRY", tw.entryLabel)

        val fr = StampDesign.styleForCountry("FR", "Europe", false, 5)
        assertEquals(StampEmblem.EIFFEL, fr.emblem)
        assertEquals("ENTRÉE", fr.entryLabel)
        assertTrue(fr.isSchengen)

        val unknown = StampDesign.styleForCountry("XX", "Africa", false, 5)
        assertEquals(StampEmblem.CONTINENT_FALLBACK, unknown.emblem)
    }
}
