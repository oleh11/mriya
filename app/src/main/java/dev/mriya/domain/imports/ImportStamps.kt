package dev.mriya.domain.imports

import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.stableKey
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Adds or updates country stamps for the departure and arrival airports of non-dismissed imported [flights].
 * Existing stamps keep their recorded cities and expand their date range/day set.
 */
fun stampFlightCountries(
    existing: Map<String, CountryStamp>,
    flights: List<Flight>,
    dismissedKeys: Set<String> = emptySet(),
    countryForIata: (String) -> String?,
): Map<String, CountryStamp> {
    if (flights.isEmpty()) return existing
    val out = existing.toMutableMap()
    for (f in flights) {
        if (f.dismissed || f.stableKey() in dismissedKeys) continue
        listOf(f.fromAirport to f.departTs, f.toAirport to f.arriveTs).forEach { (iata, ts) ->
            val code = iata?.takeIf { it.isNotBlank() } ?: return@forEach
            val iso = countryForIata(code)?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 } ?: return@forEach
            val day = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val cur = out[iso]
            out[iso] = if (cur == null) {
                val name = Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH)
                CountryStamp(name, iso, ts, ts, setOf(day), emptySet())
            } else {
                cur.copy(
                    firstTs = minOf(cur.firstTs, ts),
                    lastTs = maxOf(cur.lastTs, ts),
                    days = cur.days + day,
                )
            }
        }
    }
    return out
}
