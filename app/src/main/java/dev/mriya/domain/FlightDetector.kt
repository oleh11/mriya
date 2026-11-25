package dev.mriya.domain

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.Point
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun haversineKm(a: LatLon, b: LatLon): Double {
    val r = 6371.0088
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

/**
 * Streaming flight detector. Feed points in ascending time order.
 *
 * Phones usually go quiet in flight (airplane mode), so most flights show up as ONE long jump between
 * the last point at the departure airport and the first at the arrival airport. Each consecutive-point
 * "leg" is classified as a flight leg when:
 *  - distance >= MIN_LEG_KM and implied speed in [FAST_KMH, MAX_KMH], or
 *  - implied speed >= SLOW_KMH with either end altitude >= HIGH_ALT_M, or
 *  - both ends are near two DIFFERENT airports ([airportNear]) and speed >= AIRPORT_KMH. Door-to-door
 *    time on a short hop (airport dwell with the phone off) is slow, so airports let us accept it,
 *    while requiring airports at both ends also rejects fast trains and cars.
 * Single-point GPS/cell "teleports" (a point far away that snaps straight back) are discarded.
 * Adjacent flight legs within MERGE_GAP_S are merged.
 */
class FlightDetector(
    initialPrev: Point? = null,
    private val airportNear: (LatLon) -> String? = { null },
) {
    companion object {
        const val MIN_LEG_KM = 100.0
        const val MIN_FLIGHT_KM = 150.0
        const val FAST_KMH = 250.0
        const val SLOW_KMH = 150.0
        const val AIRPORT_KMH = 120.0
        const val MAX_KMH = 1300.0
        const val HIGH_ALT_M = 3000.0
        const val MERGE_GAP_S = 45 * 60L
        const val SPIKE_WINDOW_S = 60 * 60L
        const val LONG_LEG_KM = 800.0
        const val CRUISE_KMH = 750.0
    }

    private var prev: Point? = initialPrev
    private var prev2: Point? = null
    private var openFrom: Point? = null
    private var openTo: Point? = null
    private val done = mutableListOf<Flight>()

    val lastPoint: Point? get() = prev
    val hasOpenFlight: Boolean get() = openFrom != null

    private fun ll(p: Point) = LatLon(p.latitude, p.longitude)

    fun feed(p: Point) {
        var a = prev
        if (a != null) {
            val b = prev2
            // Spike: prev jumped far away from prev2 and p is back near prev2 within the window.
            if (b != null && p.timestamp - b.timestamp <= SPIKE_WINDOW_S) {
                val jump = haversineKm(ll(b), ll(a))
                if (jump >= MIN_LEG_KM && haversineKm(ll(b), ll(p)) < jump * 0.25) {
                    if (openTo === a) { if (openFrom === b) { openFrom = null; openTo = null } else openTo = b }
                    a = b // drop the glitch point
                }
            }
        }
        prev2 = a
        prev = p
        if (a == null) return
        classify(a, p)
    }

    private fun classify(a: Point, p: Point) {
        val dtS = p.timestamp - a.timestamp
        if (dtS <= 0) return
        val d = haversineKm(ll(a), ll(p))
        val speed = d / (dtS / 3600.0)
        val high = (a.altitude ?: 0.0) >= HIGH_ALT_M || (p.altitude ?: 0.0) >= HIGH_ALT_M
        var isFlightLeg = d >= MIN_LEG_KM && speed <= MAX_KMH &&
            (speed >= FAST_KMH || (speed >= SLOW_KMH && high))
        if (!isFlightLeg && d >= MIN_FLIGHT_KM && speed in AIRPORT_KMH..MAX_KMH) {
            val fa = airportNear(ll(a)); val fb = airportNear(ll(p))
            isFlightLeg = fa != null && fb != null && fa != fb
        }
        // Phone off for days around a long flight: implied speed is tiny, but a jump of 800+ km between two
        // different airports with nothing recorded in between can only be a flight. The true times are unknown,
        // so assume the phone reconnected on landing and back-date the departure by a cruise-speed duration.
        if (!isFlightLeg && d >= LONG_LEG_KM && speed < AIRPORT_KMH) {
            val fa = airportNear(ll(a)); val fb = airportNear(ll(p))
            if (fa != null && fb != null && fa != fb) {
                close()
                val depart = maxOf(a.timestamp, p.timestamp - (d / CRUISE_KMH * 3600).toLong())
                done += plausible(Flight(ll(a), ll(p), depart, p.timestamp, d))
                return
            }
        }

        if (isFlightLeg) {
            val to = openTo
            if (openFrom != null && to != null && a.timestamp - to.timestamp <= MERGE_GAP_S) openTo = p
            else { close(); openFrom = a; openTo = p }
        } else if (openFrom != null && a.timestamp - (openTo?.timestamp ?: 0) > MERGE_GAP_S) {
            close()
        }
    }

    /** Flushes the open flight and returns everything not yet drained. */
    fun finish(): List<Flight> { close(); return drain() }

    fun drain(): List<Flight> { val r = done.toList(); done.clear(); return r }

    private fun close() {
        val f = openFrom; val t = openTo
        openFrom = null; openTo = null
        if (f == null || t == null) return
        val dist = haversineKm(ll(f), ll(t))
        if (dist >= MIN_FLIGHT_KM) done += plausible(Flight(ll(f), ll(t), f.timestamp, t.timestamp, dist))
    }

    /**
     * When the phone was off, the gap between the last and first point includes airport dwell, which would count as
     * flight time (days, in the worst case). If the measured duration is far above a cruise-speed estimate, trust the
     * arrival (the phone reconnects on landing) and back-date the departure by the estimate instead.
     */
    private fun plausible(f: Flight): Flight {
        val est = (f.distanceKm / CRUISE_KMH * 3600).toLong() + 1800 // + taxi and climb
        return if (f.arriveTs - f.departTs > est * 14 / 10) f.copy(departTs = f.arriveTs - est) else f
    }
}
