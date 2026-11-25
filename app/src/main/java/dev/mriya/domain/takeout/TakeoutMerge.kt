package dev.mriya.domain.takeout

import dev.mriya.data.Flight
import dev.mriya.data.PassportData
import dev.mriya.data.TakeoutData
import kotlin.math.abs

/**
 * Adds what a Takeout import found on top of the Dawarich data: stamps are merged by country (days united), and a
 * Takeout flight is added only if no flight with the same airports exists within about 40 hours.
 */
fun mergeTakeout(d: PassportData, t: TakeoutData): PassportData {
    if (t.stamps.isEmpty() && t.flights.isEmpty()) return d
    val stamps = d.stamps.toMutableMap()
    t.stamps.forEach { (k, s) ->
        stamps[k] = stamps[k]?.let { a ->
            a.copy(firstTs = minOf(a.firstTs, s.firstTs), lastTs = maxOf(a.lastTs, s.lastTs), days = a.days + s.days, cities = a.cities + s.cities)
        } ?: s
    }
    val flights = d.flights.toMutableList()
    for (tf in t.flights) if (flights.none { same(it, tf) }) flights += tf
    return d.copy(stamps = stamps, flights = flights)
}

private fun same(a: Flight, b: Flight): Boolean =
    abs(a.departTs - b.departTs) < 40 * 3600 && a.fromAirport != null && a.fromAirport == b.fromAirport && a.toAirport == b.toAirport
