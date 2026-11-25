package dev.mriya.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import dev.mriya.domain.AirportDb
import dev.mriya.domain.haversineKm
import dev.mriya.domain.imports.BarcodeDecoder
import dev.mriya.domain.imports.ConvertedFlight
import dev.mriya.domain.imports.EventFlights
import dev.mriya.domain.imports.FlightExtractor
import dev.mriya.domain.imports.Ics
import dev.mriya.domain.imports.ParsedFlight
import dev.mriya.domain.imports.RawEvent
import dev.mriya.domain.imports.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An Android calendar synced to the phone (Google, Exchange, local, etc.). */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val visible: Boolean,
    val syncEvents: Boolean,
)

/** Returns true for obvious non-flight calendars such as public holidays or contacts birthdays. */
fun isAuxiliaryCalendar(cal: DeviceCalendar): Boolean {
    val name = cal.displayName.lowercase()
    val acct = cal.accountName.lowercase()
    val type = cal.accountType.lowercase()
    if (acct.contains("#holiday@group.v.calendar.google.com") || acct.contains("#contacts@group.v.calendar.google.com")) {
        return true
    }
    if (type.contains("#holiday") || type.contains("#contacts")) return true
    return name.contains("holiday") ||
        name.contains("birthday") ||
        name.contains("contacts") ||
        name.contains("week number") ||
        name.contains("moon phase")
}

/** Picks primary visible calendars by default, falling back sensibly if none match. */
fun defaultSelectedCalendarIds(calendars: List<DeviceCalendar>): Set<Long> {
    val visiblePrimary = calendars.filter { it.visible && !isAuxiliaryCalendar(it) }.mapTo(LinkedHashSet()) { it.id }
    if (visiblePrimary.isNotEmpty()) return visiblePrimary
    val primary = calendars.filter { !isAuxiliaryCalendar(it) }.mapTo(LinkedHashSet()) { it.id }
    if (primary.isNotEmpty()) return primary
    return calendars.mapTo(LinkedHashSet()) { it.id }
}

/**
 * Turns things the user shares into [ParsedFlight]s. All parsing is local: no network, no accounts, and the shared
 * content is treated as untrusted data (size limits, never executed, no links followed).
 */
class FlightImportService(context: Context) {
    private val ctx = context.applicationContext
    private val ocr by lazy { ScreenshotOcr(ctx) }

    class NoFlightsFound(message: String) : Exception(message)

    private suspend fun airports() = AirportDb.get(ctx)

    /** Text shared from a mail app, pasted, or the contents of an .ics / .eml file. */
    suspend fun fromText(text: String, today: LocalDate = LocalDate.now()): List<ParsedFlight> = withContext(Dispatchers.Default) {
        val db = airports()
        val ics = if (text.contains("BEGIN:VEVENT", ignoreCase = true)) Ics.parse(text) else emptyList()
        val found = if (ics.isNotEmpty()) ics.flatMap { EventFlights.fromEvent(it, today, db::isAirport) }.map { it.copy(source = if (it.source == "calendar") "ics" else it.source) }
        else FlightExtractor.extract(text, today, db::isAirport)
        if (found.isEmpty()) throw NoFlightsFound("No flights found in that text.")
        found
    }

