package dev.mriya.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mriya.R
import dev.mriya.data.Flight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())
internal fun fmt(ts: Long) = dateFmt.format(Instant.ofEpochSecond(ts))
internal fun year(ts: Long) = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).year
private fun flag(iso: String?): String =
    if (iso == null || iso.length != 2) "🌐"
    else iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

private enum class Tab(@StringRes val labelRes: Int) {
    Passport(R.string.tab_passport),
    Map(R.string.tab_map),
    Flights(R.string.tab_flights),
    Trophies(R.string.tab_trophies),
    Stats(R.string.tab_stats),
    Settings(R.string.tab_settings),
}

@Composable
fun MriyaApp(vm: MainViewModel) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (vm.isConfigured) Tab.Passport else Tab.Settings) }
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showGmail by remember { mutableStateOf(false) }
    val gmailUi by vm.gmailState.collectAsStateWithLifecycle()
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var showManual by remember { mutableStateOf(false) }
    var editingFlight by remember { mutableStateOf<Flight?>(null) }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val askCalendar = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.openCalendarPicker() else vm.calendarDenied()
    }
    LaunchedEffect(ui.importMessage) {
        val m = ui.importMessage ?: return@LaunchedEffect
        if (m.length > 90) return@LaunchedEffect // long explanations are shown in a dialog below
        snackbar.showSnackbar(m); vm.clearImportMessage()
    }
    NotificationPermissionRequest(vm) // asks once, when the first sync starts (Android 13+)

    // Trophies are computed from the passport; announce the ones the user has not seen yet, once.
    val unlockedTrophies = remember(ui.data, ui.continents) {
        if (ui.continents.isEmpty()) emptyMap()
        else dev.mriya.domain.Trophies.achievements(dev.mriya.domain.Trophies.stats(ui.data) { ui.continents[it] })
            .filter { it.unlocked }.associate { it.id to it.title }
    }
    LaunchedEffect(unlockedTrophies, ui.loaded) { vm.checkTrophies(unlockedTrophies) }
    LaunchedEffect(ui.trophyMessage) {
        val m = ui.trophyMessage ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(m, actionLabel = "View", duration = SnackbarDuration.Long)
        vm.clearTrophyMessage()
        if (r == SnackbarResult.ActionPerformed) tab = Tab.Trophies
    }

    LaunchedEffect(ui.lastHidden) {
        val h = ui.lastHidden ?: return@LaunchedEffect
        val n = h.flightKeys.size
        val r = snackbar.showSnackbar("Removed ${h.name}" + if (n > 0) " and $n ${if (n == 1) "flight" else "flights"}" else "", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undoHide() else vm.clearHiddenUndo()
    }

    // Offer Undo after a flight is hidden.
    LaunchedEffect(ui.lastDismissed) {
        val f = ui.lastDismissed ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Flight hidden", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.restore(f) else vm.clearUndo()
    }

    GradientBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Header(ui, vm.isConfigured, onSync = vm::sync, onCancel = vm::cancelSync, onSettings = { tab = Tab.Settings }) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(Tab.Passport, Tab.Map, Tab.Flights, Tab.Trophies, Tab.Stats).forEach { t ->
                        val tabLabel = stringResource(t.labelRes)
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            label = { Text(tabLabel, fontWeight = if (tab == t) FontWeight.ExtraBold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Passport -> Icons.Default.Public
                                        Tab.Map -> Icons.Default.Map
                                        Tab.Flights -> Icons.Default.Flight
                                        Tab.Trophies -> Icons.Default.EmojiEvents
                                        else -> Icons.Default.BarChart
                                    }, tabLabel
                                )
                            },
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (ui.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                when (tab) {
                    Tab.Passport -> PassportScreen(ui, vm::hideStamp)
                    Tab.Map -> MapScreen(
                        ui = ui,
                        adsbEnabled = vm.adsbEnabled,
                        onRemove = vm::hideStamp,
                        onDismissFlight = vm::dismiss,
                        onStartLiveTrack = vm::startLiveTracking,
                        onStopLiveTrack = vm::stopLiveTracking,
                    )
                    Tab.Flights -> FlightsScreen(
                        all = ui.data.flights.sortedByDescending { it.departTs },
                        onDismiss = vm::dismiss,
                        onRestore = vm::restore,
                        onEdit = { editingFlight = it },
                        onAdd = { showAdd = true },
                    )
                    Tab.Trophies -> TrophiesScreen(ui)
                    Tab.Stats -> StatsScreen(ui)
                    Tab.Settings -> SettingsScreen(vm) { tab = Tab.Passport }
                }
            }
        }
    }

    ui.importMessage?.takeIf { it.length > 90 }?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearImportMessage,
            title = { Text("Import") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::clearImportMessage) { Text("OK") } },
        )
    }
    if (showAdd) AddFlightsSheet(
        onDismiss = { showAdd = false },
        onFile = { showAdd = false; pickFile.launch(arrayOf("*/*")) },
        onCalendar = {
            showAdd = false
            if (vm.hasCalendarPermission()) vm.openCalendarPicker() else askCalendar.launch(android.Manifest.permission.READ_CALENDAR)
        },
        onPaste = { showAdd = false; showPaste = true },
        onGmail = { showAdd = false; showGmail = true },
        onManual = { showAdd = false; showManual = true },
    )
    if (showGmail) GmailSheet(
        state = gmailUi, busy = ui.importing,
        onDismiss = { showGmail = false },
        onConnect = { vm.gmailSignInIntent()?.let { runCatching { appCtx.startActivity(it) } } },
        onSearch = { q -> showGmail = false; vm.scanGmail(q) },
        onDisconnect = vm::disconnectGmail,
    )
    if (showPaste) PasteDialog(onDismiss = { showPaste = false }, onSubmit = { showPaste = false; vm.importText(it) })
    if (showManual) ManualFlightDialog(
        lookupConfigured = vm.isLookupConfigured,
        onLookup = vm::lookupFlight,
        onDismiss = { showManual = false },
        onSubmit = vm::saveManualFlight,
    )
    editingFlight?.let { f ->
        val usedAirlines = remember(ui.data.flights) {
            ui.data.flights.filter { !it.dismissed }.mapNotNull { it.airline }.distinct()
        }
        EditFlightDialog(
            flight = f,
            recentAirlines = usedAirlines,
            onDismiss = { editingFlight = null },
            onSave = { airline, number, aircraftType, callsign, registration, onDone ->
                vm.saveFlightEdit(f, airline, number, aircraftType, callsign, registration, onDone)
            },
            lookupAction = if (vm.isLookupConfigured) {
                { airline, number, dateIso, onApplied ->
                    var busy by remember { mutableStateOf(false) }
                    var lookupErr by remember { mutableStateOf<String?>(null) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                busy = true
                                lookupErr = null
                                vm.lookupFlight(
                                    "$airline $number".trim(),
                                    dateIso,
                                    onResult = { res ->
                                        busy = false
                                        onApplied(res.aircraftType.orEmpty(), res.callsign.orEmpty(), res.registration.orEmpty())
                                    },
                                    onError = { msg ->
                                        busy = false
                                        lookupErr = msg
                                    },
                                )
                            },
                            enabled = !busy && airline.isNotBlank() && number.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (busy) "Looking up…" else "Look up aircraft & callsign")
                        }
                        lookupErr?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else null,
        )
    }
    ui.deviceCalendars?.let { cals ->
        CalendarPickerDialog(
            calendars = cals,
            onScan = { ids, years -> vm.scanCalendar(ids, years) },
            onDismiss = vm::closeCalendarPicker,
        )
    }
    ui.candidates?.let { ReviewImportSheet(it, vm::toggleCandidate, vm::confirmImport, vm::cancelImport) }
}

