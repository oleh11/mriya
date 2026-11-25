package dev.mriya

import dev.mriya.data.LatLon
import dev.mriya.domain.greatCircleLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreatCircleTest {
    @Test
    fun `short route is one line from a to b`() {
        val lines = greatCircleLines(LatLon(52.36, 13.5), LatLon(51.47, -0.45))
        assertEquals(1, lines.size)
        assertEquals(13.5, lines[0].first()[0], 0.01)
        assertEquals(-0.45, lines[0].last()[0], 0.01)
    }

    @Test
    fun `route across the antimeridian is split in two`() {
        val lines = greatCircleLines(LatLon(35.5, 139.7), LatLon(37.6, -122.4)) // Tokyo -> San Francisco
        assertEquals(2, lines.size)
        assertTrue(lines.all { l -> l.zipWithNext().all { (p, q) -> kotlin.math.abs(p[0] - q[0]) < 180 } })
    }

    @Test
    fun `identical points do not crash`() {
        assertTrue(greatCircleLines(LatLon(10.0, 10.0), LatLon(10.0, 10.0)).isNotEmpty())
    }
}
