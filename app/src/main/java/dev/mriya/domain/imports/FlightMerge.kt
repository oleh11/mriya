package dev.mriya.domain.imports

import dev.mriya.data.Flight
import kotlin.math.abs

/**
 * Combines flights detected from Dawarich with flights imported from mail, calendar or boarding passes.
 * An import that matches a detected flight (same airports, within ~40 h: scheduled vs. actual times) enriches it with
 * airline and flight number instead of being counted twice; an unmatched import is added as its own flight.
 */
fun mergeFlights(detected: List<Flight>, imported: List<Flight>): List<Flight> {
    val result = detected.toMutableList()
    val used = HashSet<Int>()
    for (imp in imported) {
        val i = result.indices.firstOrNull { idx ->
            idx !in used && result[idx].source != "imported" && sameRoute(result[idx], imp) &&
                abs(result[idx].departTs - imp.departTs) < 40 * 3600
        }
        if (i != null) {
            used += i
            result[i] = result[i].copy(
                airline = imp.airline ?: result[i].airline,
                flightNumber = imp.flightNumber ?: result[i].flightNumber,
                source = "merged",
                importOrigin = imp.importOrigin ?: result[i].importOrigin,
            )
        } else if (result.none { it.id == imp.id && it.fromAirport == imp.fromAirport && it.toAirport == imp.toAirport }) {
            result += imp
        }
    }
    return result
}

private fun sameRoute(a: Flight, b: Flight) =
    a.fromAirport != null && a.fromAirport == b.fromAirport && a.toAirport != null && a.toAirport == b.toAirport
