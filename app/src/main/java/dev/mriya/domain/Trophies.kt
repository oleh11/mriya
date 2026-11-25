package dev.mriya.domain

import dev.mriya.data.PassportData
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

enum class Tier { BRONZE, SILVER, GOLD }

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val tier: Tier,
    val target: Int,
    val progress: Int,
    val group: String,
) {
    val unlocked get() = progress >= target
    val fraction get() = (progress.toFloat() / target).coerceIn(0f, 1f)
}

/** Everything the trophies are computed from. Derived only from visible (not removed) stamps and flights. */
data class TravelStats(
    val countries: Int,
    val continents: Int,
    val flights: Int,
    val totalKm: Double,
    val longestKm: Double,
    val airports: Int,
    val bestYearFlights: Int,
    val crossedEquator: Boolean,
    val crossedDateLine: Boolean,
    val southern: Boolean,
    val arctic: Boolean,
)

data class Rank(val title: String, val minXp: Int)

/**
 * Trophies, XP and ranks. Pure and deterministic, so they can be recomputed from the passport at any time and
 * never need to be stored; only "which trophies has the user already seen" is saved.
 */
object Trophies {
    /** The top rank is named after the Antonov An-225 Mriya, the largest aircraft ever built. */
    val ranks = listOf(
        Rank("Dreamer", 0), Rank("Wanderer", 150), Rank("Navigator", 600), Rank("Captain", 1500),
        Rank("Ace", 3500), Rank("Legend", 8000), Rank("Mriya", 15000),
    )

    fun stats(d: PassportData, continentOf: (String) -> String?): TravelStats {
        val fl = d.flights.filter { !it.dismissed }
        val perYear = fl.groupingBy { Instant.ofEpochSecond(it.departTs).atZone(ZoneOffset.UTC).year }.eachCount()
        val ends = fl.flatMap { listOf(it.from, it.to) }
        return TravelStats(
            countries = d.stamps.size,
            continents = d.stamps.values.mapNotNull { s -> s.iso2?.let(continentOf) }.toSet().size,
            flights = fl.size,
            totalKm = fl.sumOf { it.distanceKm },
            longestKm = fl.maxOfOrNull { it.distanceKm } ?: 0.0,
            airports = fl.flatMap { listOfNotNull(it.fromAirport, it.toAirport) }.toSet().size,
            bestYearFlights = perYear.values.maxOrNull() ?: 0,
            crossedEquator = fl.any { it.from.lat * it.to.lat < 0 },
            crossedDateLine = fl.any { greatCircleLines(it.from, it.to, 24).size > 1 },
            southern = ends.any { it.lat < 0 },
            arctic = ends.any { abs(it.lat) > 66.5 },
        )
    }

    fun xp(s: TravelStats): Int = s.countries * 100 + s.continents * 150 + s.flights * 10 + (s.totalKm / 1000).toInt() * 5

    fun rank(xp: Int): Rank = ranks.last { xp >= it.minXp }
    fun nextRank(xp: Int): Rank? = ranks.firstOrNull { it.minXp > xp }

