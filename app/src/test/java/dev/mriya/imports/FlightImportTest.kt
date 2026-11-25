package dev.mriya.imports

import dev.mriya.data.DeviceCalendar
import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.defaultSelectedCalendarIds
import dev.mriya.data.isAuxiliaryCalendar
import dev.mriya.data.originLabel
import dev.mriya.domain.imports.EventFlights
import dev.mriya.domain.imports.FlightExtractor
import dev.mriya.domain.imports.Ics
import dev.mriya.domain.imports.mergeFlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class FlightImportTest {
    private val airports = setOf("ORD", "SFO", "LHR", "FRA", "YUL", "DEN", "KBP", "MUC", "SJD", "HAN")
    private val isAirport: (String) -> Boolean = { it in airports }
    private val ref = LocalDate.of(2026, 8, 1)

    @Test
    fun `IATA sample boarding pass`() {
        // The example from the IATA BCBP specification: Montreal to Frankfurt, Air Canada 834, day 226.
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 226F001A0025 100"
        val f = FlightExtractor.extract(raw, ref, isAirport).single()
        assertEquals("YUL", f.from); assertEquals("FRA", f.to)
        assertEquals("AC", f.airline); assertEquals("834", f.flightNumber)
        assertEquals(LocalDate.of(2026, 8, 14), f.date)
        assertEquals("boarding-pass", f.source)
    }

    @Test
    fun `boarding pass with unknown airports is rejected`() {
        assertTrue(FlightExtractor.extract("M1DOE/JOHN             EXYZ789 AAABBBXX 0001 100Y001A0001 100", ref, isAirport).isEmpty())
    }

    @Test
    fun `schema org JSON-LD flight reservation`() {
        val html = """
            <html><body><p>Your trip</p>
            <script type="application/ld+json">
            {"@context":"http://schema.org","@type":"FlightReservation","reservationFor":{"@type":"Flight",
             "flightNumber":"0123","airline":{"@type":"Airline","iataCode":"UA"},
             "departureAirport":{"@type":"Airport","iataCode":"ORD"},"arrivalAirport":{"@type":"Airport","iataCode":"SFO"},
             "departureTime":"2026-03-12T08:30:00-05:00","arrivalTime":"2026-03-12T11:05:00-07:00"}}
            </script></body></html>
        """.trimIndent()
        val f = FlightExtractor.extract(html, ref, isAirport).single()
        assertEquals("ORD", f.from); assertEquals("SFO", f.to)
        assertEquals("UA", f.airline); assertEquals("123", f.flightNumber)
        assertEquals(LocalDate.of(2026, 3, 12), f.date)
        assertNotNull(f.departAt); assertNotNull(f.arriveAt)
        assertEquals("json-ld", f.source)
    }

    @Test
    fun `quoted-printable eml with JSON-LD is decoded`() {
        val eml = "Content-Transfer-Encoding: quoted-printable\n\n" +
            "<script type=3D\"application/ld+json\">{\"@type\":\"FlightReservation\",\"reservationFor\":{\"flightNumber\":\"55\",=\n" +
            "\"airline\":{\"iataCode\":\"LH\"},\"departureAirport\":{\"iataCode\":\"FRA\"},\"arrivalAirport\":{\"iataCode\":\"ORD\"},=\n" +
            "\"departureTime\":\"2026-05-02T10:00:00+02:00\"}}</script>"
        val f = FlightExtractor.extract(eml, ref, isAirport).single()
        assertEquals("FRA", f.from); assertEquals("ORD", f.to); assertEquals("55", f.flightNumber)
    }

    @Test
    fun `plain text booking with two flights`() {
        val text = """
            Outbound  Thu, Mar 12
            UA 123  Chicago (ORD) 8:30 AM  to  San Francisco (SFO) 11:05 AM
            Return  Mon, Mar 16
            UA 456  San Francisco (SFO) 2:15 PM  to  Chicago (ORD) 8:20 PM
        """.trimIndent()
        val f = FlightExtractor.extract(text, LocalDate.of(2026, 3, 1), isAirport)
        assertEquals(2, f.size)
        assertEquals("ORD", f[0].from); assertEquals("SFO", f[0].to); assertEquals("123", f[0].flightNumber)
        assertEquals(LocalDate.of(2026, 3, 12), f[0].date); assertEquals(LocalTime.of(8, 30), f[0].departTime)
        assertEquals("SFO", f[1].from); assertEquals("ORD", f[1].to); assertEquals("456", f[1].flightNumber)
        assertEquals(LocalDate.of(2026, 3, 16), f[1].date); assertEquals(LocalTime.of(14, 15), f[1].departTime)
    }

    @Test
    fun `ordinary words are not mistaken for airports`() {
        assertTrue(FlightExtractor.extract("THE AND FOR you can see the new day", ref, isAirport).isEmpty())
    }

    @Test
    fun `ics event is a flight and uses the event times`() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Flight UA 123 ORD-SFO\nDTSTART;TZID=America/Chicago:20260312T083000\n" +
            "DTEND;TZID=America/Chicago:20260312T110500\nLOCATION:O'Hare\nEND:VEVENT\nEND:VCALENDAR"
        val ev = Ics.parse(ics).single()
        val f = EventFlights.fromEvent(ev, ref, isAirport).single()
        assertEquals("ORD", f.from); assertEquals("SFO", f.to); assertEquals("123", f.flightNumber)
        assertEquals(LocalDate.of(2026, 3, 12), f.date)
        assertNotNull(f.departAt); assertNotNull(f.arriveAt)
        assertEquals("calendar", f.source)
    }

    @Test
    fun `calendar event that merely mentions airports is not a flight`() {
        val ev = Ics.parse("BEGIN:VEVENT\nSUMMARY:Team sync ORD SFO LHR offices\nDTSTART:20260312T150000Z\nEND:VEVENT").single()
        assertTrue(EventFlights.fromEvent(ev, ref, isAirport).isEmpty())
    }

    private fun flight(from: String, to: String, ts: Long, source: String = "detected", num: String? = null) = Flight(
        LatLon(0.0, 0.0), LatLon(1.0, 1.0), ts, ts + 7200, 1000.0, fromAirport = from, toAirport = to, source = source, flightNumber = num, airline = if (num != null) "UA" else null,
    )

    @Test
    fun `an import matching a detected flight enriches it instead of doubling it`() {
        val detected = listOf(flight("ORD", "SFO", 1_000_000))
        val imported = listOf(flight("ORD", "SFO", 1_000_000 + 3600, "imported", "123"))
        val merged = mergeFlights(detected, imported)
        assertEquals(1, merged.size)
        assertEquals("123", merged[0].flightNumber); assertEquals("merged", merged[0].source)
    }

    @Test
    fun `an unmatched import is added as its own flight`() {
        val merged = mergeFlights(listOf(flight("ORD", "SFO", 1_000_000)), listOf(flight("FRA", "ORD", 9_000_000, "imported", "55")))
        assertEquals(2, merged.size)
        assertEquals("imported", merged.last().source)
    }

    @Test
    fun `auxiliary calendars are excluded by default unless no primary calendar exists`() {
        val personal = DeviceCalendar(1L, "user@example.com", "user@example.com", "com.google", 0xFF4285F4.toInt(), visible = true, syncEvents = true)
        val work = DeviceCalendar(2L, "Work Trips", "work@company.com", "com.google", 0xFF0F9D58.toInt(), visible = true, syncEvents = true)
        val hiddenPrimary = DeviceCalendar(3L, "Old Trips", "old@example.com", "com.google", 0xFF795548.toInt(), visible = false, syncEvents = true)
        val holidays = DeviceCalendar(4L, "Holidays in United States", "en.usa#holiday@group.v.calendar.google.com", "com.google", 0xFF0B8043.toInt(), visible = true, syncEvents = true)
        val birthdays = DeviceCalendar(5L, "Birthdays", "addressbook#contacts@group.v.calendar.google.com", "com.google", 0xFF8E24AA.toInt(), visible = true, syncEvents = true)

        assertFalse(isAuxiliaryCalendar(personal))
        assertFalse(isAuxiliaryCalendar(work))
        assertTrue(isAuxiliaryCalendar(holidays))
        assertTrue(isAuxiliaryCalendar(birthdays))

        assertEquals(setOf(1L, 2L), defaultSelectedCalendarIds(listOf(personal, work, hiddenPrimary, holidays, birthdays)))
        assertEquals(setOf(3L), defaultSelectedCalendarIds(listOf(hiddenPrimary, holidays, birthdays)))
        assertEquals(setOf(4L, 5L), defaultSelectedCalendarIds(listOf(holidays, birthdays)))
    }

    @Test
    fun `google wallet screenshot OCR text parses yearless month-day flights`() {
        val aaWallet = """
            American Airlines
            Chicago trip
            Confirmation code: #SHUIFJ
            Nov 18 • AA 2636
            SJD ✈ ORD
        """.trimIndent()
        val f1 = FlightExtractor.extract(aaWallet, LocalDate.of(2026, 11, 25), isAirport).single()
        assertEquals("SJD", f1.from)
        assertEquals("ORD", f1.to)
        assertEquals("AA", f1.airline)
        assertEquals("2636", f1.flightNumber)
        assertEquals(LocalDate.of(2026, 11, 18), f1.date)

        val brWallet = """
            EVA Air
            Hanoi trip
            Confirmation code: #BFB9RR
            Apr 11 • BR 55
            ORD ✈ HAN
            Unarchive
        """.trimIndent()
        val f2 = FlightExtractor.extract(brWallet, LocalDate.of(2026, 11, 25), isAirport).single()
        assertEquals("ORD", f2.from)
        assertEquals("HAN", f2.to)
        assertEquals("BR", f2.airline)
        assertEquals("55", f2.flightNumber)
        assertEquals(LocalDate.of(2026, 4, 11), f2.date)
    }

    @Test
    fun `OCR glyph noise and full-year date precedence work properly`() {
        val noisy = "Nov 18 AA 2636\nSJD + ORD\nApr 11 BR 55\nORD > HAN"
        val extracted = FlightExtractor.extract(noisy, LocalDate.of(2026, 11, 25), isAirport)
        assertEquals(2, extracted.size)
        assertEquals("SJD", extracted[0].from)
        assertEquals("ORD", extracted[0].to)
        assertEquals("AA", extracted[0].airline)
        assertEquals("2636", extracted[0].flightNumber)
        assertEquals("ORD", extracted[1].from)
        assertEquals("HAN", extracted[1].to)
        assertEquals("BR", extracted[1].airline)
        assertEquals("55", extracted[1].flightNumber)

        val datedText = "2024-11-18 SJD to ORD\nMar 12, 2025 ORD to SFO"
        val dated = FlightExtractor.extract(datedText, LocalDate.of(2026, 11, 25), isAirport)
        assertEquals(2, dated.size)
        assertEquals(LocalDate.of(2024, 11, 18), dated[0].date)
        assertEquals(LocalDate.of(2025, 3, 12), dated[1].date)
    }

    @Test
    fun `flight origin labels and mergeFlights preserve importOrigin`() {
        assertEquals("Dawarich", flight("ORD", "SFO", 1_000_000, "detected").originLabel())
        assertEquals("Google Takeout", flight("ORD", "SFO", 1_000_000, "takeout").originLabel())
        assertEquals("Screenshot", flight("ORD", "SFO", 1_000_000, "imported").copy(importOrigin = "screenshot").originLabel())
        assertEquals("Google Calendar", flight("ORD", "SFO", 1_000_000, "imported").copy(importOrigin = "google-calendar").originLabel())
        assertEquals("Boarding pass", flight("ORD", "SFO", 1_000_000, "imported").copy(importOrigin = "boarding-pass").originLabel())
        assertEquals("Email", flight("ORD", "SFO", 1_000_000, "imported").copy(importOrigin = "email").originLabel())
        assertEquals("Manual", flight("ORD", "SFO", 1_000_000, "imported").copy(importOrigin = "manual").originLabel())

        val detected = listOf(flight("SJD", "ORD", 1_000_000, "detected"))
        val screenshot = listOf(flight("SJD", "ORD", 1_001_000, "imported", "2636").copy(importOrigin = "screenshot"))
        val merged = mergeFlights(detected, screenshot).single()
        assertEquals("merged", merged.source)
        assertEquals("screenshot", merged.importOrigin)
        assertEquals("Dawarich + Screenshot", merged.originLabel())
    }
}
