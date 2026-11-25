package dev.mriya.domain.imports

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar event, from an .ics file or the phone's calendar. */
data class RawEvent(
    val title: String,
    val description: String,
    val location: String,
    val start: Instant?,
    val end: Instant?,
    val startDate: LocalDate?,
)

object Ics {
    /** Minimal, forgiving iCalendar reader: just enough VEVENT fields to find flights. */
    fun parse(text: String): List<RawEvent> {
        val lines = text.take(2_000_000).replace(Regex("\r?\n[ \t]"), "").lines()
        val events = mutableListOf<RawEvent>()
        var cur: MutableMap<String, Pair<String, String>>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> cur = mutableMapOf()
                line.equals("END:VEVENT", ignoreCase = true) -> { cur?.let { events += toEvent(it) }; cur = null }
                cur != null -> {
                    val colon = line.indexOf(':'); if (colon < 0) continue
                    val head = line.substring(0, colon); val value = line.substring(colon + 1)
                    val name = head.substringBefore(';').uppercase()
                    cur[name] = head.substringAfter(';', "") to value
                }
            }
        }
        return events
    }

    /** iCalendar text escapes: backslash-n for a newline, backslash-comma, backslash-semicolon, double backslash. */
    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'n', 'N' -> sb.append('\n'); else -> sb.append(n) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private class Moment(val instant: Instant?, val date: LocalDate?)

    private fun moment(params: String, value: String): Moment? {
        val v = value.trim()
        return runCatching {
            when {
                v.length == 8 -> Moment(null, LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt()))
                else -> {
                    val ldt = LocalDateTime.of(
                        v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt(),
                        v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt().coerceAtMost(59),
                    )
                    val zone = if (v.endsWith("Z")) ZoneOffset.UTC else {
                        val tz = Regex("TZID=([^;]+)", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)
                        runCatching { ZoneId.of(tz!!) }.getOrDefault(ZoneId.systemDefault())
                    }
                    Moment(ldt.atZone(zone).toInstant(), ldt.toLocalDate())
                }
            }
        }.getOrNull()
    }

    private fun toEvent(f: Map<String, Pair<String, String>>): RawEvent {
        val s = f["DTSTART"]?.let { moment(it.first, it.second) }
        val e = f["DTEND"]?.let { moment(it.first, it.second) }
        return RawEvent(
            unescape(f["SUMMARY"]?.second ?: ""), unescape(f["DESCRIPTION"]?.second ?: ""), unescape(f["LOCATION"]?.second ?: ""),
            s?.instant, e?.instant, s?.date,
        )
    }
}

object EventFlights {
    private val hint = Regex("""flight|fly\b|flying|airlines?|airways|boarding|✈|\bdepart""", RegexOption.IGNORE_CASE)

    /**
     * Flights in a calendar event. Titles like "Flight UA 123 ORD-SFO" or "✈ ORD → SFO" qualify; an event that merely
     * mentions two airport codes does not, unless it also has a flight-style keyword or designator.
     */
    fun fromEvent(ev: RawEvent, ref: LocalDate, isAirport: (String) -> Boolean): List<ParsedFlight> {
        val text = "${ev.title}\n${ev.location}\n${ev.description}"
        val day = ev.startDate ?: ref
        val found = FlightExtractor.extract(text, day, isAirport)
        val keep = found.filter { it.source != "text" || it.flightNumber != null || hint.containsMatchIn(text) }
        return keep.map { f ->
            val usable = ev.start != null && f.date == day
            val dur = if (ev.start != null && ev.end != null) Duration.between(ev.start, ev.end) else null
            f.copy(
                departAt = f.departAt ?: if (usable) ev.start else null,
                arriveAt = f.arriveAt ?: if (usable && dur != null && dur >= Duration.ofMinutes(30) && dur <= Duration.ofHours(24)) ev.end else null,
                source = if (f.source == "text") "calendar" else f.source,
            )
        }
    }
}
