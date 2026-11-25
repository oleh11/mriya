package dev.mriya

import dev.mriya.data.DawarichStats
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsJsonTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `camelCase stats payload`() {
        val s = json.decodeFromString(DawarichStats.serializer(),
            """{"totalDistanceKm":1234.5,"totalPointsTracked":10,"totalCountriesVisited":4,"totalCitiesVisited":12,"yearlyStats":[{"year":2024,"totalDistanceKm":800.2}]}""")
        assertEquals(1234.5, s.totalDistanceKm, 0.001); assertEquals(12, s.totalCitiesVisited); assertEquals(2024, s.yearlyStats[0].year)
    }

    @Test
    fun `snake_case stats payload is not silently zero`() {
        val s = json.decodeFromString(DawarichStats.serializer(),
            """{"total_distance_km":99.0,"total_points_tracked":7,"total_countries_visited":3,"total_cities_visited":5,"yearly_stats":[{"year":2023,"total_distance_km":50.0}]}""")
        assertEquals(99.0, s.totalDistanceKm, 0.001); assertEquals(5, s.totalCitiesVisited); assertEquals(50.0, s.yearlyStats[0].totalDistanceKm, 0.001)
    }
}
