package dev.mriya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.data.DeviceCalendar
import dev.mriya.data.FlightLookupResult
import dev.mriya.data.defaultSelectedCalendarIds
import dev.mriya.domain.imports.ImportCandidate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())

/** Where flights can come from. Nothing here signs in to anything or sends data anywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFlightsSheet(
    onDismiss: () -> Unit,
    onFile: () -> Unit,
    onCalendar: () -> Unit,
    onPaste: () -> Unit,
    onGmail: () -> Unit,
    onManual: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Add flights", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(
                "Everything is read on this phone. You review the flights before they are added.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Option(Icons.Default.Edit, "Enter manually", "Type departure, arrival airport codes and date", onManual)
            Option(Icons.Default.FileOpen, "Boarding pass or file", "Boarding pass, flight screenshot (Google Wallet / airline), PDF, .ics or .eml", onFile)
            Option(Icons.Default.CalendarMonth, "Scan my calendar", "Choose accounts & calendars synced on this phone", onCalendar)
            Option(Icons.Default.Email, "Search my Gmail", "Connect once, then search your mail for bookings. Read-only", onGmail)
            Option(Icons.Default.ContentPaste, "From an email", "Copy a booking email's text in your mail app, then paste it here. Every flight in it is found", onPaste)
            Spacer(Modifier.height(4.dp))
            Text(
                "In your mail app: open the booking email, long-press its text, choose Select all, then Copy (or Share and pick Mriya). Saved .eml files work too, under \"Boarding pass or file\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
fun CalendarPickerDialog(
    calendars: List<DeviceCalendar>,
    onScan: (calendarIds: Set<Long>, lookbackYears: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember(calendars) { mutableStateOf(defaultSelectedCalendarIds(calendars)) }
    var lookbackYears by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose calendars to scan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mriya reads calendars synced to this phone offline. Pick which accounts and calendars to search.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (calendars.isEmpty()) {
                    Text(
                        "No synced calendars were found on Android.\n\nTurn on Calendar sync for your Google, work, or other email account in Android Settings -> Passwords & accounts, or share an .ics file instead.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "All time", 5 to "5 years", 2 to "2 years").forEach { (years, label) ->
                            FilterChip(
                                selected = lookbackYears == years,
                                onClick = { lookbackYears = years },
                                label = { Text(label, fontSize = 12.sp) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selectedIds.size} of ${calendars.size} selected",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { selectedIds = calendars.mapTo(LinkedHashSet()) { it.id } }) {
                                Text("Select all", fontSize = 12.sp)
                            }
                            TextButton(onClick = { selectedIds = emptySet() }) {
                                Text("Clear", fontSize = 12.sp)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(calendars, key = { it.id }) { cal ->
                            val checked = cal.id in selectedIds
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (checked) selectedIds - cal.id else selectedIds + cal.id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { nowChecked ->
                                        selectedIds = if (nowChecked) selectedIds + cal.id else selectedIds - cal.id
                                    },
                                )
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(cal.color)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cal.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val acct = buildString {
                                        append(cal.accountName.ifBlank { "Local device calendar" })
                                        if (!cal.syncEvents) append(" • sync off")
                                    }
                                    Text(
                                        acct,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (calendars.isNotEmpty()) {
                TextButton(
                    onClick = { onScan(selectedIds, lookbackYears) },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("Scan ${selectedIds.size} ${if (selectedIds.size == 1) "calendar" else "calendars"}")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (calendars.isEmpty()) "OK" else "Cancel") }
        },
    )
}

@Composable
fun PasteDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flights from an email") },
        text = {
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = { Text("Paste the booking email, or a line like: UA 123 ORD to SFO, 12 Mar 2026") },
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Find flights") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ManualFlightDialog(
    lookupConfigured: Boolean = false,
    onLookup: ((
        designator: String,
        dateIso: String,
        onResult: (FlightLookupResult) -> Unit,
        onError: (String) -> Unit,
    ) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSubmit: (
        from: String,
        to: String,
        dateIso: String,
        airline: String,
        flightNumber: String,
        aircraftType: String,
        callsign: String,
        registration: String,
        status: String,
        departTsOverride: Long?,
        arriveTsOverride: Long?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var airline by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var aircraftType by remember { mutableStateOf("") }
    var callsign by remember { mutableStateOf("") }
    var registration by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var departTsOverride by remember { mutableStateOf<Long?>(null) }
    var arriveTsOverride by remember { mutableStateOf<Long?>(null) }
    var gateSummary by remember { mutableStateOf<String?>(null) }
    var lookingUp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add flight manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = airline,
                        onValueChange = { airline = it.uppercase(Locale.ROOT).take(3); error = null },
                        label = { Text("Airline") },
                        placeholder = { Text("UA") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it.uppercase(Locale.ROOT).take(8); error = null },
                        label = { Text("Flight #") },
                        placeholder = { Text("123") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it; error = null },
                    label = { Text("Date (YYYY-MM-DD)") },
                    placeholder = { Text("2026-03-12") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lookupConfigured && onLookup != null) {
                    OutlinedButton(
                        onClick = {
                            lookingUp = true
                            error = null
                            onLookup(
                                "${airline.trim()} ${number.trim()}".trim(),
                                date,
                                { res ->
                                    lookingUp = false
                                    from = res.fromIata
                                    to = res.toIata
                                    airline = res.airlineIata
                                    number = res.flightNumber
                                    aircraftType = res.aircraftType.orEmpty()
                                    callsign = res.callsign.orEmpty()
                                    registration = res.registration.orEmpty()
                                    status = res.status.orEmpty()
                                    departTsOverride = res.departTs
                                    arriveTsOverride = res.arriveTs
                                    val gateBits = listOfNotNull(
                                        res.status,
                                        res.aircraftType,
                                        res.departureGate?.let { "Dep gate $it" },
                                        res.arrivalGate?.let { "Arr gate $it" },
                                    )
                                    gateSummary = gateBits.joinToString(" • ").ifEmpty { null }
                                },
                                { msg ->
                                    lookingUp = false
                                    error = msg
                                },
                            )
                        },
                        enabled = !lookingUp && airline.isNotBlank() && number.isNotBlank() && date.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (lookingUp) "Looking up flight…" else "Look up airports & status")
                    }
                } else {
                    Text(
                        "Optional: enable BYOK AeroDataBox or FlightAware lookup in Settings to auto-fill airports, aircraft, and gates.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                gateSummary?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val airportDb by androidx.compose.runtime.produceState<dev.mriya.domain.AirportDb?>(null) {
                    value = dev.mriya.domain.AirportDb.get(ctx)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AirportField("From", from, { from = it; error = null }, airportDb)
                    AirportField("To", to, { to = it; error = null }, airportDb)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        from, to, date, airline, number,
                        aircraftType, callsign, registration, status,
                        departTsOverride, arriveTsOverride, onDismiss,
                    ) { error = it }
                },
                enabled = from.isNotBlank() && to.isNotBlank() && date.isNotBlank(),
            ) { Text("Save flight") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewImportSheet(candidates: List<ImportCandidate>, onToggle: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val picked = candidates.count { it.selected }
    val hasUnverified = candidates.any { it.unverified && !it.duplicate }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Review flights", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(
                if (hasUnverified) "Plain-text matches are unticked by default — tick any leg after verifying its route and date."
                else "Check the dates and routes. Flights already in your passport are unticked.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(candidates, key = { it.flight.id }) { c ->
                    val f = c.flight
                    Row(Modifier.fillMaxWidth().clickable { onToggle(f.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.selected, onCheckedChange = { onToggle(f.id) })
                        if (!f.airline.isNullOrBlank()) AirlineBadge(f.airline, size = 32.dp)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("${f.fromAirport}  →  ${f.toAirport}", fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            val num = f.flightNumber?.let { "  •  ${f.airline.orEmpty()} $it".replace("  •   ", "  •  ") } ?: ""
                            Text(
                                "${dayFmt.format(Instant.ofEpochSecond(f.departTs))}$num  •  ${"%,d".format(f.distanceKm.roundToInt())} km" +
                                    when {
                                        c.enrichesExisting -> "  •  matches flight, will add airline/flight #"
                                        c.duplicate -> "  •  already added"
                                        c.restoreId != null -> "  •  dismissed earlier, will be restored"
                                        else -> ""
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            FlightSourceBadge(f)
                            if (c.unverified && !c.duplicate) {
                                Text(
                                    "unverified — check route & date",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfirm, enabled = picked > 0, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (picked == 1) "Add 1 flight" else "Add $picked flights", fontWeight = FontWeight.ExtraBold)
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

/**
 * Optional Gmail connection: connect once with Google's own sign-in, then search your mail from here. Read-only; the
 * mail is read on this phone, the flights go through the usual review, and the mail itself is not kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmailSheet(
    state: MainViewModel.GmailUi,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onSearch: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var query by remember { mutableStateOf(dev.mriya.data.GmailClient.DEFAULT_QUERY) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gmail", fontWeight = FontWeight.Black, fontSize = 22.sp)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                !state.configured -> Text(
                    "This build has no Google client set up, so Gmail is unavailable. Everything else works without it. " +
                        "Setup steps are in docs/GMAIL_SETUP.md.",
                    color = muted, fontSize = 13.sp,
                )
                !state.connected -> {
                    Text(
                        "Sign in with Google once, then search your mail for flight bookings from inside Mriya. " +
                            "Mriya can only read mail: it cannot send, delete or change anything. Emails are read on this phone, " +
                            "only flights you approve are saved, and the emails themselves are not kept. " +
                            "Google sees the search, as it does for any app you allow. You can disconnect here, or in your Google account, at any time.",
                        color = muted, fontSize = 13.sp,
                    )
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Connect Gmail") }
                }
                else -> {
                    Text("Search your mail with the same words as Gmail's search box. The default looks for booking emails from the last three years.", color = muted, fontSize = 13.sp)
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().height(120.dp), label = { Text("Gmail search") })
                    Button(onClick = { onSearch(query) }, enabled = !busy && query.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text(if (busy) "Searching…" else "Search and find flights")
                    }
                    state.progress?.let { Text(it, color = muted, fontSize = 12.sp) }
                    TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) { Text("Disconnect Gmail") }
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp) }
        }
    }
}
