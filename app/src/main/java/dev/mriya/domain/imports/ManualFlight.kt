package dev.mriya.domain.imports

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.originLabel
import dev.mriya.domain.Airport
import dev.mriya.domain.haversineKm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** Black Sea sinking coordinates of the Russian cruiser Moskva (45°10'43.39"N, 30°55'30.54"E). */
val MOSKVA_WRECK = LatLon(45.1787, 30.9252)
const val MOSKVA_MESSAGE = "Russian Warship, Go F**** Yourself"

fun isNearMoskvaWreck(p: LatLon): Boolean =
    abs(p.lat - MOSKVA_WRECK.lat) < 0.02 && abs(p.lon - MOSKVA_WRECK.lon) < 0.02

sealed class ManualFlightResult {
    data class Ok(val flight: Flight) : ManualFlightResult()
    data class Error(val message: String) : ManualFlightResult()
}

object ManualFlightInput {
    fun build(
        fromCode: String,
        toCode: String,
        dateIso: String,
        airline: String = "",
        flightNumber: String = "",
        aircraftType: String = "",
        callsign: String = "",
        registration: String = "",
        status: String = "",
        departTsOverride: Long? = null,
        arriveTsOverride: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        lookup: (String) -> Airport?,
    ): ManualFlightResult {
        val from = fromCode.trim().uppercase(Locale.ROOT)
        val to = toCode.trim().uppercase(Locale.ROOT)
        if (!isValidIata(from)) return ManualFlightResult.Error("Pick a departure airport: type a city or a 3-letter code like ORD, then choose it from the list.")
        if (!isValidIata(to)) return ManualFlightResult.Error("Pick an arrival airport: type a city or a 3-letter code like SFO, then choose it from the list.")
        if (from == to) return ManualFlightResult.Error("Departure and arrival airports must be different.")

        val a = lookup(from) ?: return ManualFlightResult.Error("Unknown departure airport: $from")
        val b = lookup(to) ?: return ManualFlightResult.Error("Unknown arrival airport: $to")

        val date = try {
            LocalDate.parse(dateIso.trim())
        } catch (_: DateTimeParseException) {
            return ManualFlightResult.Error("Enter date as YYYY-MM-DD (e.g. 2026-03-12).")
        }

        val depLatLon = LatLon(a.lat, a.lon)
        val arrLatLon = LatLon(b.lat, b.lon)
        val dist = haversineKm(depLatLon, arrLatLon)
        val departTs = departTsOverride ?: date.atTime(12, 0).atZone(zone).toEpochSecond()
        val arriveTs = arriveTsOverride?.takeIf { it > departTs } ?: (departTs + (dist / 750.0 * 3600).toLong() + 1800)
        val cleanAirline = airline.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() }
        val cleanNum = flightNumber.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() }

        return ManualFlightResult.Ok(
            Flight(
                from = depLatLon,
                to = arrLatLon,
                departTs = departTs,
                arriveTs = arriveTs,
                distanceKm = dist,
                fromAirport = from,
                toAirport = to,
                airline = cleanAirline,
                flightNumber = cleanNum,
                source = "imported",
                importOrigin = "manual",
                aircraftType = aircraftType.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() },
                callsign = callsign.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() },
                registration = registration.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() },
                status = status.trim().takeIf { it.isNotEmpty() },
            )
        )
    }

    private fun isValidIata(code: String): Boolean =
        code.length == 3 && code.all { it in 'A'..'Z' }
}

/** Filters flights by visibility, optional year, and free-text query across airports, airline, flight #, and year. */
fun filterFlights(
    flights: List<Flight>,
    showHidden: Boolean,
    selectedYear: Int?,
    query: String,
    zone: ZoneId = ZoneId.systemDefault(),
    /** City and airport name for an IATA code ("Chicago O'Hare International Airport"), so cities can be searched. */
    placeText: (String) -> String = { "" },
): List<Flight> {
    val q = query.trim().lowercase(Locale.ROOT)
    return flights.filter { f ->
        if (!showHidden && f.dismissed) return@filter false
        val y = Instant.ofEpochSecond(f.departTs).atZone(zone).year
        if (selectedYear != null && y != selectedYear) return@filter false
        if (q.isEmpty()) return@filter true

        val from = f.fromAirport.orEmpty()
        val to = f.toAirport.orEmpty()
        val al = f.airline.orEmpty()
        val num = f.flightNumber.orEmpty()
        listOf(from, to, "$from $to", "$from->$to", al, num, "$al $num", y.toString(), f.originLabel(), placeText(from), placeText(to))
            .any { it.lowercase(Locale.ROOT).contains(q) }
    }
}
