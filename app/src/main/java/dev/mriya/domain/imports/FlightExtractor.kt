package dev.mriya.domain.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** A flight read out of an email, calendar event, ICS file or boarding pass. Everything is parsed on the device. */
data class ParsedFlight(
    val from: String,            // IATA airport
    val to: String,
    val airline: String?,        // IATA airline code
    val flightNumber: String?,   // digits only
    val date: LocalDate,         // local departure date
    val departTime: LocalTime?,  // local, if known
    val departAt: Instant? = null,
    val arriveAt: Instant? = null,
    val source: String,          // json-ld | boarding-pass | text | calendar | ics
)

/**
 * Pulls flights out of untrusted text. Order of preference, most reliable first:
 * 1. schema.org FlightReservation JSON-LD (most airline confirmation emails),
 * 2. IATA boarding-pass barcode text (BCBP),
 * 3. plain text: airport codes, flight designators, dates and times.
 * Never executes or follows anything in the input.
 */
object FlightExtractor {
    private const val MAX_CHARS = 1_000_000

    fun extract(raw: String, ref: LocalDate, isAirport: (String) -> Boolean): List<ParsedFlight> {
        val text = decodeQuotedPrintable(raw.take(MAX_CHARS))
        val out = ArrayList<ParsedFlight>()
        fun add(f: ParsedFlight) {
            if (f.from == f.to) return
            val i = out.indexOfFirst { sameLeg(it, f) }
            if (i < 0) out += f
            // The same leg seen twice: keep the first (more reliable) reading, but fill in a missing flight number.
            else if (out[i].flightNumber == null && f.flightNumber != null) out[i] = out[i].copy(airline = out[i].airline ?: f.airline, flightNumber = f.flightNumber)
        }
        // Structured readings first. A booking often has more legs than its markup or barcode covers (markup for the
        // outbound only, one boarding pass out of a connection), so the plain text is always read too and only adds
        // legs that are not already there. Text-only legs stay marked as text, i.e. unverified.
        JsonLd.parse(text, isAirport).forEach(::add)
        Bcbp.parse(text, ref, isAirport).forEach(::add)
        TextFlights.parse(visibleText(text), ref, isAirport).forEach(::add)
        return out.take(40)
    }

    private val hiddenBlocks = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val lineBreakTags = Regex("<br\\s*/?>|</(p|div|tr|li|h[1-6])>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")

    /** HTML mail as plain text: scripts and styles dropped, block tags become line breaks, other tags become spaces. */
    internal fun visibleText(s: String): String =
        if (!s.contains('<')) s
        else s.replace(hiddenBlocks, " ").replace(lineBreakTags, "\n").replace(anyTag, " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /** The same route within a day: two readings of one flight (dates in emails are often off by a timezone). */
    private fun sameLeg(a: ParsedFlight, b: ParsedFlight) =
        a.from == b.from && a.to == b.to && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(a.date, b.date)) <= 1

