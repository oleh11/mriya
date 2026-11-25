package dev.mriya

import dev.mriya.data.LatLon
import dev.mriya.domain.CountryShape
import dev.mriya.domain.RUSSIA_AIRSPACE_CLOSED_TS
import dev.mriya.domain.airspaceToAvoid
import dev.mriya.domain.cachedRouteAvoidingAirspace
import dev.mriya.domain.liftedRoutePoints
import dev.mriya.domain.routeAvoidingAirspace
import dev.mriya.domain.routeLinesAvoidingAirspace
import dev.mriya.domain.unitVec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class FlightRoutingTest {
    private fun loadRuShape(): CountryShape {
        val file = listOf(
            File("src/main/assets/countries.json"),
            File("app/src/main/assets/countries.json"),
        ).firstOrNull { it.exists() }
            ?: error("Could not locate countries.json from ${File(".").absolutePath}")
        val ruObj = Json.parseToJsonElement(file.readText()).jsonArray
            .map { it.jsonObject }
            .first { it["iso"]?.jsonPrimitive?.content == "RU" }
        return CountryShape(
            "RU",
            ruObj["rings"]!!.jsonArray.map { ring ->
                ring.jsonArray.map { pt ->
                    pt.jsonArray[0].jsonPrimitive.double.toFloat() to pt.jsonArray[1].jsonPrimitive.double.toFloat()
                }
            },
        )
    }

    @Test
    fun `transpolar and eurasian flights route completely outside russian airspace`() {
        val ru = loadRuShape()
        val routes = listOf(
            "ORD->TPE" to (LatLon(41.9786, -87.9048) to LatLon(25.0777, 121.2328)),
            "ORD->HAN" to (LatLon(41.9786, -87.9048) to LatLon(21.2212, 105.8070)),
            "LHR->HND" to (LatLon(51.4700, -0.4543) to LatLon(35.5494, 139.7798)),
            "WAW->NRT" to (LatLon(52.1657, 20.9671) to LatLon(35.7720, 140.3929)),
            "FRA->ICN" to (LatLon(50.0379, 8.5622) to LatLon(37.4602, 126.4407)),
        )
        for ((name, endpoints) in routes) {
            val (from, to) = endpoints
            val waypoints = routeAvoidingAirspace(from, to, ru)
            assertTrue("$name should detour around RU (waypoints=${waypoints.size})", waypoints.size > 2)

            val lines = routeLinesAvoidingAirspace(from, to, ru, segmentsPerLeg = 24)
            assertTrue("$name should produce lines", lines.isNotEmpty())
            for (line in lines) {
                for (pt in line) {
                    assertFalse(
                        "$name entered RU at lon=${pt[0]}, lat=${pt[1]}",
                        ru.contains(pt[0].toFloat(), pt[1].toFloat()),
                    )
                }
                assertTrue(
                    "$name line should split at antimeridian cleanly",
                    line.zipWithNext().all { (p, q) -> abs(p[0] - q[0]) < 180.0 },
                )
            }
        }
    }

    @Test
    fun `direct routes not crossing russia keep fast two-point path`() {
        val ru = loadRuShape()
        val clearRoutes = listOf(
            LatLon(41.9786, -87.9048) to LatLon(37.6189, -122.3750), // ORD -> SFO
            LatLon(41.9786, -87.9048) to LatLon(51.4700, -0.4543),   // ORD -> LHR
            LatLon(23.1519, -109.7207) to LatLon(41.9786, -87.9048), // SJD -> ORD
        )
        for ((from, to) in clearRoutes) {
            assertEquals(listOf(from, to), routeAvoidingAirspace(from, to, ru))
        }
    }

    @Test
    fun `liftedRoutePoints begins at from, ends at to, and lifts smoothly above sphere`() {
        val ru = loadRuShape()
        val from = LatLon(41.9786, -87.9048)
        val to = LatLon(25.0777, 121.2328)
        val waypoints = routeAvoidingAirspace(from, to, ru)
        val pts = liftedRoutePoints(waypoints, totalSegments = 64)

        assertTrue(pts.size >= 9)
        assertEquals(0, pts.size % 3)

        val vStart = unitVec(from.lat, from.lon)
        val vEnd = unitVec(to.lat, to.lon)
        assertEquals(vStart[0], pts[0], 1e-3f)
        assertEquals(vStart[1], pts[1], 1e-3f)
        assertEquals(vStart[2], pts[2], 1e-3f)
        assertEquals(vEnd[0], pts[pts.size - 3], 1e-3f)
        assertEquals(vEnd[1], pts[pts.size - 2], 1e-3f)
        assertEquals(vEnd[2], pts[pts.size - 1], 1e-3f)

        val midIdx = (pts.size / 6) * 3
        val midRadius = sqrt(
            pts[midIdx] * pts[midIdx] +
                pts[midIdx + 1] * pts[midIdx + 1] +
                pts[midIdx + 2] * pts[midIdx + 2]
        )
        assertTrue("Midpoint should lift above unit sphere ($midRadius)", midRadius > 1.04f)
    }

    @Test
    fun `only flights after 24 February 2022 are detoured`() {
        val ru = loadRuShape()
        assertEquals(null, airspaceToAvoid(RUSSIA_AIRSPACE_CLOSED_TS - 1, ru))
        assertEquals(ru, airspaceToAvoid(RUSSIA_AIRSPACE_CLOSED_TS, ru))
        val ord = LatLon(41.9786, -87.9048)
        val szx = LatLon(22.6393, 113.8107)
        // A 2015 flight is drawn the way it was flown (direct); a 2023 flight is drawn around Russia.
        assertEquals(listOf(ord, szx), cachedRouteAvoidingAirspace(ord, szx, airspaceToAvoid(1_435_000_000L, ru)))
        assertTrue(cachedRouteAvoidingAirspace(ord, szx, airspaceToAvoid(1_700_000_000L, ru)).size > 2)
    }

    @Test
    fun `cached routes equal computed routes and are shared between repeated flights`() {
        val ru = loadRuShape()
        val a = LatLon(41.9786, -87.9048)
        val b = LatLon(25.0777, 121.2328)
        val first = cachedRouteAvoidingAirspace(a, b, ru)
        assertEquals(routeAvoidingAirspace(a, b, ru), first)
        assertTrue("a repeat must come from the cache", first === cachedRouteAvoidingAirspace(a, b, ru))
    }

    @Test
    fun `a wide matrix of Europe and North America to East Asia pairs never enters Russia`() {
        val ru = loadRuShape()
        val p = mapOf(
            "HEL" to LatLon(60.3172, 24.9633), "ARN" to LatLon(59.6519, 17.9186), "CPH" to LatLon(55.6181, 12.6561),
            "AMS" to LatLon(52.3105, 4.7683), "CDG" to LatLon(49.0097, 2.5479), "FRA" to LatLon(50.0379, 8.5622),
            "MUC" to LatLon(48.3538, 11.7861), "VIE" to LatLon(48.1103, 16.5697), "WAW" to LatLon(52.1657, 20.9671),
            "IST" to LatLon(41.2753, 28.7519), "LHR" to LatLon(51.4700, -0.4543), "JFK" to LatLon(40.6413, -73.7781),
            "EWR" to LatLon(40.6895, -74.1745), "BOS" to LatLon(42.3656, -71.0096), "YYZ" to LatLon(43.6777, -79.6248),
            "SEA" to LatLon(47.4502, -122.3088), "LAX" to LatLon(33.9416, -118.4085), "ORD" to LatLon(41.9786, -87.9048),
            "PEK" to LatLon(40.0799, 116.6031), "PVG" to LatLon(31.1443, 121.8083), "HKG" to LatLon(22.3080, 113.9185),
            "ICN" to LatLon(37.4602, 126.4407), "NRT" to LatLon(35.7720, 140.3929), "HND" to LatLon(35.5494, 139.7798),
            "TPE" to LatLon(25.0777, 121.2328), "KIX" to LatLon(34.4320, 135.2304),
        )
        val pairs = listOf(
            "HEL" to "ICN", "HEL" to "NRT", "HEL" to "PEK", "ARN" to "PEK", "ARN" to "HND", "CPH" to "PVG", "AMS" to "PVG",
            "CDG" to "ICN", "FRA" to "PEK", "FRA" to "HKG", "MUC" to "TPE", "VIE" to "HKG", "WAW" to "ICN", "WAW" to "PEK",
            "IST" to "PEK", "LHR" to "PEK", "LHR" to "KIX", "JFK" to "PEK", "JFK" to "PVG", "EWR" to "HKG", "BOS" to "NRT",
            "YYZ" to "HKG", "YYZ" to "ICN", "SEA" to "ICN", "LAX" to "PEK", "ORD" to "PVG", "ORD" to "ICN", "ORD" to "NRT",
        )
        val unroutable = mutableListOf<String>()
        for ((a, b) in pairs) {
            for ((from, to) in listOf(p.getValue(a) to p.getValue(b), p.getValue(b) to p.getValue(a))) {
                val lines = routeLinesAvoidingAirspace(from, to, ru, segmentsPerLeg = 24)
                if (lines.any { l -> l.any { pt -> ru.contains(pt[0].toFloat(), pt[1].toFloat()) } }) unroutable += "$a-$b"
            }
        }
        assertTrue("pairs that still cross Russia (no corridor found): ${unroutable.distinct()}", unroutable.isEmpty())
    }
}
