package dev.mriya.domain.imports

import dev.mriya.data.Flight
import kotlin.math.abs

/**
 * Candidate presented in the review sheet before importing into the user's passport.
 * [restoreId]: the same flight exists in dismissed state; confirming brings that one back.
 * [unverified]: true for heuristic plain-text matches (unchecked by default so the user checks route & date).
 */
data class ImportCandidate(
    val flight: Flight,
    val duplicate: Boolean,
    val selected: Boolean,
    val restoreId: String? = null,
    val unverified: Boolean = false,
    val enrichesExisting: Boolean = false,
)

data class ConvertedFlight(
    val flight: Flight,
    val unverified: Boolean,
)

/**
 * Builds deduplicated, reviewable candidates from converted flights against [existing] passport flights.
 * Structured imports (boarding pass, calendar, .ics, JSON-LD) are pre-selected unless already present;
 * imports that enrich an untagged GPS-detected flight with airline/flight # are also selectable;
 * heuristic plain-text matches are marked [ImportCandidate.unverified] and left unchecked by default.
 */
fun buildImportCandidates(
    converted: List<ConvertedFlight>,
    existing: List<Flight>,
): List<ImportCandidate> {
    val same = { e: Flight, f: Flight ->
        e.fromAirport == f.fromAirport && e.toAirport == f.toAirport && abs(e.departTs - f.departTs) < 40 * 3600
    }
    return converted.sortedBy { it.flight.departTs }.map { item ->
        val f = item.flight
        val existingMatch = existing.firstOrNull { !it.dismissed && same(it, f) }
        val addsDetails = existingMatch != null && (
            (existingMatch.airline == null && f.airline != null) ||
                (existingMatch.flightNumber == null && f.flightNumber != null)
            )
        val dup = existingMatch != null && !addsDetails
        val hidden = if (existingMatch != null) null else existing.firstOrNull { it.dismissed && same(it, f) }
        ImportCandidate(
            flight = f,
            duplicate = dup,
            selected = (!dup && !item.unverified),
            restoreId = hidden?.id,
            unverified = item.unverified,
            enrichesExisting = addsDetails,
        )
    }
}