    /** Saved .eml files and some shares arrive quoted-printable encoded; undo that so JSON-LD is readable. */
    internal fun decodeQuotedPrintable(s: String): String {
        if (!s.contains("quoted-printable", ignoreCase = true) && !Regex("=\\r?\\n").containsMatchIn(s)) return s
        val joined = s.replace(Regex("=\\r?\\n"), "")
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < joined.length) {
            val c = joined[i]
            val hex = if (c == '=' && i + 2 < joined.length) joined.substring(i + 1, i + 3) else null
            if (hex != null && hex.all { it in "0123456789ABCDEFabcdef" }) { bytes.write(hex.toInt(16)); i += 3 }
            else { bytes.write(c.toString().toByteArray()); i++ }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

internal object JsonLd {
    private val script = Regex("""<script[^>]*ld\+json[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String, isAirport: (String) -> Boolean): List<ParsedFlight> {
        val blobs = script.findAll(text).map { it.groupValues[1].trim() }.toList().ifEmpty {
            val t = text.trim()
            if (t.startsWith("{") || t.startsWith("[")) listOf(t) else emptyList()
        }
        val out = mutableListOf<ParsedFlight>()
        for (b in blobs) runCatching { walk(json.parseToJsonElement(b), out, isAirport) }
        return out
    }

    private fun walk(e: JsonElement, out: MutableList<ParsedFlight>, isAirport: (String) -> Boolean) {
        when (e) {
            is JsonArray -> e.forEach { walk(it, out, isAirport) }
            is JsonObject -> {
                if (e.containsKey("departureAirport") && e.containsKey("arrivalAirport")) toParsed(e, isAirport)?.let(out::add)
                else e.values.forEach { walk(it, out, isAirport) }
            }
            else -> {}
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun iata(e: JsonElement?): String? = (e.obj()?.get("iataCode").str() ?: e.str())?.trim()?.uppercase()?.takeIf { it.length == 3 }

    private fun toParsed(o: JsonObject, isAirport: (String) -> Boolean): ParsedFlight? {
        val from = iata(o["departureAirport"]) ?: return null
        val to = iata(o["arrivalAirport"]) ?: return null
        if (!isAirport(from) || !isAirport(to)) return null
        val dep = Times.parse(o["departureTime"].str()) ?: return null
        val arr = Times.parse(o["arrivalTime"].str())
        val num = o["flightNumber"].str()?.filter { it.isDigit() }?.trimStart('0')?.ifEmpty { null }
        val airline = (o["airline"].obj()?.get("iataCode").str())?.trim()?.uppercase()?.takeIf { it.length in 2..3 }
        return ParsedFlight(from, to, airline, num, dep.local.toLocalDate(), dep.local.toLocalTime(), dep.instant, arr?.instant, "json-ld")
    }
}

internal object Times {
    class T(val instant: Instant?, val local: LocalDateTime)

    fun parse(s: String?): T? {
        if (s.isNullOrBlank()) return null
        runCatching { OffsetDateTime.parse(s) }.getOrNull()?.let { return T(it.toInstant(), it.toLocalDateTime()) }
        runCatching { LocalDateTime.parse(s) }.getOrNull()?.let { return T(null, it) }
        runCatching { LocalDate.parse(s.take(10)) }.getOrNull()?.let { return T(null, it.atStartOfDay()) }
        return null
    }

    fun systemInstant(date: LocalDate, time: LocalTime?): Instant =
        date.atTime(time ?: LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant()
}

/** IATA Bar Coded Boarding Pass (BCBP) text, as found in the PDF417 / Aztec / QR code on a boarding pass. */
internal object Bcbp {
    private val start = Regex("""M[1-9][A-Z /]{20}[A-Z0-9 ]""")

    fun parse(text: String, ref: LocalDate, isAirport: (String) -> Boolean): List<ParsedFlight> {
        val out = mutableListOf<ParsedFlight>()
        for (m in start.findAll(text)) {
            val s = text.substring(m.range.first)
            val legs = s[1].digitToIntOrNull() ?: continue
            if (s.length < 60) continue
            var pos = 23 // first leg's mandatory block starts after format, legs, name and e-ticket flag
            var ok = true
            for (leg in 0 until legs) {
                if (pos + 37 > s.length) { ok = false; break }
                val from = s.substring(pos + 7, pos + 10).trim().uppercase()
                val to = s.substring(pos + 10, pos + 13).trim().uppercase()
                val carrier = s.substring(pos + 13, pos + 16).trim().uppercase()
                val number = s.substring(pos + 16, pos + 21).trim()
                val julian = s.substring(pos + 21, pos + 24).trim().toIntOrNull()
                val varSize = s.substring(pos + 35, pos + 37).trim().toIntOrNull(16)
                if (!isAirport(from) || !isAirport(to) || julian == null || julian !in 1..366 || varSize == null) { ok = false; break }
                val date = dateForJulian(julian, ref)
                if (date == null) { ok = false; break }
                out += ParsedFlight(
                    from, to, carrier.ifEmpty { null },
                    number.filter { it.isDigit() }.trimStart('0').ifEmpty { null },
                    date, null, source = "boarding-pass",
                )
                pos += 37 + varSize
            }
            if (!ok && out.isNotEmpty()) break
        }
        return out
    }

    /** A boarding pass only carries the day of the year: pick the year that lands closest to [ref]. */
    fun dateForJulian(day: Int, ref: LocalDate): LocalDate? =
        (ref.year - 1..ref.year + 1).mapNotNull { y -> runCatching { LocalDate.ofYearDay(y, day) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(ref, it)) }
}

/** Last resort: airport codes, flight designators, dates and times found in free text. */
internal object TextFlights {
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val monthRe = monthNames.joinToString("|") { it.replaceFirstChar(Char::uppercase) } + "|" + monthNames.joinToString("|") + "|" + monthNames.joinToString("|") { it.uppercase() }
    private val stop = setOf(
        "THE", "AND", "FOR", "YOU", "ARE", "NOT", "ALL", "CAN", "HER", "WAS", "ONE", "OUR", "OUT", "DAY", "GET", "HAS", "HIM", "HIS", "HOW",
        "NEW", "NOW", "OLD", "SEE", "TWO", "WAY", "WHO", "ITS", "LET", "PUT", "SAY", "SHE", "TOO", "USE", "PDF", "HTML", "EST", "PST",
        "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "JAN", "FEB", "APR", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC", "MAY", "MAR",
        "USD", "EUR", "GBP", "UTC", "GMT", "VIA", "NON", "REF", "PNR", "ETA", "ETD", "TBA", "FAQ", "URL", "APP", "WEB", "COM", "ORG", "NET",
    )
    private val airportTok = Regex("""\b[A-Z]{3}\b""")
    // One-digit numbers (QF1, BA9) count only when written tight to a two-letter airline code, so stray "AB 1" in prose is ignored.
    private val designator = Regex("""\b([A-Z]{2}|[A-Z]\d|\d[A-Z])\s?(\d{2,4}|(?<=[A-Z]{2})\d)\b""")
    /** "8:30", "8:30 AM", "8:30pm" and "8:30 a.m." (travel portals write the dotted form). */
    private val timeRe = Regex("""\b(\d{1,2}):(\d{2})(?:\s?([AaPp])\.?[Mm]\b\.?)?""")
    private val dateRes = listOf(
        Regex("""\b(20\d{2})-(\d{2})-(\d{2})\b""") to "iso",
        Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](20\d{2})\b""") to "num",
        Regex("""\b(\d{1,2})\s?($monthRe)[a-z]*\.?,?\s?(20\d{2}|\d{2})\b""") to "dmy",
        Regex("""\b($monthRe)[a-z]*\.?\s(\d{1,2})(?:st|nd|rd|th)?,?\s(20\d{2})\b""") to "mdy",
        Regex("""\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\s(?:($monthRe)[a-z]*\.?\s(\d{1,2})|(\d{1,2})\s($monthRe)[a-z]*)\b""") to "wd",
    )
    private val yearlessDateRes = listOf(
        Regex("""\b($monthRe)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""") to "md",
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($monthRe)[a-z]*\.?\b""") to "dm",
    )

    private class DateHit(val pos: Int, val date: LocalDate)

    private fun month(s: String) = monthNames.indexOf(s.lowercase().take(3)) + 1

    private fun nearestDate(ref: LocalDate, month: Int, day: Int, preferPast: Boolean = false): LocalDate? {
        val candidates = (ref.year - 1..ref.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull() }
        if (preferPast) {
            candidates.filter { !it.isAfter(ref) }.maxOrNull()?.let { return it }
        }
        return candidates.minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(ref, it)) }
    }

    private fun dates(text: String, ref: LocalDate): List<DateHit> {
        val preferPast = Regex("unarchive|past (flights|trips)|completed trips|previous trips", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val headerYears = Regex("""(?m)^\s*(20\d{2})\b""").findAll(text).map { it.range.first to it.groupValues[1].toInt() }.toList()
        fun yearAt(pos: Int) = headerYears.lastOrNull { it.first <= pos }?.second
        // 02/09/2025: day first or month first? A date in the same message that cannot be read the other way
        // settles it (18/04/2025 is day first); otherwise follow the phone's region.
        val numeric = Regex("""\b(\d{1,2})[/.](\d{1,2})[/.]20\d{2}\b""").findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val dayFirst = when {
            numeric.any { it.first > 12 } -> true
            numeric.any { it.second > 12 } -> false
            else -> java.util.Locale.getDefault().country !in setOf("US", "CA", "PH", "")
        }
        val hits = mutableListOf<DateHit>()
        val covered = mutableListOf<IntRange>()
        for ((re, kind) in dateRes) for (m in re.findAll(text)) {
            val g = m.groupValues
            val d = runCatching {
                when (kind) {
                    "iso" -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                    "num" -> if (dayFirst) LocalDate.of(g[3].toInt(), g[2].toInt(), g[1].toInt()) else LocalDate.of(g[3].toInt(), g[1].toInt(), g[2].toInt())
                    "dmy" -> LocalDate.of(year(g[3]), month(g[2]), g[1].toInt())
                    "mdy" -> LocalDate.of(g[3].toInt(), month(g[1]), g[2].toInt())
                    else -> { // weekday + month + day, no year: nearest to the reference date
                        val mo = month(g[1].ifEmpty { g[4] }); val day = (g[2].ifEmpty { g[3] }).toInt()
                        yearAt(m.range.first)?.let { y -> LocalDate.of(y, mo, day) } ?: nearestDate(ref, mo, day, preferPast)
                    }
                }
            }.getOrNull() ?: continue
            hits += DateHit(m.range.first, d)
            covered += m.range
        }
        for ((re, kind) in yearlessDateRes) for (m in re.findAll(text)) {
            if (covered.any { it.first <= m.range.last && m.range.first <= it.last }) continue
            val g = m.groupValues
            val d = runCatching {
                when (kind) {
                    "md" -> yearAt(m.range.first)?.let { y -> LocalDate.of(y, month(g[1]), g[2].toInt()) } ?: nearestDate(ref, month(g[1]), g[2].toInt(), preferPast)
                    else -> yearAt(m.range.first)?.let { y -> LocalDate.of(y, month(g[2]), g[1].toInt()) } ?: nearestDate(ref, month(g[2]), g[1].toInt(), preferPast)
                }
            }.getOrNull() ?: continue
            hits += DateHit(m.range.first, d)
            covered += m.range
        }
        return hits.sortedBy { it.pos }
    }

    private fun year(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    /** Two-letter "codes" that are ordinary words next to numbers ("PM 12"), not airlines. */
    private val notAirline = setOf("AM", "PM", "ID", "NO", "OK", "ON", "IN", "AT", "TO", "OR", "IF", "IS", "IT", "BY", "UP", "OF", "ME", "MY", "WE", "HE", "SO", "DO", "GO", "BE", "AN")

    /** Only punctuation, arrows or "to" between two airport codes: they belong to one chain ("ORD - DEN - SFO"). */
    private val chainSeparator = Regex("""^[\s>\-/\u2192\u2013\u2014]*(?:to)?[\s>\-/\u2192\u2013\u2014]*$""", RegexOption.IGNORE_CASE)

    private fun firstDistinct(list: List<MatchResult>): Pair<MatchResult, MatchResult>? {
        val a = list.firstOrNull() ?: return null
        val b = list.firstOrNull { it.value != a.value } ?: return null
        return a to b
    }

    fun parse(text: String, ref: LocalDate, isAirport: (String) -> Boolean): List<ParsedFlight> {
        val tokens = airportTok.findAll(text).filter { it.value !in stop && isAirport(it.value) }.toList()
        val dateHits = dates(text, ref)
        val designators = designator.findAll(text).filter { it.groupValues[1] !in notAirline }.toList()
        val out = ArrayList<ParsedFlight>()

        fun put(p: ParsedFlight) {
            if (p.from != p.to && out.none { it.from == p.from && it.to == p.to && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, p.date)) <= 1 }) out += p
        }

        fun leg(a: MatchResult, b: MatchResult, des: MatchResult?): ParsedFlight {
            val ls = text.lastIndexOf('\n', a.range.first) + 1
            val le = text.indexOf('\n', a.range.first).let { if (it < 0) text.length else it }
            val sameLine = dateHits.filter { it.pos in ls until le }.minByOrNull { kotlin.math.abs(it.pos - a.range.first) }
            val date = (sameLine ?: dateHits.lastOrNull { it.pos <= a.range.first } ?: dateHits.firstOrNull { it.pos > a.range.first })?.date ?: ref
            // The departure time follows the departure airport ("Chicago (ORD) 8:30 AM"); fall back to a time just before it.
            // A wider window would pick up the previous flight's arrival time.
            val tm = timeRe.find(text.substring(a.range.first, minOf(text.length, a.range.first + 60)))
                ?: timeRe.findAll(text.substring(maxOf(0, a.range.first - 25), a.range.first)).lastOrNull()
            val time = tm?.let { t ->
                var h = t.groupValues[1].toInt(); val mi = t.groupValues[2].toInt()
                val ap = t.groupValues[3].lowercase()
                if (ap == "p" && h < 12) h += 12
                if (ap == "a" && h == 12) h = 0
                runCatching { LocalTime.of(h, mi) }.getOrNull()
            }
            return ParsedFlight(
                a.value, b.value, des?.groupValues?.get(1), des?.groupValues?.get(2)?.trimStart('0')?.ifEmpty { null },
                date, time, source = "text",
            )
        }

        // 0) A list of trips, one per row ("J576LK • STL → ORD  Wed, Dec 17", as in airline apps' past-flight lists):
        //    each row is a leg and its date is the one on the row or just below it, never the previous row's.
        val used = HashSet<Int>()
        val rowRe = Regex("""^\s*(?:[A-Z0-9]{5,7}\s*[^\sA-Za-z0-9]\s*)([A-Z]{3})\s*(?:[^\sA-Za-z0-9]{1,2}|to|-)\s*([A-Z]{3})\b(.{0,40})$""")
        class Row(val start: Int, val end: Int, val a: Int, val b: Int)
        val rows = ArrayList<Row>()
        var pos = 0
        while (pos <= text.length) {
            val e = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val m = rowRe.find(text.substring(pos, e).trimEnd('\r'))
            if (m != null) {
                val ia = tokens.indexOfFirst { it.range.first == pos + m.groups[1]!!.range.first }
                val ib = tokens.indexOfFirst { it.range.first == pos + m.groups[2]!!.range.first }
                if (ia >= 0 && ib >= 0 && tokens[ia].value != tokens[ib].value) rows += Row(pos, e, ia, ib)
            }
            pos = e + 1
        }
        if (rows.size >= 2) {
            rows.forEachIndexed { k, r ->
                val limit = minOf(rows.getOrNull(k + 1)?.start ?: text.length, r.end + 160)
                val d = dateHits.firstOrNull { it.pos in r.start until limit }?.date
                val base = leg(tokens[r.a], tokens[r.b], null)
                put(if (d != null) base.copy(date = d) else base)
                used += r.a; used += r.b
            }
        }

        // 1) Legs anchored on a flight number. On a short line ("UA 123 ORD to DEN", or a table row) the airports of that
        //    line are the leg; otherwise take the pair closest to the number, before it or after it.
        for (d in designators) {
            val ls = text.lastIndexOf('\n', d.range.first) + 1
            val le = text.indexOf('\n', d.range.first).let { if (it < 0) text.length else it }
            // Only when this is the line's single flight number: a paragraph with several flights needs the closest-pair rule.
            val soleOnLine = designators.count { it.range.first in ls until le } == 1
            val onLine = if (le - ls <= 200 && soleOnLine) firstDistinct(tokens.filter { it.range.first in ls until le }) else null
            val pair = onLine ?: run {
                val after = firstDistinct(tokens.filter { it.range.first >= d.range.last && it.range.first - d.range.last <= 160 })
                val beforeRev = tokens.filter { it.range.last < d.range.first && d.range.first - it.range.last <= 160 }.asReversed()
                val before = firstDistinct(beforeRev)?.let { (near, earlier) -> earlier to near }
                val gapAfter = after?.let { it.first.range.first - d.range.last } ?: Int.MAX_VALUE
                val gapBefore = before?.let { d.range.first - it.second.range.last } ?: Int.MAX_VALUE
                if (gapAfter <= gapBefore) after else before
            }
            if (pair != null) {
                put(leg(pair.first, pair.second, d))
                used += tokens.indexOf(pair.first); used += tokens.indexOf(pair.second)
            } else {
                // A travel portal that names only one airport per flight ("Arrival to Los Cabos (SJD) ... Flight AA 1649").
                // Keep the leg with the other end blank; the importer completes it from a flight already in the passport.
                val near = tokens.filter { kotlin.math.abs(it.range.first - d.range.first) <= 160 }
                    .minByOrNull { kotlin.math.abs(it.range.first - d.range.first) }
                if (near != null) {
                    val ls = text.lastIndexOf('\n', near.range.first) + 1
                    val before = text.substring(maxOf(ls, near.range.first - 60), near.range.first).lowercase()
                    val arrives = Regex("""\b(arriv\w*|into|inbound|to)\b""").containsMatchIn(before)
                    val departs = Regex("""\b(depart\w*|from|outbound)\b""").containsMatchIn(before)
                    if (arrives != departs) {
                        // The date is the one next to the flight number ("Nov 13, 2026 at 12:45 PM, Flight AA 1649"),
                        // not a booking or return date from the header.
                        val dls = text.lastIndexOf('\n', d.range.first) + 1
                        val dle = text.indexOf('\n', d.range.first).let { if (it < 0) text.length else it }
                        val own = dateHits.firstOrNull { it.pos in dls until dle }?.date
                        val p = leg(near, near, d).let { if (own != null) it.copy(date = own) else it }
                        put(if (arrives) p.copy(from = "", to = near.value, source = "partial") else p.copy(from = near.value, to = "", source = "partial"))
                        used += tokens.indexOf(near)
                    }
                }
            }
        }

        // 2) Chains and pairs the flight numbers did not cover: "ORD - DEN - SFO" is two legs, and a first airport
        //    mentioned in a header must not shift every later pair.
        var i = 0
        while (i < tokens.size - 1) {
            val a = tokens[i]; val b = tokens[i + 1]
            if (i in used || (i + 1) in used) { i++; continue }
            if (a.value != b.value && b.range.first - a.range.first < 250) {
                val near = { m: MatchResult -> designators.minByOrNull { kotlin.math.abs(it.range.first - m.range.first) }?.takeIf { kotlin.math.abs(it.range.first - m.range.first) < 220 } }
                put(leg(a, b, near(a)))
                var j = i + 1
                while (j + 1 < tokens.size) {
                    val c = tokens[j]; val n = tokens[j + 1]
                    val sep = text.substring(c.range.last + 1, n.range.first)
                    if (n.value == c.value || sep.length > 14 || !chainSeparator.matches(sep)) break
                    put(leg(c, n, near(c)))
                    j++
                }
                i = j + 1
            } else i++
        }
        return out
    }
}
