package dev.mriya

import dev.mriya.domain.ProjectedArc
import dev.mriya.domain.hitTestArcs
import dev.mriya.domain.liftedArcPoints
import dev.mriya.domain.pointToSegmentDistSq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobeHitTest {
    private val r = 400f
    private val cx = 500f
    private val cy = 500f

    @Test
    fun `tap on front-facing arc returns its id`() {
        val arc = ProjectedArc("f1", liftedArcPoints(0.0, -20.0, 0.0, 20.0))
        // Looking straight at (lat0=0, lon0=0): midpoint of arc is at (x=cx, y=cy).
        val hit = hitTestArcs(cx, cy, listOf(arc), sl = 0f, cl = 1f, so = 0f, co = 1f, cx = cx, cy = cy, r = r, maxDistPx = 24f)
        assertEquals("f1", hit)
    }

    @Test
    fun `tap far away from arc returns null`() {
        val arc = ProjectedArc("f1", liftedArcPoints(0.0, -20.0, 0.0, 20.0))
        val hit = hitTestArcs(cx, cy - 180f, listOf(arc), sl = 0f, cl = 1f, so = 0f, co = 1f, cx = cx, cy = cy, r = r, maxDistPx = 24f)
        assertNull(hit)
    }

    @Test
    fun `arc on back side of globe is ignored`() {
        // Arc near lon 180 while camera faces lon 0.
        val backArc = ProjectedArc("back", liftedArcPoints(0.0, 165.0, 0.0, -165.0))
        val hit = hitTestArcs(cx, cy, listOf(backArc), sl = 0f, cl = 1f, so = 0f, co = 1f, cx = cx, cy = cy, r = r, maxDistPx = 60f)
        assertNull(hit)
    }

    @Test
    fun `nearest arc wins when two arcs are nearby`() {
        val north = ProjectedArc("north", liftedArcPoints(10.0, -20.0, 10.0, 20.0))
        val south = ProjectedArc("south", liftedArcPoints(-10.0, -20.0, -10.0, 20.0))
        // North arc midpoint has y = cy - sin(10 deg) * h * r ~ cy - 72.
        val hit = hitTestArcs(cx, cy - 68f, listOf(south, north), sl = 0f, cl = 1f, so = 0f, co = 1f, cx = cx, cy = cy, r = r, maxDistPx = 40f)
        assertEquals("north", hit)
    }

    @Test
    fun `segment distance handles degenerate zero-length segment`() {
        assertEquals(25f, pointToSegmentDistSq(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
    }
}