    fun achievements(s: TravelStats): List<Achievement> = buildList {
        fun a(id: String, title: String, desc: String, tier: Tier, target: Int, progress: Int, group: String) =
            add(Achievement(id, title, desc, tier, target, progress, group))
        val c = s.countries
        a("countries_1", "First stamp", "Collect your first country stamp", Tier.BRONZE, 1, c, "Countries")
        a("countries_5", "Pages filling up", "Visit 5 countries", Tier.BRONZE, 5, c, "Countries")
        a("countries_10", "Double digits", "Visit 10 countries", Tier.SILVER, 10, c, "Countries")
        a("countries_20", "Twenty stamps", "Visit 20 countries", Tier.SILVER, 20, c, "Countries")
        a("countries_35", "Seasoned traveler", "Visit 35 countries", Tier.GOLD, 35, c, "Countries")
        a("countries_50", "Fifty nations", "Visit 50 countries", Tier.GOLD, 50, c, "Countries")
        a("countries_100", "Century of countries", "Visit 100 countries", Tier.GOLD, 100, c, "Countries")
        val k = s.continents
        a("continents_3", "Three continents", "Visit 3 continents", Tier.BRONZE, 3, k, "Continents")
        a("continents_5", "Five continents", "Visit 5 continents", Tier.SILVER, 5, k, "Continents")
        a("continents_6", "Almost everywhere", "Visit 6 continents", Tier.GOLD, 6, k, "Continents")
        a("continents_7", "All seven", "Visit every continent", Tier.GOLD, 7, k, "Continents")
        val f = s.flights
        a("flights_1", "Wheels up", "Take your first flight", Tier.BRONZE, 1, f, "Flights")
        a("flights_10", "Frequent flyer", "Take 10 flights", Tier.BRONZE, 10, f, "Flights")
        a("flights_25", "Sky regular", "Take 25 flights", Tier.SILVER, 25, f, "Flights")
        a("flights_50", "Half a hundred", "Take 50 flights", Tier.SILVER, 50, f, "Flights")
        a("flights_100", "Century club", "Take 100 flights", Tier.GOLD, 100, f, "Flights")
        a("flights_250", "Always in transit", "Take 250 flights", Tier.GOLD, 250, f, "Flights")
        val km = s.totalKm.toInt()
        a("km_10000", "Long haul", "Fly 10,000 km in total", Tier.BRONZE, 10_000, km, "Distance")
        a("km_40075", "Around the world", "Fly 40,075 km, once around the equator", Tier.SILVER, 40_075, km, "Distance")
        a("km_100000", "Two and a half laps", "Fly 100,000 km in total", Tier.SILVER, 100_000, km, "Distance")
        a("km_384400", "To the Moon", "Fly 384,400 km, the distance to the Moon", Tier.GOLD, 384_400, km, "Distance")
        a("km_768800", "Moon and back", "Fly 768,800 km, a round trip to the Moon", Tier.GOLD, 768_800, km, "Distance")
        val longest = s.longestKm.toInt()
        a("longest_5000", "Ocean crosser", "Take a flight of 5,000 km or more", Tier.BRONZE, 5_000, longest, "Records")
        a("longest_10000", "Ultra long haul", "Take a flight of 10,000 km or more", Tier.SILVER, 10_000, longest, "Records")
        a("longest_14000", "Edge of the map", "Take a flight of 14,000 km or more", Tier.GOLD, 14_000, longest, "Records")
        a("airports_10", "Airport hopper", "Pass through 10 airports", Tier.BRONZE, 10, s.airports, "Airports")
        a("airports_25", "Terminal collector", "Pass through 25 airports", Tier.SILVER, 25, s.airports, "Airports")
        a("airports_50", "Gate master", "Pass through 50 airports", Tier.GOLD, 50, s.airports, "Airports")
        a("year_10", "Busy year", "Take 10 flights in one year", Tier.BRONZE, 10, s.bestYearFlights, "Records")
        a("year_25", "Jet-setter year", "Take 25 flights in one year", Tier.SILVER, 25, s.bestYearFlights, "Records")
        a("year_50", "Nonstop year", "Take 50 flights in one year", Tier.GOLD, 50, s.bestYearFlights, "Records")
        a("equator", "Crossed the equator", "Fly from one hemisphere to the other", Tier.SILVER, 1, if (s.crossedEquator) 1 else 0, "Firsts")
        a("southern", "Southern skies", "Fly to or from the southern hemisphere", Tier.BRONZE, 1, if (s.southern) 1 else 0, "Firsts")
        a("dateline", "Chased the sun", "Fly across the International Date Line", Tier.SILVER, 1, if (s.crossedDateLine) 1 else 0, "Firsts")
        a("arctic", "Polar flyer", "Fly to or from north of the Arctic Circle", Tier.GOLD, 1, if (s.arctic) 1 else 0, "Firsts")
    }
}
