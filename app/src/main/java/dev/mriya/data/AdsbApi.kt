package dev.mriya.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class AdsbFeed(val id: String, val label: String, val baseUrl: String) {
    ADSB_LOL("adsb_lol", "adsb.lol", "https://api.adsb.lol"),
    AIRPLANES_LIVE("airplanes_live", "airplanes.live", "https://api.airplanes.live");

    companion object {
        fun fromId(id: String?): AdsbFeed = entries.firstOrNull { it.id == id } ?: ADSB_LOL
    }
}

data class LiveAircraft(
    val callsign: String,
    val registration: String? = null,
    val aircraftType: String? = null,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Int? = null,
    val groundSpeedKt: Int? = null,
    val headingDeg: Float? = null,
    val squawk: String? = null,
    val seenAtEpochSec: Long = Instant.now().epochSecond,
)

sealed class AdsbQuery {
    data class Callsign(val value: String) : AdsbQuery()
    data class Registration(val value: String) : AdsbQuery()
}

class AdsbException(message: String) : IllegalStateException(message)

private val iataToIcaoMap = mapOf(
    "AA" to "AAL", "AC" to "ACA", "AF" to "AFR", "AI" to "AIC", "AM" to "AMX",
    "AS" to "ASA", "AT" to "RAM", "AV" to "AVA", "AY" to "FIN", "AZ" to "ITY",
    "A3" to "AEE", "BA" to "BAW", "BR" to "EVA", "BT" to "BTI", "B6" to "JBU",
    "CA" to "CCA", "CI" to "CAL", "CM" to "CMP", "CX" to "CPA", "CZ" to "CSN",
    "DL" to "DAL", "EI" to "EIN", "EK" to "UAE", "ET" to "ETH", "EW" to "EWG",
    "EY" to "ETD", "FI" to "ICE", "FR" to "RYR", "FZ" to "FDB", "F9" to "FFT",
    "GA" to "GIA", "GF" to "GFA", "G3" to "GLO", "HA" to "HAL", "HU" to "CHH",
    "IB" to "IBE", "JL" to "JAL", "JQ" to "JST", "KE" to "KAL", "KL" to "KLM",
    "KQ" to "KQA", "LA" to "LAN", "LH" to "DLH", "LO" to "LOT", "LX" to "SWR",
    "LY" to "ELY", "MF" to "CXA", "MH" to "MAS", "MS" to "MSR", "MU" to "CES",
    "NH" to "ANA", "NK" to "NKS", "NZ" to "ANZ", "OK" to "CSA", "OS" to "AUA",
    "OU" to "CTN", "OZ" to "AAR", "PC" to "PGT", "PD" to "POE", "PQ" to "SQP",
    "PR" to "PAL", "PS" to "AUI", "QF" to "QFA", "QR" to "QTR", "RJ" to "RJA",
    "RO" to "ROT", "SA" to "SAA", "SK" to "SAS", "SN" to "BEL", "SQ" to "SIA",
    "SV" to "SVA", "TG" to "THA", "TK" to "THY", "TP" to "TAP", "TR" to "TGW",
    "UA" to "UAL", "U2" to "EZY", "UX" to "AEA", "VA" to "VOZ", "VN" to "HVN",
    "VS" to "VIR", "VY" to "VLG", "WN" to "SWA", "WS" to "WJA", "WY" to "OMA",
    "W6" to "WZZ", "ZH" to "CSZ",
)

fun iataToIcaoCallsign(airlineCode: String?, flightNumber: String?): String? {
    val carrier = airlineCode?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    val num = flightNumber?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    val icao = if (carrier.length == 3) carrier else iataToIcaoMap[carrier] ?: carrier
    return "$icao$num"
}

/**
 * Classifies a user tracking query as a registration (tail number) or ATC callsign.
 */
