package dev.mriya.domain.imports

import dev.mriya.data.Flight
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Some bookings name only one airport per flight ("Arrival to Los Cabos (SJD) ... Flight AA 1649"). The reader keeps
 * such a leg as `source = "partial"` with the other end blank. Here each one is completed from a flight already in the
 * passport (usually detected from the location history) that touches the same airport within a day of that date.
 * A leg with no such flight, or with several different candidates, is dropped rather than guessed.
 *
 * A completed leg is marked "email-resolved": the airline and flight number come from the booking and the route is
 * confirmed by the existing flight, so it is not treated as an unverified text match.
 */
fun completePartialLegs(parsed: List<ParsedFlight>, existing: List<Flight>, zone: ZoneId = ZoneId.systemDefault()): List<ParsedFlight> {
    val (partial, whole) = parsed.partition { it.source == "partial" }
    if (partial.isEmpty()) return parsed
    val completed = partial.mapNotNull { p ->
        val needsFrom = p.from.isEmpty()
        val known = if (needsFrom) p.to else p.from
        val others = existing.asSequence()
            .filter { !it.dismissed && (if (needsFrom) it.toAirport else it.fromAirport) == known }
            .filter { abs(ChronoUnit.DAYS.between(Instant.ofEpochSecond(it.departTs).atZone(zone).toLocalDate(), p.date)) <= 1 }
            .mapNotNull { if (needsFrom) it.fromAirport else it.toAirport }
            .distinct().toList()
        others.singleOrNull()?.let { other ->
            if (needsFrom) p.copy(from = other, source = "email-resolved") else p.copy(to = other, source = "email-resolved")
        }
    }
    return whole + completed
}
