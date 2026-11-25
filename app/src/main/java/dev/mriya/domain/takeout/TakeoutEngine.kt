package dev.mriya.domain.takeout

import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.Point
import dev.mriya.data.TakeoutData
import dev.mriya.data.stableKey
import dev.mriya.domain.FlightDetector
import dev.mriya.domain.haversineKm
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Turns the positions found in a Takeout export into country stamps and flights, using the same detector as the
 * Dawarich sync. The points themselves are never kept: only the derived stamps and flights are.
 *
 * Lookups are injected so the logic runs (and is tested) without Android: [isoAt] maps a position to an ISO-2 country,
 * [airportNear] to an airport code, [airportCountry] to the country of the nearest airport.
 */
class TakeoutEngine(
    private val isoAt: (Double, Double) -> String?,
    private val airportNear: (LatLon) -> String?,
    private val airportCountry: (LatLon) -> String?,
) : TakeoutSink {
    private val stamps = HashMap<String, CountryStamp>()
    private val flights = LinkedHashMap<String, Flight>()
    var points = 0L
        private set
    private var detector = FlightDetector(null, airportNear)

    /** Call before each file: files are processed independently, so a flight is never glued across two files. */
    fun beginFile() { detector = FlightDetector(null, airportNear) }

    fun endFile() { detector.finish().forEach(::addFlight) }

    override fun point(ts: Long, lat: Double, lon: Double, altitude: Double?) {
        points++
        detector.feed(Point(latitude = lat, longitude = lon, timestamp = ts, altitude = altitude))
        detector.drain().forEach(::addFlight)
        isoAt(lat, lon)?.let { stamp(it, ts) }
    }

    override fun flight(startTs: Long, endTs: Long, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        val a = LatLon(fromLat, fromLon); val b = LatLon(toLat, toLon)
        addFlight(Flight(a, b, startTs, endTs, haversineKm(a, b)))
    }

    private fun addFlight(f: Flight) {
        if (f.distanceKm < FlightDetector.MIN_FLIGHT_KM) return
        val g = f.copy(fromAirport = f.fromAirport ?: airportNear(f.from), toAirport = f.toAirport ?: airportNear(f.to), source = "takeout")
        if (flights.putIfAbsent(g.stableKey(), g) == null) {
            // Landing in a country proves you were there, even with no points in it (phone off on the layover).
            airportCountry(g.from)?.let { stamp(it, g.departTs) }
            airportCountry(g.to)?.let { stamp(it, g.arriveTs) }
        }
    }

    private fun stamp(iso: String, ts: Long) {
        val day = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val cur = stamps[iso]
        stamps[iso] = if (cur == null) CountryStamp(Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH), iso, ts, ts, setOf(day), emptySet())
        else cur.copy(firstTs = minOf(cur.firstTs, ts), lastTs = maxOf(cur.lastTs, ts), days = cur.days + day)
    }

    fun result(importedAt: Long) = TakeoutData(stamps.toMap(), flights.values.toList(), points, importedAt)
}
