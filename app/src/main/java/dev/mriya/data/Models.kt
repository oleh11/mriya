package dev.mriya.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale
import kotlinx.serialization.json.JsonNames

/** A location point as returned by Dawarich GET /api/v1/points (subset of fields). */
@Serializable
data class Point(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long, // unix seconds
    val altitude: Double? = null,
    val city: String? = null,
    val country: String? = null,
)

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class Flight(
    val from: LatLon,
    val to: LatLon,
    val departTs: Long,
    val arriveTs: Long,
    val distanceKm: Double,
    val fromAirport: String? = null, // IATA
    val toAirport: String? = null,
    val dismissed: Boolean = false,
    val airline: String? = null,      // IATA airline code, from imports or user edits
    val flightNumber: String? = null, // flight digits, e.g. "123"
    val source: String = "detected",  // detected (from Dawarich) | imported | merged (both) | takeout
    val importOrigin: String? = null, // google-calendar | calendar | screenshot | boarding-pass | ics | email | manual
    val aircraftType: String? = null,
    val callsign: String? = null,
    val registration: String? = null,
    val status: String? = null,
) {
    /** Stable identity: airports are filled in lazily, so never compare whole objects. */
    val id: String get() = "${departTs}_$arriveTs"
}

/** Passport entry for one country, aggregated from points. Keyed by ISO-2 when known. */
@Serializable
data class CountryStamp(
    val name: String,
    val iso2: String?,
    val firstTs: Long,
    val lastTs: Long,
    val days: Set<String>, // yyyy-MM-dd (UTC) days with at least one point
    val cities: Set<String>,
) {
    val dayCount get() = days.size
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class YearlyStat(
    val year: Int,
    @JsonNames("total_distance_km") val totalDistanceKm: Double = 0.0,
    @JsonNames("total_countries_visited") val totalCountriesVisited: Int = 0,
    @JsonNames("total_cities_visited") val totalCitiesVisited: Int = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DawarichStats(
    @JsonNames("total_distance_km") val totalDistanceKm: Double = 0.0,
    @JsonNames("total_points_tracked") val totalPointsTracked: Long = 0,
    @JsonNames("total_countries_visited") val totalCountriesVisited: Int = 0,
    @JsonNames("total_cities_visited") val totalCitiesVisited: Int = 0,
    @JsonNames("yearly_stats") val yearlyStats: List<YearlyStat> = emptyList(),
)

/** Everything persisted between launches. Bump [SCHEMA] when the stored shape changes; old data is then re-synced. */
@Serializable
data class PassportData(
    val schema: Int = SCHEMA,
    val lastSyncTs: Long = 0,
    val lastPoint: Point? = null, // carried over so incremental syncs can detect flights across the boundary
    val stamps: Map<String, CountryStamp> = emptyMap(),
    val flights: List<Flight> = emptyList(),
    val stats: DawarichStats? = null,
    // Filled in from the user's own choices when loading; never written into the rebuildable cache file.
    @Transient val hiddenCountries: Set<String> = emptySet(),
    @Transient val seenTrophies: Set<String> = emptySet(),
) {
    companion object {
        /** 3: airport tiers and normalised flight times; 4: airport countries stamped from flights. Bumping this makes old data re-sync. */
        const val SCHEMA = 4
    }
}

/**
 * User-supplied overrides for a flight keyed by [Flight.stableKey].
 * `null` preserves the source value; `""` clears a source value; non-blank strings override it.
 */
@Serializable
data class FlightEdit(
    val airline: String? = null,
    val flightNumber: String? = null,
    val aircraftType: String? = null,
    val callsign: String? = null,
    val registration: String? = null,
)

/**
 * Choices the user made. Kept apart from the cache, which "Clear cache and resync" wipes and re-derives:
 * these survive resyncs and re-detection.
 */
@Serializable
data class UserData(
    val dismissedFlights: Set<String> = emptySet(), // Flight.stableKey()
    val hiddenCountries: Set<String> = emptySet(),  // stamp keys (ISO-2)
    val seenTrophies: Set<String> = emptySet(),
    val flightEdits: Map<String, FlightEdit> = emptyMap(),
)

/** Stable across re-detection (timestamps move when detection is tuned): airports plus UTC day. */
fun Flight.stableKey(): String {
    fun p(c: LatLon) = String.format(Locale.ROOT, "%.1f,%.1f", c.lat, c.lon)
    return "${fromAirport ?: p(from)}-${toAirport ?: p(to)}-${departTs / 86_400}"
}

fun importOriginLabel(origin: String?): String? = when (origin) {
    "google-calendar" -> "Google Calendar"
    "calendar" -> "Calendar"
    "screenshot" -> "Screenshot"
    "boarding-pass" -> "Boarding pass"
    "ics" -> "ICS file"
    "email" -> "Email"
    "manual" -> "Manual"
    else -> null
}

fun Flight.originLabel(): String = when (source) {
    "takeout" -> "Google Takeout"
    "merged" -> importOriginLabel(importOrigin)?.let { "Dawarich + $it" } ?: "Dawarich + Matched"
    "imported" -> importOriginLabel(importOrigin) ?: "Imported"
    else -> "Dawarich"
}

/** What was derived from a Google Takeout import: countries and flights only. The points are not kept. */
@Serializable
data class TakeoutData(
    val stamps: Map<String, CountryStamp> = emptyMap(),
    val flights: List<Flight> = emptyList(),
    val points: Long = 0,
    val importedAt: Long = 0,
)

/** Optional passport holder identity fields stored encrypted on-device and never uploaded. */
@Serializable
data class HolderProfile(
    val givenNames: String = "",
    val surname: String = "",
    val nationality: String = "",
    val dateOfBirth: String = "",
    val placeOfBirth: String = "",
    val passportNumber: String = "",
) {
    val isPopulated: Boolean
        get() = givenNames.isNotBlank() || surname.isNotBlank() || nationality.isNotBlank() ||
            dateOfBirth.isNotBlank() || placeOfBirth.isNotBlank()
}