@Composable
private fun Header(ui: UiState, configured: Boolean, onSync: () -> Unit, onCancel: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(46.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_mriya_mark),
                contentDescription = "Mriya logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mriya", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Spacer(Modifier.width(6.dp))
                Pill("PASSPORT", small = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (configured) MriyaColors.Ok else MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    ui.progress ?: if (!configured) "Not connected" else if (ui.data.lastSyncTs > 0) "Dawarich • last point ${fmt(ui.data.lastSyncTs)}" else "Dawarich • not synced",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        // While syncing the spinner doubles as a cancel button.
        RoundIcon(if (ui.syncing) onCancel else onSync, enabled = configured) {
            if (ui.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Sync, "Sync", Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(onSettings) { Icon(Icons.Default.Settings, "Settings", Modifier.size(20.dp)) }
    }
}

@Composable
internal fun RoundIcon(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(42.dp).clickable(enabled = enabled, onClick = onClick),
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

@Composable
internal fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.5.sp)
        if (trailing != null) { Spacer(Modifier.width(8.dp)); Chip(trailing) }
    }
}

@Composable
private fun PassportScreen(ui: UiState, onRemove: (dev.mriya.data.CountryStamp) -> Unit) {
    var confirmStamp by remember { mutableStateOf<dev.mriya.data.CountryStamp?>(null) }
    if (!ui.loaded) return
    val flights = ui.data.flights.filter { !it.dismissed }
    val allStamps = ui.data.stamps.values.toList()
    if (allStamps.isEmpty() && flights.isEmpty()) {
        Empty(if (ui.syncing) "Syncing your location history… Stamps and flights appear as soon as the first batch is saved." else "No stamps yet. Connect Dawarich or import Google Takeout in Settings.")
        return
    }
    // Years come from flights AND from days spent in countries, so overland-only years still show up.
    val years = (flights.map { year(it.departTs) } + allStamps.flatMap { s -> s.days.mapNotNull { it.take(4).toIntOrNull() } })
        .distinct().sortedDescending()
    var openStamp by remember { mutableStateOf<dev.mriya.data.CountryStamp?>(null) }
    var choice by rememberSaveable { mutableIntStateOf(-1) } // -1 = latest year (default), 0 = all time
    val selected: Int? = when { choice == 0 -> null; choice in years -> choice; else -> years.firstOrNull() }
    val stamps = if (selected == null) allStamps else allStamps.filter { s -> s.days.any { it.startsWith("$selected-") } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(years) { y -> Chip("$y", Modifier.clickable { choice = y }, selected = selected == y) }
                item { Chip("All time", Modifier.clickable { choice = 0 }, selected = selected == null) }
            }
        }
        item(span = { GridItemSpan(2) }) { PassportDeck(selected, allStamps, flights, ui.profile, ui.profilePhoto?.bytes) }
        item(span = { GridItemSpan(2) }) { SectionLabel("Stamps", "${stamps.size} countries") }
        val earliestTs = allStamps.minOfOrNull { it.firstTs }
        items(stamps.sortedBy { it.firstTs }, key = { it.iso2 ?: it.name }) { s ->
            val style = dev.mriya.domain.StampDesign.styleForCountry(
                isoOrName = s.iso2 ?: s.name,
                continent = s.iso2?.let { ui.continents[it] },
                isEarliest = s.firstTs == earliestTs,
                dayCount = s.dayCount,
            )
            val cityHint = s.cities.sorted().firstOrNull()
            val detail = listOfNotNull(
                cityHint,
                "${s.dayCount} ${if (s.dayCount == 1) "day" else "days"}",
                if (s.cities.size > 1) "${s.cities.size} cities" else null,
            ).joinToString(" · ")
            PassportStamp(
                flag(s.iso2),
                s.name,
                fmt(s.firstTs),
                detail,
                style = style,
                modifier = Modifier.clickable { openStamp = s },
            )
        }
    }
    openStamp?.let { s -> StampSheet(s, onRemove = { openStamp = null; confirmStamp = s }) { openStamp = null } }
    confirmStamp?.let { s -> RemoveCountryDialog(s.name, onConfirm = { confirmStamp = null; onRemove(s) }, onDismiss = { confirmStamp = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StampSheet(s: dev.mriya.data.CountryStamp, onRemove: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flag(s.iso2), fontSize = 40.sp)
                Spacer(Modifier.width(12.dp))
                Text(s.name, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("First visit ${fmt(s.firstTs)}  ·  last ${fmt(s.lastTs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${s.dayCount} ${if (s.dayCount == 1) "day" else "days"} recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.cities.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("CITIES", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.cities.sorted().take(60).forEach { Chip(it) }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("I was never here: remove") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(
    ui: UiState,
    adsbEnabled: Boolean,
    onRemove: (dev.mriya.data.CountryStamp) -> Unit,
    onDismissFlight: (Flight) -> Unit,
    onStartLiveTrack: (query: String, enableAdsb: Boolean) -> Unit,
    onStopLiveTrack: () -> Unit,
) {
    var confirm by remember { mutableStateOf<dev.mriya.data.CountryStamp?>(null) }
    var showCountriesSheet by remember { mutableStateOf(false) }
    var showLiveDialog by remember { mutableStateOf(false) }
    val flights = ui.data.flights.filter { !it.dismissed }
    var sel by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    var selectedFlightId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.hideRussia) {
        if ((ui.hideRussia && sel.first == "RU") || (!ui.hideRussia && sel.first == "MOSKVA")) {
            sel = null to null
        }
    }
    val selectedFlight = flights.firstOrNull { it.id == selectedFlightId }
    val stamp = ui.data.stamps.values.firstOrNull { it.iso2 != null && it.iso2 == sel.first }
    Box(Modifier.fillMaxSize()) {
        Globe(
            visitedIso = ui.data.stamps.values.mapNotNull { it.iso2 }.toSet(),
            flights = flights,
            selectedIso = sel.first,
            onCountryTap = { iso, name -> sel = iso to name; if (name != null) selectedFlightId = null },
            modifier = Modifier.fillMaxSize(),
            selectedFlightId = selectedFlight?.id,
            onFlightTap = { f -> selectedFlightId = f?.id; if (f != null) sel = null to null },
            liveAircraft = ui.liveAircraft,
            hideRussia = ui.hideRussia,
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                "${ui.data.stamps.size} countries",
                modifier = Modifier
                    .semantics { contentDescription = "Open visited countries list" }
                    .clickable(enabled = ui.data.stamps.isNotEmpty()) { showCountriesSheet = true },
            )
            Chip("${flights.size} flights")
            Chip(
                text = if (ui.liveTrackingQuery != null) "Live: ${ui.liveTrackingQuery}" else "Live",
                modifier = Modifier
                    .semantics { contentDescription = "Track a live flight via ADS-B" }
                    .clickable { showLiveDialog = true },
                selected = ui.liveTrackingQuery != null,
            )
        }
        if (ui.liveTrackingQuery != null) {
            val ac = ui.liveAircraft
            MriyaCard(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append("LIVE  •  ")
                                append(ac?.callsign ?: ui.liveTrackingQuery)
                                ac?.aircraftType?.let { append("  •  $it") }
                            },
                            fontFamily = Mono,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        val details = when {
                            ac != null -> listOfNotNull(
                                ac.registration,
                                ac.altitudeFt?.let { if (it == 0) "On ground" else "${"%,d".format(it)} ft" },
                                ac.groundSpeedKt?.let { "$it kt" },
                                ac.headingDeg?.let { "${it.roundToInt()}°" },
                            ).joinToString("  •  ")
                            ui.liveTrackingBusy -> "Checking open ADS-B feed…"
                            else -> ui.liveTrackingError ?: "Waiting for signal…"
                        }
                        Text(details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RoundIcon(onStopLiveTrack) {
                        Icon(Icons.Default.Close, "Stop live tracking", Modifier.size(18.dp))
                    }
                }
            }
        } else if (selectedFlight != null) {
            val f = selectedFlight
            MriyaCard(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${f.fromAirport ?: "%.1f°, %.1f°".format(f.from.lat, f.from.lon)}  →  ${f.toAirport ?: "%.1f°, %.1f°".format(f.to.lat, f.to.lon)}",
                            fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 20.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        val flightNo = f.flightNumber?.let { "  •  ${f.airline.orEmpty()} $it".replace("•   ", "•  ") } ?: ""
                        val isMoskvaRedirect = ui.hideRussia && (f.fromAirport in ui.ruAirports || f.toAirport in ui.ruAirports)
                        val moskvaNote = if (isMoskvaRedirect) "  •  Moskva wreck (45.18°N, 30.93°E)" else ""
                        Text(
                            "${fmt(f.departTs)}$flightNo  •  ${"%,d".format(f.distanceKm.roundToInt())} km$moskvaNote",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlightSourceBadge(f)
                        if (isMoskvaRedirect) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                dev.mriya.domain.imports.MOSKVA_MESSAGE,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    RoundIcon({ onDismissFlight(f); selectedFlightId = null }) {
                        Icon(Icons.Default.Delete, "Remove flight", Modifier.size(18.dp))
                    }
                }
            }
        } else if (sel.second != null) {
            MriyaCard(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isMoskvaSpot = sel.first == "MOSKVA"
                    Text(if (isMoskvaSpot) "🇺🇦" else flag(sel.first), fontSize = 34.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sel.second ?: "", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text(
                            when {
                                isMoskvaSpot -> "Moskva wreck site · 45.18°N, 30.93°E"
                                stamp != null -> "First visit ${fmt(stamp.firstTs)} · ${stamp.dayCount} ${if (stamp.dayCount == 1) "day" else "days"}"
                                else -> "Not visited yet"
                            },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stamp != null) RoundIcon({ confirm = stamp }) { Icon(Icons.Default.Delete, "Remove from my passport", Modifier.size(18.dp)) }
                }
            }
        }
    }
    confirm?.let { s -> RemoveCountryDialog(s.name, onConfirm = { confirm = null; onRemove(s) }, onDismiss = { confirm = null }) }
    if (showLiveDialog) {
        val quickQueries = remember(flights) {
            flights.mapNotNull { f ->
                f.callsign ?: dev.mriya.data.iataToIcaoCallsign(f.airline, f.flightNumber)
            }.distinct().take(6)
        }
        LiveTrackDialog(
            adsbEnabled = adsbEnabled,
            initialQuery = ui.liveTrackingQuery.orEmpty(),
            quickQueries = quickQueries,
            onDismiss = { showLiveDialog = false },
            onTrack = { q, enableAdsb ->
                showLiveDialog = false
                onStartLiveTrack(q, enableAdsb)
            },
        )
    }
    if (showCountriesSheet) {
        val sortedStamps = ui.data.stamps.values.sortedBy { it.name }
        ModalBottomSheet(onDismissRequest = { showCountriesSheet = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Visited countries (${sortedStamps.size})", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sortedStamps, key = { it.iso2 ?: it.name }) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sel = s.iso2 to s.name
                                    selectedFlightId = null
                                    showCountriesSheet = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(flag(s.iso2), fontSize = 26.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "First visit ${fmt(s.firstTs)} · ${s.dayCount} ${if (s.dayCount == 1) "day" else "days"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTrackDialog(
    adsbEnabled: Boolean,
    initialQuery: String,
    quickQueries: List<String>,
    onDismiss: () -> Unit,
    onTrack: (query: String, enableAdsb: Boolean) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track live flight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (adsbEnabled) "Enter a callsign (UAL123), flight # (UA 123), or tail # (N27958) to track via open ADS-B."
                    else "Live ADS-B tracking is opt-in and off by default. Only the callsign or tail # you enter is queried on community ADS-B feeds.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase(Locale.ROOT) },
                    label = { Text("Callsign, flight #, or tail #") },
                    placeholder = { Text("UA 123 or UAL123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (quickQueries.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(quickQueries) { q ->
                            Chip(q, Modifier.clickable { query = q }, selected = query == q)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onTrack(query, !adsbEnabled) },
                enabled = query.isNotBlank(),
            ) {
                Text(if (adsbEnabled) "Track" else "Enable & track")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveCountryDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $name?") },
        text = { Text("Use this if you were never there. The stamp and the flights to or from $name are hidden from your passport, map and stats. It stays removed after a resync. You can undo it right away, or restore it later in Settings.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun StatsScreen(ui: UiState) {
    val allFlights = ui.data.flights.filter { !it.dismissed }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    val yearsAvailable = allFlights.map { year(it.departTs) }.distinct().sortedDescending()
    val activeYear = selectedYear?.takeIf { it in yearsAvailable }
    val flights = if (activeYear == null) allFlights else allFlights.filter { year(it.departTs) == activeYear }
    val stampsInScope = if (activeYear == null) ui.data.stamps.values.toList() else ui.data.stamps.values.filter { s -> s.days.any { it.startsWith("$activeYear-") } }
    val byYear = flights.groupBy { year(it.departTs) }.toSortedMap(reverseOrder())
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (yearsAvailable.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("All years", Modifier.clickable { selectedYear = null }, selected = activeYear == null) }
                items(yearsAvailable) { y -> Chip("$y", Modifier.clickable { selectedYear = if (activeYear == y) null else y }, selected = activeYear == y) }
            }
        }
        item {
            MriyaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (activeYear == null) "ALL TIME" else "$activeYear", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${"%,d".format(flights.sumOf { it.distanceKm }.roundToInt())} km", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 38.sp)
                    Text("flown across ${flights.size} flights", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("${stampsInScope.size} ${if (stampsInScope.size == 1) "country" else "countries"}")
                        // Cities come from our own stamps: Dawarich's /stats endpoint reports 0 for cities and countries on some servers.
                        val cities = stampsInScope.flatMap { it.cities }.toSet().size
                        if (cities > 0) Chip("$cities ${if (cities == 1) "city" else "cities"}")
                        ui.data.stats?.takeIf { activeYear == null && it.totalDistanceKm > 0 }?.let { Chip("${"%,d".format(it.totalDistanceKm.roundToInt())} km tracked") }
                    }
                }
            }
        }
        item { TopAirlinesCard(flights) }
        byYear.forEach { (y, fl) ->
            item {
                val countries = ui.data.stamps.values.count { s -> s.days.any { it.startsWith("$y-") } }
                MriyaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$y", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${fl.size} flights", fontWeight = FontWeight.Bold)
                            Text("${"%,d".format(fl.sumOf { it.distanceKm }.roundToInt())} km · $countries countries", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(msg: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
