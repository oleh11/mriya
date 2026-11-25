package dev.mriya.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.net.Uri
import dev.mriya.MriyaApplication
import dev.mriya.data.AdsbApi
import dev.mriya.data.AdsbFeed
import dev.mriya.data.DeviceCalendar
import dev.mriya.data.Flight
import dev.mriya.data.GmailClient
import dev.mriya.data.GmailException
import dev.mriya.data.FlightImportService
import dev.mriya.data.FlightLookupApi
import dev.mriya.data.FlightLookupProvider
import dev.mriya.data.FlightLookupResult
import dev.mriya.data.HolderProfile
import dev.mriya.data.LiveAircraft
import dev.mriya.data.normalizeTrackQuery
import dev.mriya.data.PassportData
import dev.mriya.data.PassportRepository
import dev.mriya.data.ProfileStore
import dev.mriya.data.TakeoutData
import dev.mriya.data.TakeoutImportService
import dev.mriya.data.stableKey
import dev.mriya.domain.AirportDb
import dev.mriya.domain.BackupBundle
import dev.mriya.domain.BackupCrypto
import dev.mriya.domain.buildFlightEditForInputs
import dev.mriya.domain.imports.completePartialLegs
import dev.mriya.domain.imports.ImportCandidate
import dev.mriya.domain.imports.MOSKVA_MESSAGE
import dev.mriya.domain.imports.ManualFlightInput
import dev.mriya.domain.imports.ManualFlightResult
import dev.mriya.domain.imports.ParsedFlight
import dev.mriya.domain.imports.buildImportCandidates
import dev.mriya.sync.SyncScheduler
import dev.mriya.sync.SyncWorker
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Base64

data class UiState(
    val data: PassportData = PassportData(),
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    /** Flight just dismissed, so the UI can offer Undo. */
    val lastDismissed: Flight? = null,
    /** Country just removed (with the flights hidden along with it), so the UI can offer Undo. */
    val lastHidden: HiddenUndo? = null,
    val continents: Map<String, String> = emptyMap(),
    /** Set when new trophies were earned; shown once as a message. */
    val trophyMessage: String? = null,
    // Flight import (share / file / calendar): candidates wait for the user's confirmation.
    val importing: Boolean = false,
    val candidates: List<ImportCandidate>? = null,
    val importMessage: String? = null,
    val deviceCalendars: List<DeviceCalendar>? = null,
    // Google Takeout import: derived stamps and flights stored separately from Dawarich cache.
    val takeout: TakeoutData? = null,
    val takeoutImporting: Boolean = false,
    val takeoutProgress: String? = null,
    val takeoutMessage: String? = null,
    // Encrypted holder identity profile and square portrait stored on-device only.
    val profile: HolderProfile? = null,
    val profilePhoto: PhotoBlob? = null,
    val profileMessage: String? = null,
    // Passphrase-encrypted export / import backup.
    val backupBusy: Boolean = false,
    val backupMessage: String? = null,
    // Opt-in live ADS-B tracking on the 3D globe.
    val liveTrackingQuery: String? = null,
    val liveAircraft: LiveAircraft? = null,
    val liveTrackingBusy: Boolean = false,
    val liveTrackingError: String? = null,
    // Opt-in globe filter hiding Russia and redirecting Russian flight endpoints to the Moskva wreck.
    val hideRussia: Boolean = false,
    /** IATA codes of Russian airports, used to word the message for flights that end there (display only). */
    val ruAirports: Set<String> = emptySet(),
)

