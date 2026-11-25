package dev.mriya.domain

import android.content.Context
import dev.mriya.data.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [tier]: 2 large airport, 1 medium with scheduled service, 0 other (general aviation, closed). */
data class Airport(val iata: String, val name: String, val lat: Double, val lon: Double, val country: String, val tier: Int = 1, val city: String = "")

/** Large and medium airports with an IATA code (OurAirports), bundled in assets/airports.csv. */
class AirportDb private constructor(private val airports: List<Airport>) {
    private val byCode: Map<String, Airport> by lazy { airports.associateBy { it.iata } }

    private class Key(val a: Airport, val city: String, val aliases: List<String>, val name: String, val country: String, val all: String)

    /**
     * Names people use that differ from the source data's "municipality": the main airport of Taipei is listed under
     * Taoyuan, Kyiv's under Boryspil, and so on.
     */
    private val cityAliases = mapOf(
        "TPE" to "taipei", "TSA" to "taipei", "KBP" to "kyiv kiev", "IEV" to "kyiv kiev", "SJD" to "los cabos cabo san lucas",
        "SGN" to "saigon ho chi minh", "HAN" to "hanoi", "NRT" to "tokyo", "HND" to "tokyo", "KIX" to "osaka", "ITM" to "osaka",
        "ICN" to "seoul", "GMP" to "seoul", "PKX" to "beijing", "PEK" to "beijing", "PVG" to "shanghai", "SHA" to "shanghai",
        "MXP" to "milan milano", "LIN" to "milan milano", "FCO" to "rome roma", "CIA" to "rome roma", "IAD" to "washington dc",
        "DCA" to "washington dc", "BWI" to "washington baltimore", "EWR" to "new york", "LGA" to "new york", "JFK" to "new york",
        "SVO" to "moscow", "DME" to "moscow", "VKO" to "moscow", "LED" to "saint petersburg st petersburg",
    )

    private val keys: List<Key> by lazy {
        airports.map { a ->
            val country = runCatching { java.util.Locale("", a.country).getDisplayCountry(java.util.Locale.ENGLISH) }.getOrDefault("")
            val city = fold(a.city); val name = fold(a.name); val c = fold(country)
            val aliases = cityAliases[a.iata]?.let { al -> listOf(al) + al.split(' ') }.orEmpty()
            Key(a, city, aliases, name, c, "$city ${aliases.firstOrNull().orEmpty()} $name ${a.iata.lowercase()} $c")
        }
    }

    /**
     * Airports matching [query], best first: an exact code, then city (exact, starts with, contains), then airport
     * name, then country. Accents and case are ignored ("zurich" finds Zürich), several words must all match, and
     * big commercial airports come before small or closed ones with the same score.
     */
    fun search(query: String, limit: Int = 8): List<Airport> {
        val q = fold(query).trim()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return keys.mapNotNull { k ->
            val score = when {
                k.a.iata.lowercase() == q -> 0
                k.city == q || q in k.aliases -> 1
                k.city.startsWith(q) || k.aliases.any { it.startsWith(q) } -> 2
                k.name.startsWith(q) -> 3
                k.city.contains(q) -> 4
                k.name.contains(q) -> 5
                k.country.startsWith(q) -> 7
                words.size > 1 && words.all { it in k.all } -> 6
                else -> return@mapNotNull null
            }
            Triple(k.a, score, k.a.tier)
        }.sortedWith(compareBy({ it.second }, { -it.third }, { it.first.name })).take(limit).map { it.first }
    }

    fun byIata(code: String): Airport? = byCode[code.uppercase()]
    fun isAirport(code: String) = byCode.containsKey(code.uppercase())

    /** IATA codes of every airport in the country with ISO-2 code [country]. */
    fun codesIn(country: String): Set<String> = airports.filter { it.country.equals(country, ignoreCase = true) }.map { it.iata }.toSet()

    /**
     * Best airport within [maxKm], or null. Distance is penalised by tier, so a big commercial airport 34 km away
     * beats a general-aviation field 5 km away (a Chicago-area phone should read ORD, not DPA).
     */
    fun nearest(p: LatLon, maxKm: Double = 40.0): Airport? =
        airports.filter { kotlin.math.abs(it.lat - p.lat) < 1.0 }
            .map { it to haversineKm(p, LatLon(it.lat, it.lon)) }
            .filter { it.second <= maxKm }
            .minByOrNull { (a, d) -> d + when (a.tier) { 2 -> 0.0; 1 -> 10.0; else -> 40.0 } }
            ?.first

    companion object {
        @Volatile private var cache: AirportDb? = null

        fun of(list: List<Airport>) = AirportDb(list)

        /** Lower case with accents removed, for matching. */
        internal fun fold(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        /** Parsed once per process on a background thread. */
        suspend fun get(ctx: Context): AirportDb = cache ?: withContext(Dispatchers.IO) {
            cache ?: load(ctx.applicationContext).also { cache = it }
        }

        private fun load(ctx: Context) = AirportDb(
            ctx.assets.open("airports.csv").bufferedReader().useLines { lines ->
                lines.mapNotNull { l ->
                    val c = l.split(',')
                    if (c.size < 5) null else Airport(c[0], c[1], c[2].toDouble(), c[3].toDouble(), c[4], c.getOrNull(5)?.toIntOrNull() ?: 1, c.getOrNull(6).orEmpty())
                }.toList()
            }
        )
    }
}
