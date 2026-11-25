package dev.mriya.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class FlightLookupProvider(val id: String, val label: String) {
    NONE("none", "Off"),
    AERODATABOX("aerodatabox", "AeroDataBox"),
    FLIGHTAWARE("flightaware", "FlightAware");

    companion object {
        fun fromId(id: String?): FlightLookupProvider =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class FlightLookupResult(
    val fromIata: String,
    val toIata: String,
    val airlineIata: String,
    val flightNumber: String,
    val departTs: Long? = null,
    val arriveTs: Long? = null,
    val aircraftType: String? = null,
    val registration: String? = null,
    val callsign: String? = null,
    val status: String? = null,
    val departureTerminal: String? = null,
    val departureGate: String? = null,
    val arrivalTerminal: String? = null,
    val arrivalGate: String? = null,
)

class FlightLookupException(message: String) : IllegalStateException(message)

/**
 * Parses a designator such as `"UA 123"`, `"LH400"`, `"B6 524"`, or `"7W101"` into `(carrier, number)`.
 */
fun parseFlightDesignator(raw: String): Pair<String, String>? {
    val cleaned = raw.trim().uppercase(Locale.ROOT)
    if (cleaned.isEmpty()) return null
    val spaced = Regex("^([A-Z0-9]{2,3})[\\s-]+(\\d{1,4}[A-Z]?)$").matchEntire(cleaned)
    if (spaced != null) {
        val code = spaced.groupValues[1]
        if (code.any { it in 'A'..'Z' }) return code to spaced.groupValues[2]
    }
    val compact = Regex("^([A-Z]{2,3}|[0-9][A-Z]|[A-Z][0-9])(\\d{1,4}[A-Z]?)$").matchEntire(cleaned)
        ?: return null
    return compact.groupValues[1] to compact.groupValues[2]
}

/**
 * Opt-in client for schedule/aircraft lookup by flight number and date using user-supplied API keys.
 * Keys are sent strictly in HTTP request headers (never query params) and omitted from exceptions.
 */
class FlightLookupApi(
    private val provider: FlightLookupProvider,
    private val apiKey: String,
    private val aeroDataBoxHost: String = DEFAULT_AERODATABOX_HOST,
    private val baseUrlOverride: String? = null,
) {
    suspend fun lookup(designator: String, dateIso: String): FlightLookupResult = withContext(Dispatchers.IO) {
        if (provider == FlightLookupProvider.NONE) {
            throw FlightLookupException("Enable AeroDataBox or FlightAware in Settings first.")
        }
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            throw FlightLookupException("Add your ${provider.label} API key in Settings first.")
        }
        val (carrier, number) = parseFlightDesignator(designator)
            ?: throw FlightLookupException("Enter flight as airline + number (e.g. UA 123).")
        val date = try {
            LocalDate.parse(dateIso.trim())
        } catch (_: DateTimeParseException) {
            throw FlightLookupException("Enter date as YYYY-MM-DD.")
        }
        val flightCode = "$carrier$number"

        when (provider) {
            FlightLookupProvider.NONE -> throw FlightLookupException("Flight lookup is turned off.")
            FlightLookupProvider.AERODATABOX -> lookupAeroDataBox(flightCode, carrier, number, date, cleanKey)
            FlightLookupProvider.FLIGHTAWARE -> lookupFlightAware(flightCode, carrier, number, date, cleanKey)
        }
    }

    private fun lookupAeroDataBox(
        flightCode: String,
        carrier: String,
        number: String,
        date: LocalDate,
        key: String,
    ): FlightLookupResult {
        val host = aeroDataBoxHost.trim().ifEmpty { DEFAULT_AERODATABOX_HOST }
        val base = (baseUrlOverride ?: "https://$host").trim().trimEnd('/')
        val encodedCode = URLEncoder.encode(flightCode, Charsets.UTF_8.name())
        val url = "$base/flights/number/$encodedCode/$date"
        val req = Request.Builder()
            .url(url)
            .header("X-RapidAPI-Key", key)
            .header("X-RapidAPI-Host", host)
            .build()

        val body = executeJson(req, "AeroDataBox")
        val arr = when (val root = json.parseToJsonElement(body)) {
            is JsonArray -> root
            is JsonObject -> root["flights"]?.jsonArray
            else -> null
        } ?: throw FlightLookupException("No flight data returned for $flightCode on $date.")

        val item = arr.firstOrNull()?.jsonObject
            ?: throw FlightLookupException("No flights found for $flightCode on $date.")

        val dep = item["departure"]?.jsonObject
        val arrObj = item["arrival"]?.jsonObject
        val fromIata = dep?.get("airport")?.jsonObject?.get("iata")?.str()?.uppercase(Locale.ROOT)
        val toIata = arrObj?.get("airport")?.jsonObject?.get("iata")?.str()?.uppercase(Locale.ROOT)
        if (fromIata.isNullOrBlank() || toIata.isNullOrBlank()) {
            throw FlightLookupException("Flight record is missing airport codes.")
        }

        val depTime = dep["revisedTime"]?.jsonObject ?: dep["scheduledTime"]?.jsonObject
        val arrTime = arrObj["revisedTime"]?.jsonObject ?: arrObj["scheduledTime"]?.jsonObject
        val ac = item["aircraft"]?.jsonObject
        val airlineObj = item["airline"]?.jsonObject

        return FlightLookupResult(
            fromIata = fromIata,
            toIata = toIata,
            airlineIata = airlineObj?.get("iata")?.str()?.uppercase(Locale.ROOT) ?: carrier,
            flightNumber = item["number"]?.str()?.replace(Regex("[^0-9A-Z]", RegexOption.IGNORE_CASE), "")
                ?.removePrefix(carrier)?.ifBlank { number } ?: number,
            departTs = parseTimestamp(depTime?.get("utc")?.str() ?: depTime?.get("local")?.str()),
            arriveTs = parseTimestamp(arrTime?.get("utc")?.str() ?: arrTime?.get("local")?.str()),
            aircraftType = ac?.get("model")?.str()?.uppercase(Locale.ROOT),
            registration = ac?.get("reg")?.str()?.uppercase(Locale.ROOT),
            callsign = item["callSign"]?.str()?.uppercase(Locale.ROOT),
            status = item["status"]?.str(),
            departureTerminal = dep["terminal"]?.str(),
            departureGate = dep["gate"]?.str(),
            arrivalTerminal = arrObj["terminal"]?.str(),
            arrivalGate = arrObj["gate"]?.str(),
        )
    }

    private fun lookupFlightAware(
        flightCode: String,
        carrier: String,
        number: String,
        date: LocalDate,
        key: String,
    ): FlightLookupResult {
        val base = (baseUrlOverride ?: "https://aeroapi.flightaware.com/aeroapi").trim().trimEnd('/')
        val encodedCode = URLEncoder.encode(flightCode, Charsets.UTF_8.name())
        val nextDay = date.plusDays(1)
        val url = "$base/flights/$encodedCode".toHttpUrl().newBuilder()
            .addQueryParameter("start", "${date}T00:00:00Z")
            .addQueryParameter("end", "${nextDay}T00:00:00Z")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("x-apikey", key)
            .build()

        val body = executeJson(req, "FlightAware")
        val root = json.parseToJsonElement(body).jsonObject
        val flights = root["flights"]?.jsonArray
        val item = flights?.firstOrNull()?.jsonObject
            ?: throw FlightLookupException("No flights found for $flightCode on $date.")

        val origin = item["origin"]?.jsonObject
        val dest = item["destination"]?.jsonObject
        val fromIata = (origin?.get("code_iata")?.str() ?: origin?.get("code")?.str())?.uppercase(Locale.ROOT)
        val toIata = (dest?.get("code_iata")?.str() ?: dest?.get("code")?.str())?.uppercase(Locale.ROOT)
        if (fromIata.isNullOrBlank() || toIata.isNullOrBlank()) {
            throw FlightLookupException("FlightAware record is missing airport codes.")
        }

        val depStr = item["actual_out"]?.str() ?: item["estimated_out"]?.str() ?: item["scheduled_out"]?.str()
        val arrStr = item["actual_in"]?.str() ?: item["estimated_in"]?.str() ?: item["scheduled_in"]?.str()

        return FlightLookupResult(
            fromIata = fromIata.takeLast(3),
            toIata = toIata.takeLast(3),
            airlineIata = item["operator_iata"]?.str()?.uppercase(Locale.ROOT) ?: carrier,
            flightNumber = item["flight_number"]?.str() ?: number,
            departTs = parseTimestamp(depStr),
            arriveTs = parseTimestamp(arrStr),
            aircraftType = item["aircraft_type"]?.str()?.uppercase(Locale.ROOT),
            registration = item["registration"]?.str()?.uppercase(Locale.ROOT),
            callsign = item["ident_icao"]?.str()?.uppercase(Locale.ROOT),
            status = item["status"]?.str(),
            departureTerminal = item["terminal_origin"]?.str(),
            departureGate = item["gate_origin"]?.str(),
            arrivalTerminal = item["terminal_destination"]?.str(),
            arrivalGate = item["gate_destination"]?.str(),
        )
    }

    private fun executeJson(req: Request, sourceLabel: String): String {
        return client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                throw FlightLookupException("$sourceLabel rejected the API key (HTTP ${r.code}).")
            }
            if (r.code == 404) {
                throw FlightLookupException("Flight not found on $sourceLabel.")
            }
            if (r.code == 429) {
                throw FlightLookupException("$sourceLabel rate limit reached; try again shortly.")
            }
            if (!r.isSuccessful) {
                throw FlightLookupException("$sourceLabel returned HTTP ${r.code}.")
            }
            r.body?.string().orEmpty()
        }
    }

    companion object {
        const val DEFAULT_AERODATABOX_HOST = "aerodatabox.p.rapidapi.com"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        }

        private fun JsonElement?.str(): String? =
            runCatching { this?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

        internal fun parseTimestamp(raw: String?): Long? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val isoCandidate = if (' ' in s && 'T' !in s) s.replace(' ', 'T') else s
            return runCatching { Instant.parse(isoCandidate).epochSecond }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(isoCandidate).toInstant().epochSecond }.getOrNull()
        }
    }
}
