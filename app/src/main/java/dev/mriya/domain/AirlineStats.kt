package dev.mriya.domain

import dev.mriya.data.Flight
import java.util.Locale

enum class AirlineRankBy { FLIGHTS, DISTANCE }

data class AirlineInfo(
    val code: String,
    val name: String,
    val accentHex: Long,
)

data class AirlineStat(
    val code: String,
    val name: String,
    val accentHex: Long,
    val flights: Int,
    val distanceKm: Double,
)

data class TopAirlinesSummary(
    val totalAirlines: Int,
    val taggedFlights: Int,
    val untaggedFlights: Int,
    val ranked: List<AirlineStat>,
)

/** Offline IATA airline directory with display names and livery accent colors. */
object AirlineDirectory {
    private val known: Map<String, Pair<String, Long>> = mapOf(
        // North America
        "AA" to ("American Airlines" to 0xFFC8102E),
        "AC" to ("Air Canada" to 0xFFD22630),
        "AM" to ("Aeromexico" to 0xFF0B2343),
        "AS" to ("Alaska Airlines" to 0xFF01426A),
        "B6" to ("JetBlue" to 0xFF003876),
        "DL" to ("Delta Air Lines" to 0xFFC01933),
        "F9" to ("Frontier Airlines" to 0xFF248168),
        "HA" to ("Hawaiian Airlines" to 0xFF413691),
        "NK" to ("Spirit Airlines" to 0xFFD9A404),
        "PD" to ("Porter Airlines" to 0xFF0F2B46),
        "UA" to ("United Airlines" to 0xFF005DAA),
        "WN" to ("Southwest Airlines" to 0xFF304CB2),
        "WS" to ("WestJet" to 0xFF007A78),
        // Europe & Ukraine
        "A3" to ("Aegean Airlines" to 0xFF004B87),
        "AF" to ("Air France" to 0xFF002157),
        "AY" to ("Finnair" to 0xFF0B1560),
        "AZ" to ("ITA Airways" to 0xFF003B73),
        "BA" to ("British Airways" to 0xFF075AAA),
        "BT" to ("airBaltic" to 0xFF6B9E1E),
        "DE" to ("Condor" to 0xFFE5A000),
        "EI" to ("Aer Lingus" to 0xFF006272),
        "EN" to ("Air Dolomiti" to 0xFF00847E),
        "EW" to ("Eurowings" to 0xFF96154F),
        "FI" to ("Icelandair" to 0xFF003366),
        "FR" to ("Ryanair" to 0xFF073590),
        "IB" to ("Iberia" to 0xFFD71920),
        "KL" to ("KLM" to 0xFF00A1DE),
        "LH" to ("Lufthansa" to 0xFF05164D),
        "LO" to ("LOT Polish Airlines" to 0xFF0B2B6B),
        "LX" to ("SWISS" to 0xFFC8102E),
        "OA" to ("Olympic Air" to 0xFF0072CE),
        "OK" to ("Czech Airlines" to 0xFFC8102E),
        "OS" to ("Austrian Airlines" to 0xFFE60000),
        "OU" to ("Croatia Airlines" to 0xFF004B87),
        "PC" to ("Pegasus Airlines" to 0xFFF2A900),
        "PQ" to ("SkyUp Airlines" to 0xFFFF6A13),
        "PS" to ("UIA" to 0xFF0057B7),
        "RO" to ("TAROM" to 0xFF003DA5),
        "SK" to ("SAS" to 0xFF00008B),
        "SN" to ("Brussels Airlines" to 0xFF003B79),
        "TK" to ("Turkish Airlines" to 0xFFC70A0C),
        "TP" to ("TAP Air Portugal" to 0xFF00843D),
        "U2" to ("easyJet" to 0xFFFF6600),
        "UX" to ("Air Europa" to 0xFF0072CE),
        "VS" to ("Virgin Atlantic" to 0xFFE10A0A),
        "VY" to ("Vueling" to 0xFFD9A404),
        "W6" to ("Wizz Air" to 0xFFC6007E),
        // Asia-Pacific
        "AI" to ("Air India" to 0xFFDA0E29),
        "BR" to ("EVA Air" to 0xFF006B3F),
        "CA" to ("Air China" to 0xFFD22630),
        "CI" to ("China Airlines" to 0xFF8C1D40),
        "CX" to ("Cathay Pacific" to 0xFF006564),
        "CZ" to ("China Southern" to 0xFF0088CE),
        "FM" to ("Shanghai Airlines" to 0xFFC8102E),
        "GA" to ("Garuda Indonesia" to 0xFF005C99),
        "HU" to ("Hainan Airlines" to 0xFFC8102E),
        "JL" to ("Japan Airlines" to 0xFFCC0000),
        "JQ" to ("Jetstar" to 0xFFFF5A00),
        "KE" to ("Korean Air" to 0xFF0064A4),
        "MF" to ("XiamenAir" to 0xFF005BAA),
        "MH" to ("Malaysia Airlines" to 0xFF00338D),
        "MU" to ("China Eastern" to 0xFFC8102E),
        "NH" to ("ANA" to 0xFF004595),
        "NZ" to ("Air New Zealand" to 0xFF1F2A34),
        "OZ" to ("Asiana Airlines" to 0xFF5D2D6E),
        "PR" to ("Philippine Airlines" to 0xFF0038A8),
        "QF" to ("Qantas" to 0xFFE40000),
        "SQ" to ("Singapore Airlines" to 0xFFF29F05),
        "TG" to ("Thai Airways" to 0xFF4F2170),
        "TR" to ("Scoot" to 0xFFE0B400),
        "VA" to ("Virgin Australia" to 0xFFC8102E),
        "VN" to ("Vietnam Airlines" to 0xFF005470),
        "ZH" to ("Shenzhen Airlines" to 0xFFC8102E),
        // Middle East, Africa & Latin America
        "AT" to ("Royal Air Maroc" to 0xFFB5121B),
        "AV" to ("Avianca" to 0xFFD91E2A),
        "CM" to ("Copa Airlines" to 0xFF005DAA),
        "EK" to ("Emirates" to 0xFFD71921),
        "ET" to ("Ethiopian Airlines" to 0xFF078930),
        "EY" to ("Etihad Airways" to 0xFFBD8B13),
        "FZ" to ("flydubai" to 0xFF0072CE),
        "G3" to ("GOL" to 0xFFFF6600),
        "GF" to ("Gulf Air" to 0xFF997A2B),
        "KQ" to ("Kenya Airways" to 0xFFC8102E),
        "LA" to ("LATAM" to 0xFF1B0088),
        "LY" to ("EL AL" to 0xFF0038A8),
        "MS" to ("EgyptAir" to 0xFF00205B),
        "QR" to ("Qatar Airways" to 0xFF5C0632),
        "RJ" to ("Royal Jordanian" to 0xFF7D1B22),
        "SA" to ("South African Airways" to 0xFF006747),
        "SV" to ("Saudia" to 0xFF006C35),
        "WY" to ("Oman Air" to 0xFF007398),
    )