    /**
     * Many booking emails at once (a mailbox search). One flight usually arrives several times (confirmation, check-in
     * reminder, receipt, a forward), so legs on the same route within a day are merged, keeping the best reading.
     */
    suspend fun fromEmails(texts: List<String>, today: LocalDate = LocalDate.now()): List<ParsedFlight> = withContext(Dispatchers.Default) {
        val db = airports()
        val rank = { f: ParsedFlight -> (if (f.source != "text") 2 else 0) + (if (f.flightNumber != null) 1 else 0) + (if (f.departTime != null) 1 else 0) }
        val merged = ArrayList<ParsedFlight>()
        for (t in texts) for (f in FlightExtractor.extract(t, today, db::isAirport)) {
            val i = merged.indexOfFirst {
                it.from == f.from && it.to == f.to && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, f.date)) <= 1
            }
            if (i < 0) merged += f else if (rank(f) > rank(merged[i])) merged[i] = f
        }
        if (merged.isEmpty()) throw NoFlightsFound("No flights found in ${texts.size} ${if (texts.size == 1) "email" else "emails"}.")
        merged
    }

    /** A shared file: PDF or image (boarding-pass barcode or flight screenshot OCR), or a text-like file (.ics, .eml, .txt). */
    suspend fun fromUri(uri: Uri): List<ParsedFlight> = withContext(Dispatchers.IO) {
        val mime = (ctx.contentResolver.getType(uri) ?: "").lowercase()
        val name = uri.lastPathSegment?.lowercase() ?: ""
        when {
            mime == "application/pdf" || name.endsWith(".pdf") -> fromImages(pdfBitmaps(uri))
            mime.startsWith("image/") -> fromImages(listOfNotNull(imageBitmap(uri)))
            else -> fromText(readBytes(uri, 2_000_000).toString(Charsets.UTF_8))
        }
    }

    /** Batch-imports up to 20 selected/shared files or screenshots, keeping any readable flights. */
    suspend fun fromUris(uris: List<Uri>): List<ParsedFlight> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw NoFlightsFound("No files selected.")
        val combined = LinkedHashMap<String, ParsedFlight>()
        var lastError: NoFlightsFound? = null
        for (uri in uris.take(20)) {
            try {
                for (f in fromUri(uri)) {
                    combined.putIfAbsent("${f.from}${f.to}${f.date}${f.flightNumber}", f)
                }
            } catch (e: NoFlightsFound) {
                lastError = e
            }
        }
        val out = combined.values.toList()
        if (out.isEmpty()) throw (lastError ?: NoFlightsFound("No flights found in those files."))
        out
    }

    private suspend fun fromImages(bitmaps: List<Bitmap>): List<ParsedFlight> {
        val db = airports()
        val today = LocalDate.now()
        val foundMap = LinkedHashMap<String, ParsedFlight>()
        try {
            for (bmp in bitmaps) {
                val barcodeTexts = scan(bmp)
                val barcodeFlights = barcodeTexts.flatMap { FlightExtractor.extract(it, today, db::isAirport) }
                if (barcodeFlights.isNotEmpty()) {
                    barcodeFlights.forEach { foundMap.putIfAbsent("${it.from}${it.to}${it.date}${it.flightNumber}", it) }
                    continue
                }
                var bestScore = -1
                var bestExtracted: List<ParsedFlight> = emptyList()
                for (ocrText in ocr.recognizeVariants(bmp)) {
                    val extracted = FlightExtractor.extract(ocrText, today, db::isAirport)
                    if (extracted.isEmpty()) continue
                    val score = extracted.sumOf {
                        (if (it.airline != null && it.flightNumber != null) 4 else 0) +
                            (if (it.date != today) 2 else 0) + 1
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestExtracted = extracted
                    }
                    val complete = extracted.all { it.airline != null && it.flightNumber != null && it.date != today }
                    if (complete) break
                }
                if (bestExtracted.isNotEmpty()) {
                    bestExtracted.forEach { f ->
                        val verifiedOcr = when {
                            f.airline != null && f.flightNumber != null && f.source == "text" -> f.copy(source = "ocr")
                            f.source == "text" -> f.copy(source = "ocr-unverified")
                            else -> f
                        }
                        foundMap.putIfAbsent("${verifiedOcr.from}${verifiedOcr.to}${verifiedOcr.date}${verifiedOcr.flightNumber}", verifiedOcr)
                    }
                }
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
        val found = foundMap.values.toList()
        if (found.isEmpty()) {
            throw NoFlightsFound("No boarding-pass barcode or readable flight route found in that image. Make sure both 3-letter airport codes (like ORD and SJD) are visible.")
        }
        return found
    }

    private fun scan(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return BarcodeDecoder.decode(bitmap.width, bitmap.height, pixels)
    }

    private fun readBytes(uri: Uri, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                if (out.size() + n > max) throw NoFlightsFound("That file is too large to import.")
                out.write(buf, 0, n)
            }
        } ?: throw NoFlightsFound("Couldn't open that file.")
        return out.toByteArray()
    }

    /** Decodes a downsampled copy, so a huge photo can't exhaust memory. */
    private fun imageBitmap(uri: Uri): Bitmap? {
        val bytes = readBytes(uri, 25_000_000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Renders up to the first 4 pages of a PDF for barcode scanning. */
    private fun pdfBitmaps(uri: Uri): List<Bitmap> {
        val tmp = java.io.File(ctx.cacheDir, "import.pdf")
        try {
            tmp.writeBytes(readBytes(uri, 25_000_000))
            val out = mutableListOf<Bitmap>()
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until minOf(r.pageCount, 4)) r.openPage(i).use { page ->
                        val scale = 1800f / maxOf(page.width, page.height)
                        val bmp = createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out += bmp
                    }
                }
            }
            return out
        } catch (e: NoFlightsFound) { throw e }
        catch (e: Exception) { throw NoFlightsFound("Couldn't read that PDF.") }
        finally { tmp.delete() }
    }

    // ---- calendar ------------------------------------------------------------------------

    fun hasCalendarPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Lists calendars currently synced to Android's local calendar provider (non-auxiliary first). */
    suspend fun listCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) throw NoFlightsFound("Calendar access was not granted.")
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val out = mutableListOf<DeviceCalendar>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += DeviceCalendar(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty().ifBlank { "Calendar" },
                    accountName = c.getString(2).orEmpty(),
                    accountType = c.getString(3).orEmpty(),
                    color = c.getInt(4),
                    visible = c.getInt(5) == 1,
                    syncEvents = c.getInt(6) == 1,
                )
            }
        }
        out.sortedWith(
            compareBy<DeviceCalendar>(
                { isAuxiliaryCalendar(it) },
                { !it.visible },
                { !it.syncEvents },
                { it.accountName.lowercase() },
                { it.displayName.lowercase() },
            )
        )
    }

    /** Flight-looking events from the selected calendars synced to this phone (newest first, streaming). */
    suspend fun fromCalendar(
        calendarIds: Set<Long>? = null,
        lookbackYears: Int? = null,
    ): List<ParsedFlight> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) throw NoFlightsFound("Calendar access was not granted.")
        if (calendarIds != null && calendarIds.isEmpty()) throw NoFlightsFound("Select at least one calendar to scan.")
        val db = airports()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val from = if (lookbackYears == null) 1L else now - lookbackYears.toLong() * 366L * 86_400_000
        val to = now + 540L * 86_400_000
        val selection = buildString {
            append("${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.DTSTART} BETWEEN ? AND ?")
            if (calendarIds != null) {
                append(" AND ${CalendarContract.Events.CALENDAR_ID} IN (")
                append(calendarIds.joinToString(",") { "?" })
                append(')')
            }
        }
        val selectionArgs = buildList {
            add(from.toString())
            add(to.toString())
            calendarIds?.forEach { add(it.toString()) }
        }.toTypedArray()
        val proj = arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
        )
        val calAccountTypeById = runCatching { listCalendars().associate { it.id to it.accountType } }.getOrDefault(emptyMap())
        val hint = Regex("flight|boarding|airlines?|airways|✈", RegexOption.IGNORE_CASE)
        val foundMap = LinkedHashMap<String, ParsedFlight>()
        val flightishTitles = mutableListOf<String>()
        var googleStubCount = 0
        var scanned = 0
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj,
            selection, selectionArgs, "${CalendarContract.Events.DTSTART} DESC",
        )?.use { c ->
            while (c.moveToNext() && scanned < 50_000) {
                scanned++
                val start = c.getLong(3)
                val end = if (c.isNull(4)) null else c.getLong(4)
                val allDay = c.getInt(5) == 1
                val calId = c.getLong(6)
                val isGoogleCal = calAccountTypeById[calId].equals("com.google", ignoreCase = true)
                val title = c.getString(0) ?: ""
                val desc = c.getString(1) ?: ""
                val loc = c.getString(2) ?: ""
                val ev = RawEvent(
                    title, desc, loc,
                    if (allDay) null else Instant.ofEpochMilli(start),
                    if (allDay || end == null) null else Instant.ofEpochMilli(end),
                    if (allDay) Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    else Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
                )
                val extracted = EventFlights.fromEvent(ev, today, db::isAirport)
                if (extracted.isNotEmpty()) {
                    for (rawF in extracted) {
                        val tagged = if (isGoogleCal && rawF.source == "calendar") rawF.copy(source = "google-calendar") else rawF
                        foundMap.putIfAbsent("${tagged.from}${tagged.to}${tagged.date}${tagged.flightNumber}", tagged)
                    }
                } else {
                    if (desc.contains("use the official Google Calendar app", ignoreCase = true)) {
                        googleStubCount++
                    }
                    if (flightishTitles.size < 8 && hint.containsMatchIn("$title $desc")) {
                        val clean = title.trim()
                        if (clean.isNotEmpty() && clean !in flightishTitles) flightishTitles += clean
                    }
                }
            }
        }
        val found = foundMap.values.toList()
        if (found.isEmpty()) {
            val selectedLabels = runCatching {
                listCalendars()
                    .filter { calendarIds == null || it.id in calendarIds }
                    .map { cal ->
                        if (cal.accountName.isBlank() || cal.accountName.equals(cal.displayName, ignoreCase = true)) {
                            cal.displayName
                        } else {
                            "${cal.accountName} (${cal.displayName})"
                        }
                    }
            }.getOrDefault(emptyList())
            throw NoFlightsFound(calendarReport(scanned, flightishTitles, googleStubCount, selectedLabels))
        }
        found
    }

    /**
     * Explains why a calendar scan found nothing instead of a bare "no flights": whether the selected
     * calendars are empty/unsynced, which flight-looking events lacked airport codes, or whether Google
     * Calendar masked Gmail-imported details from Android's local database. Shown on the phone only.
     */
    private fun calendarReport(
        scanned: Int,
        flightishTitles: List<String>,
        googleStubCount: Int,
        selectedLabels: List<String>,
    ): String {
        val scope = when {
            selectedLabels.isEmpty() -> "selected calendars"
            selectedLabels.size <= 4 -> selectedLabels.joinToString(", ")
            else -> selectedLabels.take(4).joinToString(", ") + " (+${selectedLabels.size - 4} more)"
        }
        if (scanned == 0) {
            return "Scanned 0 events in $scope. Mriya reads calendars synced to this phone offline—it does not sign in to Google or Proton. " +
                "Turn on Calendar sync for that account in Android Settings -> Passwords & accounts, or share/open an .ics file instead."
        }
        return buildString {
            append("Scanned $scanned events across $scope; none held a flight Mriya could read (needs two 3-letter airport codes like ORD to TPE).")
            if (flightishTitles.isNotEmpty()) {
                append(" Flight-like events missing readable airports: ")
                append(flightishTitles.take(5).joinToString("; ") { "“" + it.take(60) + "”" })
                append('.')
            }
            if (googleStubCount > 0) {
                append(" Note: Google Calendar hides full flight details of auto-created Gmail events from Android's local calendar database ($googleStubCount stub events seen). Share or paste the booking email, or import an .ics file instead.")
            }
        }
    }

    // ---- conversion ----------------------------------------------------------------------

    /** Gives a parsed flight coordinates (from the bundled airport list) and times, estimating arrival if unknown. */
    suspend fun toFlights(parsed: List<ParsedFlight>): List<Flight> = toConvertedFlights(parsed).map { it.flight }

    /** Converts parsed flights while preserving whether a match came from unverified plain-text heuristics. */
    suspend fun toConvertedFlights(parsed: List<ParsedFlight>): List<ConvertedFlight> {
        val db = airports()
        return parsed.mapNotNull { p ->
            val a = db.byIata(p.from) ?: return@mapNotNull null
            val b = db.byIata(p.to) ?: return@mapNotNull null
            // Real airport coordinates are always stored; hiding Russia on the globe is applied only when drawing.
            val from = LatLon(a.lat, a.lon)
            val to = LatLon(b.lat, b.lon)
            val dist = haversineKm(from, to)
            val depart = (p.departAt ?: Times.systemInstant(p.date, p.departTime)).epochSecond
            val arrive = p.arriveAt?.epochSecond?.takeIf { it > depart } ?: (depart + (dist / 750.0 * 3600).toLong() + 1800)
            val origin = when (p.source) {
                "google-calendar" -> "google-calendar"
                "calendar" -> "calendar"
                "ocr", "ocr-unverified" -> "screenshot"
                "boarding-pass" -> "boarding-pass"
                "ics" -> "ics"
                "json-ld", "text", "email-resolved" -> "email"
                else -> null
            }
            val flight = Flight(
                from = from, to = to, departTs = depart, arriveTs = arrive, distanceKm = dist,
                fromAirport = p.from, toAirport = p.to, airline = p.airline, flightNumber = p.flightNumber,
                source = "imported", importOrigin = origin,
            )
            ConvertedFlight(flight = flight, unverified = p.source == "text" || p.source == "ocr-unverified")
        }
    }
}