fun normalizeTrackQuery(input: String): AdsbQuery? {
    val raw = input.trim().uppercase(Locale.ROOT)
    if (raw.isEmpty()) return null
    if (raw.startsWith("REG:")) {
        val reg = raw.removePrefix("REG:").trim()
        return if (reg.isNotEmpty()) AdsbQuery.Registration(reg) else null
    }
    if ('-' in raw || Regex("^N[1-9][0-9A-Z]{0,4}$").matches(raw)) {
        return AdsbQuery.Registration(raw)
    }
    val parsed = parseFlightDesignator(raw)
    if (parsed != null) {
        val (carrier, num) = parsed
        val callsign = iataToIcaoCallsign(carrier, num) ?: "$carrier$num"
        return AdsbQuery.Callsign(callsign)
    }
    val compact = raw.replace(" ", "")
    return if (Regex("^[A-Z0-9]{3,8}$").matches(compact)) AdsbQuery.Callsign(compact) else null
}

/**
 * Client for free community ADS-B v2 JSON feeds (`adsb.lol` and `airplanes.live`).
 * Sends only the callsign or registration the user chooses to track.
 */
class AdsbApi(
    private val feed: AdsbFeed = AdsbFeed.ADSB_LOL,
    private val baseUrlOverride: String? = null,
) {
    suspend fun lookup(query: AdsbQuery): LiveAircraft? = when (query) {
        is AdsbQuery.Callsign -> lookupByCallsign(query.value)
        is AdsbQuery.Registration -> lookupByRegistration(query.value)
    }

    suspend fun lookupByCallsign(callsign: String): LiveAircraft? = withContext(Dispatchers.IO) {
        val clean = callsign.trim().uppercase(Locale.ROOT)
        if (clean.isEmpty()) return@withContext null
        fetchSingle("/v2/callsign/${URLEncoder.encode(clean, Charsets.UTF_8.name())}", clean)
    }

    suspend fun lookupByRegistration(registration: String): LiveAircraft? = withContext(Dispatchers.IO) {
        val clean = registration.trim().uppercase(Locale.ROOT)
        if (clean.isEmpty()) return@withContext null
        fetchSingle("/v2/reg/${URLEncoder.encode(clean, Charsets.UTF_8.name())}", clean)
    }

    private fun fetchSingle(path: String, fallbackLabel: String): LiveAircraft? {
        val base = (baseUrlOverride ?: feed.baseUrl).trim().trimEnd('/')
        val req = Request.Builder().url("$base$path").build()
        val body = client.newCall(req).execute().use { r ->
            if (r.code == 429) throw AdsbException("${feed.label} rate limit reached; retrying shortly.")
            if (!r.isSuccessful) throw AdsbException("${feed.label} returned HTTP ${r.code}.")
            r.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body).jsonObject
        val acList = root["ac"]?.jsonArray ?: return null
        val item = acList.firstOrNull()?.jsonObject ?: return null
        val lat = item["lat"]?.dbl() ?: return null
        val lon = item["lon"]?.dbl() ?: return null
        val cs = item["flight"]?.str() ?: item["callsign"]?.str() ?: fallbackLabel
        val altBaro = item["alt_baro"]?.let { el ->
            val p = runCatching { el.jsonPrimitive }.getOrNull()
            when {
                p == null -> null
                p.intOrNull != null -> p.intOrNull
                p.contentOrNull.equals("ground", ignoreCase = true) -> 0
                else -> p.doubleOrNull?.roundToInt()
            }
        }
        return LiveAircraft(
            callsign = cs.trim().uppercase(Locale.ROOT),
            registration = item["r"]?.str()?.uppercase(Locale.ROOT),
            aircraftType = item["t"]?.str()?.uppercase(Locale.ROOT),
            lat = lat,
            lon = lon,
            altitudeFt = altBaro,
            groundSpeedKt = item["gs"]?.dbl()?.roundToInt(),
            headingDeg = item["track"]?.flt(),
            squawk = item["squawk"]?.str(),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
        }

        private fun JsonElement.str(): String? =
            runCatching { jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

        private fun JsonElement.dbl(): Double? =
            runCatching { jsonPrimitive.doubleOrNull }.getOrNull()

        private fun JsonElement.flt(): Float? =
            runCatching { jsonPrimitive.floatOrNull }.getOrNull()
    }
}