    private val fallbackPalette = longArrayOf(
        0xFF5B3FD0,
        0xFF006564,
        0xFFC8102E,
        0xFF005DAA,
        0xFF006C35,
        0xFFB45309,
        0xFF4338CA,
        0xFF9D174D,
    )

    fun lookup(rawCode: String): AirlineInfo {
        val code = rawCode.trim().uppercase(Locale.ROOT)
        val knownEntry = known[code]
        if (knownEntry != null) {
            return AirlineInfo(code = code, name = knownEntry.first, accentHex = knownEntry.second)
        }
        val hash = code.fold(17) { acc, ch -> acc * 31 + ch.code }
        val color = fallbackPalette[kotlin.math.abs(hash) % fallbackPalette.size]
        return AirlineInfo(code = code, name = code, accentHex = color)
    }
}

private class AirlineAccumulator(var flights: Int = 0, var distanceKm: Double = 0.0)

/**
 * Aggregates non-dismissed [flights] by normalized IATA airline code and ranks them either by
 * flight count or total flown distance.
 */
fun computeTopAirlines(
    flights: List<Flight>,
    rankBy: AirlineRankBy = AirlineRankBy.DISTANCE,
): TopAirlinesSummary {
    val active = flights.filter { !it.dismissed }
    val buckets = HashMap<String, AirlineAccumulator>()
    var tagged = 0
    var untagged = 0

    for (f in active) {
        val code = f.airline?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (code == null) {
            untagged++
            continue
        }
        tagged++
        val acc = buckets.getOrPut(code) { AirlineAccumulator() }
        acc.flights++
        acc.distanceKm += f.distanceKm
    }

    val stats = buckets.entries.map { (code, acc) ->
        val info = AirlineDirectory.lookup(code)
        AirlineStat(
            code = info.code,
            name = info.name,
            accentHex = info.accentHex,
            flights = acc.flights,
            distanceKm = acc.distanceKm,
        )
    }

    val comparator = when (rankBy) {
        AirlineRankBy.DISTANCE ->
            compareByDescending<AirlineStat> { it.distanceKm }
                .thenByDescending { it.flights }
                .thenBy { it.code }
        AirlineRankBy.FLIGHTS ->
            compareByDescending<AirlineStat> { it.flights }
                .thenByDescending { it.distanceKm }
                .thenBy { it.code }
    }

    return TopAirlinesSummary(
        totalAirlines = stats.size,
        taggedFlights = tagged,
        untaggedFlights = untagged,
        ranked = stats.sortedWith(comparator),
    )
}