@Immutable
class PhotoBlob(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PhotoBlob && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

data class HiddenUndo(val key: String, val name: String, val flightKeys: List<String>)

/**
 * Syncing itself runs in WorkManager ([SyncWorker]) so it survives screen lock, backgrounding and process death.
 * This ViewModel only starts/cancels that work and mirrors its state; results are read back from disk.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as? MriyaApplication)?.repository ?: PassportRepository(app)
    private val importer = FlightImportService(app)
    private val takeoutImporter = TakeoutImportService(app)
    private val profileStore = (app as? MriyaApplication)?.profileStore ?: ProfileStore(app)
    private val _state = MutableStateFlow(UiState(hideRussia = repo.hideRussia))
    val state: StateFlow<UiState> = _state

    /** Last error taken from a failed worker, so it can be cleared when a new run starts. */
    private var workError: String? = null
    private var lastCommits = 0
    private var wasRunning = false
    private var takeoutJob: Job? = null
    private var liveTrackJob: Job? = null

    init {
        // Disk and parsing happen off the main thread; the UI shows nothing until the data is in.
        // A sync that finished while the app was closed is picked up here.
        viewModelScope.launch { _state.update { it.copy(continents = dev.mriya.domain.Continents.get(app)) } }
        viewModelScope.launch { _state.update { it.copy(ruAirports = dev.mriya.domain.AirportDb.get(app).codesIn("RU")) } }
        viewModelScope.launch {
            val d = repo.load()
            val t = repo.loadTakeout()
            val (p, photo) = profileStore.load()
            _state.update { it.copy(data = d, takeout = t, profile = p, profilePhoto = photo?.let(::PhotoBlob), loaded = true) }
            // Nothing cached (first run, or the stored format changed): pull history automatically.
            if (isConfigured && d.lastSyncTs == 0L) sync()
        }
        viewModelScope.launch { observeWork() }
    }

    val serverUrl get() = repo.serverUrl
    val apiKey get() = repo.apiKey
    var legacyQueryAuth: Boolean
        get() = repo.legacyQueryAuth
        set(value) {
            repo.legacyQueryAuth = value
        }
    val flightLookupProvider get() = repo.flightLookupProvider
    val flightLookupApiKey get() = repo.flightLookupApiKey
    val aeroDataBoxHost get() = repo.aeroDataBoxHost
    val isLookupConfigured
        get() = repo.flightLookupProvider != FlightLookupProvider.NONE && repo.flightLookupApiKey.isNotBlank()
    val adsbEnabled get() = repo.adsbEnabled
    val adsbFeed get() = repo.adsbFeed
    val isConfigured get() = repo.serverUrl.isNotBlank() && repo.apiKey.isNotBlank()

    fun saveFlightLookupConfig(provider: FlightLookupProvider, apiKey: String, host: String) {
        repo.flightLookupProvider = provider
        repo.flightLookupApiKey = apiKey.trim()
        repo.aeroDataBoxHost = host
    }

    fun setAdsbConfig(enabled: Boolean, feed: AdsbFeed) {
        repo.adsbEnabled = enabled
        repo.adsbFeed = feed
        if (!enabled) stopLiveTracking()
    }

    fun setHideRussia(enabled: Boolean) {
        repo.hideRussia = enabled
        _state.update { it.copy(hideRussia = enabled) }
    }

    fun urlAllowed(url: String) = repo.isUrlAllowed(url)

    private suspend fun observeWork() {
        val wm = WorkManager.getInstance(getApplication<Application>())
        combine(
            wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NOW),
            wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_PERIODIC),
        ) { now, periodic -> now to periodic }.collect { (now, periodic) ->
            val running = (now + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }
            val nowPending = now.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val syncing = running != null || nowPending

            val page = running?.progress?.getInt(SyncWorker.KEY_PAGE, 0) ?: 0
            val total = running?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0
            val progress = when {
                !syncing -> null
                running != null -> if (total > 0) "Syncing page $page of $total" else "Connecting…"
                now.any { it.runAttemptCount > 0 } -> "Waiting to retry…"
                else -> "Waiting for network…"
            }

            // New checkpoint or a finished run: show the partial/final result from disk.
            val commits = running?.progress?.getInt(SyncWorker.KEY_COMMITS, 0) ?: 0
            val finished = wasRunning && running == null
            val checkpointed = running != null && commits != lastCommits
            lastCommits = commits
            wasRunning = running != null
            val data = if (finished || checkpointed) repo.load() else null

            val failed = now.firstOrNull { it.state == WorkInfo.State.FAILED }
            _state.update { s ->
                var error = s.error
                if (syncing) {
                    if (error != null && error == workError) error = null
                    workError = null
                } else if (failed != null) {
                    val msg = failed.outputData.getString(SyncWorker.KEY_ERROR) ?: "Sync failed."
                    workError = msg; error = msg
                }
                s.copy(syncing = syncing, progress = progress, error = error, data = data ?: s.data)
            }
        }
    }

    /** Returns true if saved. A different server wipes the cached data so two servers never mix. */
    fun saveSettings(url: String, key: String): Boolean {
        val clean = url.trim().trimEnd('/')
        if (!repo.isUrlAllowed(clean)) {
            _state.update { it.copy(error = "Server URL must start with https://") }
            return false
        }
        val changed = clean != repo.serverUrl.trim().trimEnd('/')
        repo.serverUrl = clean
        repo.apiKey = key.trim()
        viewModelScope.launch {
            // Cancel first and wait for the worker to stop; only then wipe, and only then start again.
            SyncScheduler.stopAll(getApplication())
            if (changed) resetData()
            start()
        }
        return true
    }

    /** Settings action: forget everything cached and pull it all again. */
    fun clearAndResync() {
        viewModelScope.launch {
            SyncScheduler.stopAll(getApplication())
            resetData()
            start()
        }
    }

    private suspend fun resetData() {
        repo.clearData()
        val d = repo.load()
        _state.update { UiState(data = d, loaded = true, continents = it.continents, takeout = it.takeout, profile = it.profile, profilePhoto = it.profilePhoto, hideRussia = it.hideRussia, ruAirports = it.ruAirports) } // keep what is loaded once at startup
    }

    private fun start() {
        if (isConfigured) SyncScheduler.enqueueManual(getApplication())
        SyncScheduler.applyPeriodic(getApplication()) // stopAll() also cancelled the opt-in periodic schedule
    }

    fun sync() {
        if (_state.value.syncing || !isConfigured) return
        _state.update { it.copy(error = null) }
        workError = null
        SyncScheduler.enqueueManual(getApplication())
    }

    fun cancelSync() {
        viewModelScope.launch {
            SyncScheduler.stopAll(getApplication())
            SyncScheduler.applyPeriodic(getApplication())
        }
    }

    fun dismiss(f: Flight) = setDismissed(f, true)
    fun restore(f: Flight) = setDismissed(f, false)

    fun saveFlightEdit(
        flight: Flight,
        airline: String,
        flightNumber: String,
        aircraftType: String,
        callsign: String,
        registration: String,
        onDone: () -> Unit,
    ) {
        val edit = buildFlightEditForInputs(flight, airline, flightNumber, aircraftType, callsign, registration)
        viewModelScope.launch {
            val d = repo.saveFlightEdit(flight.stableKey(), edit)
            _state.update { it.copy(data = d, importMessage = "Updated flight details.") }
            onDone()
        }
    }

    private fun setDismissed(f: Flight, dismissed: Boolean) {
        viewModelScope.launch {
            val d = repo.setDismissed(f.id, dismissed)
            _state.update { it.copy(data = d, lastDismissed = if (dismissed) f else null) }
        }
    }

    fun clearUndo() = _state.update { it.copy(lastDismissed = null) }

    // ---- remove a country I never visited ---------------------------------------------------

    /** Hides the stamp and the flights touching that country. Remembered across resyncs; undoable. */
    fun hideStamp(s: dev.mriya.data.CountryStamp) {
        viewModelScope.launch {
            val key = s.iso2 ?: s.name
            val (d, keys) = repo.hideCountry(key)
            _state.update { it.copy(data = d, lastHidden = HiddenUndo(key, s.name, keys)) }
        }
    }

    fun undoHide() {
        val h = _state.value.lastHidden ?: return
        viewModelScope.launch {
            val d = repo.restoreCountry(h.key, h.flightKeys)
            _state.update { it.copy(data = d, lastHidden = null) }
        }
    }

    /** Announces trophies the user has not seen yet (once), and remembers them. [unlocked]: id to title. */
    fun checkTrophies(unlocked: Map<String, String>) {
        val s = _state.value
        if (!s.loaded || s.continents.isEmpty()) return
        val fresh = unlocked.filterKeys { it !in s.data.seenTrophies }
        if (fresh.isEmpty()) return
        val msg = if (fresh.size > 3) "You have earned ${fresh.size} trophies" else "Trophy unlocked: " + fresh.values.joinToString(", ")
        _state.update { it.copy(trophyMessage = msg, data = it.data.copy(seenTrophies = it.data.seenTrophies + fresh.keys)) }
        viewModelScope.launch { repo.markTrophiesSeen(fresh.keys) }
    }

    fun clearTrophyMessage() = _state.update { it.copy(trophyMessage = null) }

    fun clearHiddenUndo() = _state.update { it.copy(lastHidden = null) }

    fun restoreAllHidden() {
        viewModelScope.launch { _state.update { it.copy(data = repo.restoreAllHidden(), lastHidden = null) } }
    }

    // ---- flight import --------------------------------------------------------------------

    fun importText(text: String) = runImport { importer.fromText(text) }

    // ---- optional Gmail connection (read-only; see docs/GMAIL_SETUP.md) ----
    data class GmailUi(val configured: Boolean, val connected: Boolean, val message: String? = null, val progress: String? = null)
    private val gmail = GmailClient(app)
    private val _gmail = MutableStateFlow(GmailUi(gmail.isConfigured, gmail.isConnected))
    val gmailState: StateFlow<GmailUi> = _gmail
    fun gmailSignInIntent() = gmail.signInIntent()
    fun completeGmailSignIn(redirect: String) {
        viewModelScope.launch {
            _gmail.value = try { gmail.finishSignIn(redirect); GmailUi(true, true, "Gmail connected.") }
            catch (e: GmailException) { GmailUi(gmail.isConfigured, gmail.isConnected, e.message) }
            catch (e: Exception) { GmailUi(gmail.isConfigured, gmail.isConnected, "Couldn't connect Gmail.") }
        }
    }
    fun disconnectGmail() { viewModelScope.launch { gmail.disconnect(); _gmail.value = GmailUi(gmail.isConfigured, false, "Gmail disconnected. Mriya no longer has access.") } }
    fun scanGmail(query: String) = runImport {
        val msgs = gmail.search(query.ifBlank { GmailClient.DEFAULT_QUERY }) { d, t -> _gmail.update { it.copy(progress = "Reading email $d of $t") } }
        _gmail.update { it.copy(progress = null) }
        if (msgs.isEmpty()) throw FlightImportService.NoFlightsFound("No emails matched that search.")
        importer.fromEmails(msgs.map { it.subject + "\n" + it.body })
    }
    fun importUri(uri: Uri) = runImport { importer.fromUri(uri) }
    fun importUris(uris: List<Uri>) = runImport { importer.fromUris(uris) }
    fun openCalendarPicker() {
        _state.update { it.copy(importing = true, importMessage = null) }
        viewModelScope.launch {
            try {
                val calendars = importer.listCalendars()
                _state.update { it.copy(importing = false, deviceCalendars = calendars) }
            } catch (e: FlightImportService.NoFlightsFound) {
                _state.update { it.copy(importing = false, importMessage = e.message) }
            } catch (_: Exception) {
                _state.update { it.copy(importing = false, importMessage = "Couldn't read calendars on this phone.") }
            }
        }
    }
    fun closeCalendarPicker() = _state.update { it.copy(deviceCalendars = null) }
    fun scanCalendar(calendarIds: Set<Long>? = null, lookbackYears: Int? = null) {
        _state.update { it.copy(deviceCalendars = null) }
        runImport { importer.fromCalendar(calendarIds, lookbackYears) }
    }
    fun hasCalendarPermission() = importer.hasCalendarPermission()
    fun calendarDenied() = _state.update { it.copy(importMessage = "Calendar access is needed to look for flights. You can allow it in Android settings.") }

    private fun runImport(read: suspend () -> List<ParsedFlight>) {
        _state.update { it.copy(importing = true, importMessage = null, candidates = null, deviceCalendars = null) }
        viewModelScope.launch {
            try {
                val converted = importer.toConvertedFlights(completePartialLegs(read(), _state.value.data.flights))
                val cands = buildImportCandidates(converted, _state.value.data.flights)
                _state.update {
                    it.copy(importing = false, candidates = cands.ifEmpty { null },
                        importMessage = if (cands.isEmpty()) "No flights found." else null)
                }
            } catch (e: GmailException) {
                _gmail.update { it.copy(progress = null) }
                _state.update { it.copy(importing = false, importMessage = e.message) }
            } catch (e: FlightImportService.NoFlightsFound) {
                _state.update { it.copy(importing = false, importMessage = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, importMessage = "Couldn't read that.") }
            }
        }
    }

    fun toggleCandidate(id: String) = _state.update { s ->
        s.copy(candidates = s.candidates?.map { if (it.flight.id == id) it.copy(selected = !it.selected) else it })
    }

    fun confirmImport() {
        val chosen = _state.value.candidates?.filter { it.selected } ?: return
        val picked = chosen.map { it.flight }
        viewModelScope.launch {
            chosen.mapNotNull { it.restoreId }.forEach { repo.setDismissed(it, false) }
            val d = repo.addImports(picked)
            val msg = when {
                picked.isEmpty() -> null
                repo.hideRussia && picked.any { it.fromAirport in _state.value.ruAirports || it.toAirport in _state.value.ruAirports } -> MOSKVA_MESSAGE
                else -> "Added ${picked.size} ${if (picked.size == 1) "flight" else "flights"}."
            }
            _state.update { it.copy(data = d, candidates = null, importMessage = msg) }
        }
    }

    fun cancelImport() = _state.update { it.copy(candidates = null, importMessage = null) }
    fun clearImportMessage() = _state.update { it.copy(importMessage = null) }

    fun saveManualFlight(
        from: String,
        to: String,
        dateIso: String,
        airline: String,
        flightNumber: String,
        aircraftType: String = "",
        callsign: String = "",
        registration: String = "",
        status: String = "",
        departTsOverride: Long? = null,
        arriveTsOverride: Long? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val db = AirportDb.get(getApplication())
            when (
                val res = ManualFlightInput.build(
                    fromCode = from,
                    toCode = to,
                    dateIso = dateIso,
                    airline = airline,
                    flightNumber = flightNumber,
                    aircraftType = aircraftType,
                    callsign = callsign,
                    registration = registration,
                    status = status,
                    departTsOverride = departTsOverride,
                    arriveTsOverride = arriveTsOverride,
                ) { db.byIata(it) }
            ) {
                is ManualFlightResult.Error -> onError(res.message)
                is ManualFlightResult.Ok -> {
                    val d = repo.addImports(listOf(res.flight))
                    val msg = if (
                        repo.hideRussia &&
                        (res.flight.fromAirport in _state.value.ruAirports || res.flight.toAirport in _state.value.ruAirports)
                    ) {
                        MOSKVA_MESSAGE
                    } else {
                        "Added ${res.flight.fromAirport} → ${res.flight.toAirport}."
                    }
                    _state.update {
                        it.copy(
                            data = d,
                            importMessage = msg,
                        )
                    }
                    onSuccess()
                }
            }
        }
    }

    fun lookupFlight(
        designator: String,
        dateIso: String,
        onResult: (FlightLookupResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val api = FlightLookupApi(
                    provider = repo.flightLookupProvider,
                    apiKey = repo.flightLookupApiKey,
                    aeroDataBoxHost = repo.aeroDataBoxHost,
                )
                onResult(api.lookup(designator, dateIso))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Flight lookup failed.")
            }
        }
    }

    // ---- Google Takeout import ---------------------------------------------------------------

    fun importTakeout(uri: Uri) {
        val app = getApplication<Application>()
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        takeoutJob?.cancel()
        _state.update { it.copy(takeoutImporting = true, takeoutProgress = "Reading Takeout file…", takeoutMessage = null) }
        takeoutJob = viewModelScope.launch {
            try {
                val result = takeoutImporter.importUri(uri) { points, files ->
                    val label = if (files > 0) "Reading… ${"%,d".format(points)} points ($files ${if (files == 1) "file" else "files"})"
                    else "Reading… ${"%,d".format(points)} points"
                    _state.update { it.copy(takeoutProgress = label) }
                }
                if (result.points == 0L && result.flights.isEmpty()) {
                    _state.update {
                        it.copy(
                            takeoutImporting = false,
                            takeoutProgress = null,
                            takeoutMessage = "No Google Location History data found in that file.",
                        )
                    }
                    return@launch
                }
                val d = repo.saveTakeoutData(result)
                _state.update {
                    it.copy(
                        data = d,
                        takeout = result,
                        takeoutImporting = false,
                        takeoutProgress = null,
                        takeoutMessage = "Imported ${result.stamps.size} ${if (result.stamps.size == 1) "country" else "countries"} and ${result.flights.size} ${if (result.flights.size == 1) "flight" else "flights"}.",
                    )
                }
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(takeoutImporting = false, takeoutProgress = null, takeoutMessage = "Takeout import cancelled.")
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(takeoutImporting = false, takeoutProgress = null, takeoutMessage = "Could not read that Takeout file.")
                }
            }
        }
    }

    fun cancelTakeoutImport() {
        takeoutJob?.cancel()
        takeoutJob = null
    }

    fun removeTakeout() {
        viewModelScope.launch {
            val d = repo.clearTakeout()
            _state.update { it.copy(data = d, takeout = null, takeoutMessage = "Removed Takeout data.") }
        }
    }

    fun clearTakeoutMessage() = _state.update { it.copy(takeoutMessage = null) }

    // ---- passport holder profile (encrypted on device) ---------------------------------------

    fun saveProfile(profile: HolderProfile) {
        viewModelScope.launch {
            runCatching { profileStore.saveProfile(profile) }
                .onSuccess { saved ->
                    _state.update { it.copy(profile = saved, profileMessage = "Passport profile saved encrypted on this device.") }
                }
                .onFailure {
                    _state.update { it.copy(profileMessage = "Could not save passport profile.") }
                }
        }
    }

    fun pickProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            val bytes = runCatching { profileStore.savePhotoFromUri(uri) }.getOrNull()
            if (bytes == null) {
                _state.update { it.copy(profileMessage = "Could not load or encrypt that image.") }
            } else {
                _state.update { it.copy(profilePhoto = PhotoBlob(bytes), profileMessage = "Portrait photo saved encrypted.") }
            }
        }
    }

    fun deleteProfile() {
        viewModelScope.launch {
            profileStore.deleteAll()
            _state.update { it.copy(profile = null, profilePhoto = null, profileMessage = "Deleted passport profile and photo.") }
        }
    }

    fun clearProfileMessage() = _state.update { it.copy(profileMessage = null) }

    // ---- passphrase-encrypted backup (never includes Dawarich credentials) ------------------

    fun exportBackup(uri: Uri, passphrase: String) {
        _state.update { it.copy(backupBusy = true, backupMessage = null) }
        val chars = passphrase.toCharArray()
        viewModelScope.launch {
            try {
                val (userData, imports, takeout) = repo.snapshotForBackup()
                val (profile, photo) = profileStore.load()
                val bundle = BackupBundle(
                    createdAt = Instant.now().epochSecond,
                    userData = userData,
                    imports = imports,
                    takeout = takeout,
                    profile = profile,
                    photoBase64 = photo?.let { Base64.getEncoder().encodeToString(it) },
                )
                val encrypted = withContext(Dispatchers.Default) { BackupCrypto.encrypt(bundle, chars) }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
                        ?: error("Could not write to the selected file.")
                }
                _state.update { it.copy(backupBusy = false, backupMessage = "Encrypted backup exported.") }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(backupBusy = false, backupMessage = e.message) }
            } catch (_: Exception) {
                _state.update { it.copy(backupBusy = false, backupMessage = "Could not export backup.") }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun importBackup(uri: Uri, passphrase: String) {
        _state.update { it.copy(backupBusy = true, backupMessage = null) }
        val chars = passphrase.toCharArray()
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read the selected file.")
                }
                val bundle = withContext(Dispatchers.Default) { BackupCrypto.decrypt(raw, chars) }
                val data = repo.restoreFromBackup(bundle.userData, bundle.imports, bundle.takeout)
                val photoBytes = bundle.photoBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                if (bundle.profile == null && photoBytes == null) {
                    profileStore.deleteAll()
                } else {
                    bundle.profile?.let { profileStore.saveProfile(it) }
                    photoBytes?.let { profileStore.savePhotoBytes(it) }
                }
                val (loadedProfile, loadedPhoto) = profileStore.load()
                _state.update {
                    it.copy(
                        data = data,
                        takeout = bundle.takeout,
                        profile = loadedProfile,
                        profilePhoto = loadedPhoto?.let(::PhotoBlob),
                        backupBusy = false,
                        backupMessage = "Backup restored.",
                    )
                }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(backupBusy = false, backupMessage = e.message) }
            } catch (_: Exception) {
                _state.update { it.copy(backupBusy = false, backupMessage = "Could not restore backup.") }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    override fun onCleared() {
        liveTrackJob?.cancel()
        super.onCleared()
    }

    fun startLiveTracking(rawInput: String, enableAdsb: Boolean = false) {
        if (enableAdsb) repo.adsbEnabled = true
        if (!repo.adsbEnabled) {
            _state.update { it.copy(liveTrackingError = "Turn on live ADS-B tracking first.") }
            return
        }
        val query = normalizeTrackQuery(rawInput)
        if (query == null) {
            _state.update { it.copy(liveTrackingError = "Enter a callsign (UAL123), flight # (UA 123), or tail # (N27958).") }
            return
        }
        val label = when (query) {
            is dev.mriya.data.AdsbQuery.Callsign -> query.value
            is dev.mriya.data.AdsbQuery.Registration -> query.value
        }
        liveTrackJob?.cancel()
        _state.update {
            it.copy(
                liveTrackingQuery = label,
                liveAircraft = null,
                liveTrackingBusy = true,
                liveTrackingError = null,
            )
        }
        liveTrackJob = viewModelScope.launch {
            val api = AdsbApi(repo.adsbFeed)
            while (true) {
                try {
                    val found = api.lookup(query)
                    _state.update {
                        it.copy(
                            liveAircraft = found,
                            liveTrackingBusy = false,
                            liveTrackingError = if (found == null) "$label is not transmitting on ${repo.adsbFeed.label} right now." else null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            liveTrackingBusy = false,
                            liveTrackingError = e.message ?: "Could not reach ${repo.adsbFeed.label}.",
                        )
                    }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    fun stopLiveTracking() {
        liveTrackJob?.cancel()
        liveTrackJob = null
        _state.update {
            it.copy(
                liveTrackingQuery = null,
                liveAircraft = null,
                liveTrackingBusy = false,
                liveTrackingError = null,
            )
        }
    }
}
