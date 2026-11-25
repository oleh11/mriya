package dev.mriya.data

import android.content.Context
import androidx.core.content.edit
import dev.mriya.domain.AirportDb
import dev.mriya.domain.CountryShapes
import dev.mriya.domain.FlightDetector
import dev.mriya.domain.OfflineCountryLookup
import dev.mriya.domain.applyUserData
import dev.mriya.domain.imports.mergeFlights
import dev.mriya.domain.imports.stampFlightCountries
import dev.mriya.domain.takeout.mergeTakeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.coroutineContext

class PassportRepository(context: Context) {
    private val ctx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = File(ctx.filesDir, "passport.json")
    private val prefs get() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets by lazy { SecureStore(ctx) }

    var serverUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v) }
    var apiKey: String
        get() = secrets.get("api_key")
        set(v) = secrets.put("api_key", v)
    var legacyQueryAuth: Boolean
        get() = prefs.getBoolean("legacy_query_auth", false)
        set(v) = prefs.edit { putBoolean("legacy_query_auth", v) }
    var flightLookupProvider: FlightLookupProvider
        get() = FlightLookupProvider.fromId(prefs.getString("lookup_provider", null))
        set(v) = prefs.edit { putString("lookup_provider", v.id) }
    var aeroDataBoxHost: String
        get() = prefs.getString("aerodatabox_host", FlightLookupApi.DEFAULT_AERODATABOX_HOST)
            ?: FlightLookupApi.DEFAULT_AERODATABOX_HOST
        set(v) = prefs.edit { putString("aerodatabox_host", v.trim().ifEmpty { FlightLookupApi.DEFAULT_AERODATABOX_HOST }) }
    var flightLookupApiKey: String
        get() = secrets.get("flight_lookup_api_key")
        set(v) = secrets.put("flight_lookup_api_key", v)
    var adsbEnabled: Boolean
        get() = prefs.getBoolean("adsb_enabled", false)
        set(v) = prefs.edit { putBoolean("adsb_enabled", v) }
    var adsbFeed: AdsbFeed
        get() = AdsbFeed.fromId(prefs.getString("adsb_feed", null))
        set(v) = prefs.edit { putString("adsb_feed", v.id) }
    var hideRussia: Boolean
        get() = prefs.getBoolean("hide_russia", prefs.getBoolean("hide_mordor", false)) // old key read as a fallback
        set(v) = prefs.edit { putBoolean("hide_russia", v).remove("hide_mordor") }

    /** Release builds only talk to HTTPS servers; debug builds also allow local http for development. */
    fun isUrlAllowed(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || (dev.mriya.BuildConfig.DEBUG && u.startsWith("http://"))
    }

    // ---- storage -------------------------------------------------------------------------


    private fun readRaw(): PassportData =
        AtomicFileStore.readWithBackup(file) { raw ->
            json.decodeFromString(PassportData.serializer(), raw).takeIf { it.schema == PassportData.SCHEMA }
                ?: error("Schema mismatch")
        } ?: PassportData()

    /** Write to a temp file, fsync, preserve `.bak`, then atomically replace. */
    private fun saveAtomic(d: PassportData) {
        AtomicFileStore.writeAtomicWithBackup(file, json.encodeToString(PassportData.serializer(), d).toByteArray())
    }

    private suspend fun enrich(d: PassportData): PassportData {
        val airports = AirportDb.get(ctx)
        fun fix(f: Flight) = if (f.fromAirport != null && f.toAirport != null) f else f.copy(
            fromAirport = f.fromAirport ?: airports.nearest(f.from)?.iata,
            toAirport = f.toAirport ?: airports.nearest(f.to)?.iata,
        )
        return d.copy(flights = d.flights.map(::fix))
    }

    /** Loads off the main thread. Flights get airport codes filled in lazily. */
    // ---- imported flights (mail, calendar, boarding passes) ----------------------------------
    // Kept in their own file: they are user-entered, so "Clear cache & resync" must not erase them.

    private val importsFile get() = File(ctx.filesDir, "imports.json")
    private val importsSerializer = ListSerializer(Flight.serializer())

    private fun readImports(): List<Flight> =
        AtomicFileStore.readWithBackup(importsFile) { raw ->
            json.decodeFromString(importsSerializer, raw)
        } ?: emptyList()

    private fun saveImports(list: List<Flight>) {
        AtomicFileStore.writeAtomicWithBackup(importsFile, json.encodeToString(importsSerializer, list).toByteArray())
    }

    /** Detected flights plus imports; imports enrich matching flights and stamp their airport countries. */
    private fun withImports(d: PassportData, airports: AirportDb, userData: UserData): PassportData {
        val imports = readImports()
        return d.copy(
            flights = mergeFlights(d.flights, imports),
            stamps = stampFlightCountries(d.stamps, imports, userData.dismissedFlights) { airports.byIata(it)?.country },
        )
    }

    // ---- Google Takeout derived stamps and flights --------------------------------------------
    // Kept in its own file so "Clear cache & resync" does not erase imported Takeout history.

    private val takeoutFile get() = File(ctx.filesDir, "takeout.json")

    private fun readTakeout(): TakeoutData? =
        AtomicFileStore.readWithBackup(takeoutFile) { raw ->
            json.decodeFromString(TakeoutData.serializer(), raw)
        }

    private fun saveTakeout(t: TakeoutData) {
        AtomicFileStore.writeAtomicWithBackup(takeoutFile, json.encodeToString(TakeoutData.serializer(), t).toByteArray())
    }

    suspend fun loadTakeout(): TakeoutData? = withContext(Dispatchers.IO) { lock.withLock { readTakeout() } }

    suspend fun saveTakeoutData(t: TakeoutData): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            saveTakeout(t)
            view(readRaw())
        }
    }

    suspend fun clearTakeout(): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            AtomicFileStore.deleteWithBackup(takeoutFile)
            view(readRaw())
        }
    }

    suspend fun snapshotForBackup(): Triple<UserData, List<Flight>, TakeoutData?> = withContext(Dispatchers.IO) {
        lock.withLock { Triple(readUser(), readImports(), readTakeout()) }
    }

    suspend fun restoreFromBackup(userData: UserData, imports: List<Flight>, takeout: TakeoutData?): PassportData =
        withContext(Dispatchers.IO) {
            lock.withLock {
                saveUser(userData)
                saveImports(imports)
                if (takeout != null) saveTakeout(takeout) else AtomicFileStore.deleteWithBackup(takeoutFile)
                view(readRaw())
            }
        }

    /** Loads off the main thread. Flights get airport codes filled in lazily, and imports/Takeout are merged in. */
    suspend fun load(): PassportData = withContext(Dispatchers.IO) { lock.withLock { view(readRaw()) } }

    // ---- the user's own choices (hidden stamps, dismissed flights, seen trophies) --------------

    private val userFile get() = File(ctx.filesDir, "userdata.json")

    private fun readUser(): UserData =
        AtomicFileStore.readWithBackup(userFile) { raw ->
            json.decodeFromString(UserData.serializer(), raw)
        } ?: UserData()

    private fun saveUser(u: UserData) {
        AtomicFileStore.writeAtomicWithBackup(userFile, json.encodeToString(UserData.serializer(), u).toByteArray())
    }

    /** What the screens see: derived data, plus Takeout, plus imports, plus the user's choices on top. */
    private suspend fun view(d: PassportData): PassportData {
        val airports = AirportDb.get(ctx)
        val userData = readUser()
        val enriched = enrich(d)
        val withTakeout = readTakeout()?.let { mergeTakeout(enriched, it) } ?: enriched
        return applyUserData(withImports(withTakeout, airports, userData), userData)
    }

    /**
     * "I was never there": hides the stamp [key] and dismisses the flights that touch that country.
     * Returns the new data and the flight keys that were newly dismissed, so the action can be undone exactly.
     */
    suspend fun hideCountry(key: String): Pair<PassportData, List<String>> = withContext(Dispatchers.IO) {
        lock.withLock {
            val airports = AirportDb.get(ctx)
            val ud = readUser()
            val raw = readRaw()
            val touching = view(raw).flights.filter { f ->
                !f.dismissed && listOf(f.fromAirport, f.toAirport).any { it != null && airports.byIata(it)?.country == key }
            }.map { it.stableKey() }
            saveUser(ud.copy(hiddenCountries = ud.hiddenCountries + key, dismissedFlights = ud.dismissedFlights + touching))
            view(raw) to touching
        }
    }

    suspend fun restoreCountry(key: String, flightKeys: List<String>): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            val ud = readUser()
            saveUser(ud.copy(hiddenCountries = ud.hiddenCountries - key, dismissedFlights = ud.dismissedFlights - flightKeys.toSet()))
            view(readRaw())
        }
    }

    /** Brings back every hidden stamp and every dismissed flight. */
    suspend fun restoreAllHidden(): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            saveUser(readUser().copy(hiddenCountries = emptySet(), dismissedFlights = emptySet()))
            val raw = readRaw()
            val cleared = raw.copy(flights = raw.flights.map { it.copy(dismissed = false) }).also(::saveAtomic)
            saveImports(readImports().map { it.copy(dismissed = false) })
            view(cleared)
        }
    }

    suspend fun markTrophiesSeen(ids: Set<String>) = withContext(Dispatchers.IO) {
        lock.withLock { readUser().let { saveUser(it.copy(seenTrophies = it.seenTrophies + ids)) } }
    }

    suspend fun saveFlightEdit(flightKey: String, edit: FlightEdit): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            val ud = readUser()
            val updatedEdits = ud.flightEdits.toMutableMap()
            val emptyEdit = edit.airline == null && edit.flightNumber == null &&
                edit.aircraftType == null && edit.callsign == null && edit.registration == null
            if (emptyEdit) updatedEdits.remove(flightKey) else updatedEdits[flightKey] = edit
            saveUser(ud.copy(flightEdits = updatedEdits))
            view(readRaw())
        }
    }

    suspend fun setDismissed(flightId: String, dismissed: Boolean): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            val raw = readRaw()
            val updated = raw.copy(flights = raw.flights.map { if (it.id == flightId) it.copy(dismissed = dismissed) else it })
            saveAtomic(updated)
            val imports = readImports()
            if (imports.any { it.id == flightId }) saveImports(imports.map { if (it.id == flightId) it.copy(dismissed = dismissed) else it })
            // Remember the choice by a stable key too, so it survives a resync or a change in detection.
            val key = view(raw).flights.firstOrNull { it.id == flightId }?.stableKey()
            if (key != null) readUser().let { u ->
                saveUser(u.copy(dismissedFlights = if (dismissed) u.dismissedFlights + key else u.dismissedFlights - key))
            }
            view(updated)
        }
    }

    /** Adds confirmed imports (deduplicated by id, and by route and day) and returns the merged data. */
    suspend fun addImports(flights: List<Flight>): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = { f: Flight -> "${f.fromAirport}-${f.toAirport}-${f.departTs / 86_400}" }
            saveImports((readImports() + flights).distinctBy { it.id + "|" + it.fromAirport + "|" + it.toAirport }.distinctBy(key))
            view(readRaw())
        }
    }

    suspend fun clearData() = withContext(Dispatchers.IO) { lock.withLock { AtomicFileStore.deleteWithBackup(file); Unit } }

    // ---- sync ----------------------------------------------------------------------------

    /**
     * Incremental, resumable sync. Pages stream through the flight detector and country folding; every
     * [CHECKPOINT_PAGES] pages (when no flight is mid-detection) the progress is committed to disk, so a
     * timeout on page 190 keeps the first 180. Cancellable between pages.
     */
    suspend fun sync(onProgress: (page: Int, total: Int) -> Unit, onData: (PassportData) -> Unit): PassportData =
        withContext(Dispatchers.Default) {
            val api = DawarichApi(serverUrl, apiKey, allowQueryFallback = legacyQueryAuth)
            val lookup = OfflineCountryLookup(CountryShapes.get(ctx))
            val airports = AirportDb.get(ctx)
            val start = lock.withLock { readRaw() }
            val detector = FlightDetector(start.lastPoint) { airports.nearest(it, 25.0)?.iata }
            val stamps = start.stamps.toMutableMap()
            val newFlights = mutableListOf<Flight>()
            var lastTs = start.lastSyncTs
            val startAt = if (start.lastSyncTs > 0) start.lastSyncTs + 1 else 0L

            suspend fun commit(stats: DawarichStats? = null): PassportData {
                val d = commitToDisk(stamps, lastTs, detector.lastPoint, newFlights.toList(), stats)
                newFlights.clear()
                return enrich(d).also(onData)
            }

            var page = 1
            var total = 1
            while (page <= total) {
                coroutineContext.ensureActive()
                val res = api.points(startAt, page)
                total = res.totalPages
                for (p in res.points) {
                    detector.feed(p)
                    detector.drain().forEach { f -> newFlights += f; stampAirportCountries(stamps, f, airports) }
                    fold(stamps, p, lookup)
                    if (p.timestamp > lastTs) lastTs = p.timestamp
                }
                onProgress(page, total)
                if (page % CHECKPOINT_PAGES == 0 && page < total && !detector.hasOpenFlight) commit()
                page++
            }
            detector.finish().forEach { f -> newFlights += f; stampAirportCountries(stamps, f, airports) }
            val stats = runCatching { api.stats() }.getOrNull()
            commit(stats)
        }

    private suspend fun commitToDisk(
        stamps: Map<String, CountryStamp>, lastTs: Long, lastPoint: Point?, newFlights: List<Flight>, stats: DawarichStats?,
    ): PassportData = withContext(Dispatchers.IO) {
        lock.withLock {
            val fresh = readRaw() // re-read: the user may have dismissed flights while we were syncing
            fresh.copy(
                lastSyncTs = lastTs, lastPoint = lastPoint, stamps = stamps.toMap(),
                // Airport codes are stored with the flights, so they don't depend on lookups at read time.
                flights = (fresh.flights + newFlights).distinctBy { it.id },
                stats = stats ?: fresh.stats,
            ).let { enrich(it) }.also(::saveAtomic)
        }
    }

    /**
     * Landing in a country proves you were there even when the phone was off through the layover, so each flight
     * also stamps the countries of its two airports (dated by the flight's own times).
     */
    private fun stampAirportCountries(stamps: MutableMap<String, CountryStamp>, f: Flight, airports: AirportDb) {
        listOf(f.from to f.departTs, f.to to f.arriveTs).forEach { (pt, ts) ->
            val iso = airports.nearest(pt, 40.0)?.country?.takeIf { it.length == 2 } ?: return@forEach
            val day = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val cur = stamps[iso]
            stamps[iso] = if (cur == null) {
                CountryStamp(Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH), iso, ts, ts, setOf(day), emptySet())
            } else cur.copy(firstTs = minOf(cur.firstTs, ts), lastTs = maxOf(cur.lastTs, ts), days = cur.days + day)
        }
    }

    /** Stamps are keyed by ISO-2. The polygon lookup covers points with no (or unrecognised) country name. */
    private fun fold(stamps: MutableMap<String, CountryStamp>, p: Point, lookup: OfflineCountryLookup) {
        val raw = p.country?.takeIf { it.isNotBlank() }
        val iso = raw?.let(::isoFor) ?: lookup.isoAt(p.latitude, p.longitude)
        val key = iso ?: raw ?: return
        val name = iso?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) } ?: raw!!
        val day = Instant.ofEpochSecond(p.timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val cur = stamps[key]
        stamps[key] = if (cur == null) {
            CountryStamp(name, iso, p.timestamp, p.timestamp, setOf(day), setOfNotNull(p.city))
        } else cur.copy(
            firstTs = minOf(cur.firstTs, p.timestamp),
            lastTs = maxOf(cur.lastTs, p.timestamp),
            days = cur.days + day,
            cities = if (p.city != null) cur.cities + p.city else cur.cities,
        )
    }

    companion object {
        /**
         * Guards every read-modify-write of the stored files. Process-wide on purpose: the ViewModel and the sync
         * worker each create their own repository, so a per-instance lock would not stop them clobbering each other.
         */
        private val lock = Mutex()

        const val CHECKPOINT_PAGES = 10

        private val isoByName: Map<String, String> by lazy {
            val locales = listOf("en", "uk", "de", "fr", "es", "it", "pl", "ru", "pt", "tr", "nl").map { Locale.forLanguageTag(it) }
            val m = HashMap<String, String>()
            for (iso in Locale.getISOCountries()) for (l in locales) m[Locale.Builder().setRegion(iso).build().getDisplayCountry(l).lowercase()] = iso
            m += mapOf(
                "united states of america" to "US", "usa" to "US", "czechia" to "CZ", "czech republic" to "CZ",
                "russian federation" to "RU", "south korea" to "KR", "korea, republic of" to "KR", "türkiye" to "TR",
                "turkey" to "TR", "uk" to "GB", "great britain" to "GB",
                "united kingdom of great britain and northern ireland" to "GB",
                "north macedonia" to "MK", "macedonia" to "MK", "eswatini" to "SZ", "swaziland" to "SZ",
            )
            m
        }

        fun isoFor(name: String): String? = isoByName[name.trim().lowercase()]
    }
}
