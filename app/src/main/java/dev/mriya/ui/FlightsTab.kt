package dev.mriya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.data.Flight
import dev.mriya.data.originLabel
import dev.mriya.domain.AirlineDirectory
import dev.mriya.domain.imports.filterFlights
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun FlightsScreen(
    all: List<Flight>,
    onDismiss: (Flight) -> Unit,
    onRestore: (Flight) -> Unit,
    onEdit: (Flight) -> Unit,
    onAdd: () -> Unit,
) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val hiddenCount = all.count { it.dismissed }
    val years = remember(all) { all.map { year(it.departTs) }.distinct().sortedDescending() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val airportDb by androidx.compose.runtime.produceState<dev.mriya.domain.AirportDb?>(null) {
        value = dev.mriya.domain.AirportDb.get(ctx)
    }
    val flights = remember(all, showHidden, selectedYear, query, airportDb) {
        val db = airportDb
        filterFlights(all, showHidden, selectedYear, query, placeText = { code -> db?.byIata(code)?.let { "${it.city} ${it.name}" }.orEmpty() })
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Flights", "${all.size - hiddenCount}")
                Spacer(Modifier.weight(1f))
                Chip("+ Add flights", Modifier.clickable(onClick = onAdd), selected = true)
                Spacer(Modifier.width(8.dp))
                if (hiddenCount > 0) {
                    Chip(
                        if (showHidden) "Hide dismissed" else "Show $hiddenCount dismissed",
                        Modifier.clickable { showHidden = !showHidden },
                        selected = showHidden,
                    )
                }
            }
        }
        if (all.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter by airport, airline, or flight #") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (years.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip("All years", Modifier.clickable { selectedYear = null }, selected = selectedYear == null) }
                        items(years) { y ->
                            Chip("$y", Modifier.clickable { selectedYear = if (selectedYear == y) null else y }, selected = selectedYear == y)
                        }
                    }
                }
            }
        }
        if (all.isEmpty()) {
            item {
                Text(
                    "No flights yet. Detected flights appear after a sync; or add one from a booking, boarding pass or calendar.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (flights.isEmpty()) {
            item {
                Text("No matching flights for the current filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(flights, key = { it.id }) { f ->
            MriyaCard(Modifier.fillMaxWidth().alpha(if (f.dismissed) 0.55f else 1f)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!f.airline.isNullOrBlank()) { AirlineBadge(f.airline, size = 42.dp); Spacer(Modifier.width(14.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${f.fromAirport ?: "%.1f°, %.1f°".format(f.from.lat, f.from.lon)}  →  ${f.toAirport ?: "%.1f°, %.1f°".format(f.to.lat, f.to.lon)}",
                            fontFamily = Mono,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        val carrierPart = listOfNotNull(f.airline?.takeIf { it.isNotBlank() }, f.flightNumber?.takeIf { it.isNotBlank() })
                            .joinToString(" ")
                            .takeIf { it.isNotEmpty() }
                            ?.let { "  •  $it" } ?: ""
                        val equipPart = f.aircraftType?.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""
                        Text(
                            "${fmt(f.departTs)}$carrierPart$equipPart  •  ${"%,d".format(f.distanceKm.roundToInt())} km",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlightSourceBadge(f)
                    }
                    if (f.dismissed) {
                        RoundIcon({ onRestore(f) }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Restore flight", Modifier.size(18.dp))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoundIcon({ onEdit(f) }) {
                                Icon(Icons.Default.Edit, "Edit airline or flight details", Modifier.size(18.dp))
                            }
                            RoundIcon({ onDismiss(f) }) {
                                Icon(Icons.Default.Close, "Not a flight", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onAdd,
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text("Add flight") },
        containerColor = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    )
    }
}

@Composable
internal fun EditFlightDialog(
    flight: Flight,
    recentAirlines: List<String>,
    onDismiss: () -> Unit,
    onSave: (airline: String, flightNumber: String, aircraftType: String, callsign: String, registration: String, onDone: () -> Unit) -> Unit,
    lookupAction: (@Composable (airline: String, number: String, dateIso: String, onApplied: (aircraftType: String, callsign: String, registration: String) -> Unit) -> Unit)? = null,
) {
    var airline by rememberSaveable(flight.id) { mutableStateOf(flight.airline.orEmpty()) }
    var number by rememberSaveable(flight.id) { mutableStateOf(flight.flightNumber.orEmpty()) }
    var aircraftType by rememberSaveable(flight.id) { mutableStateOf(flight.aircraftType.orEmpty()) }
    var callsign by rememberSaveable(flight.id) { mutableStateOf(flight.callsign.orEmpty()) }
    var registration by rememberSaveable(flight.id) { mutableStateOf(flight.registration.orEmpty()) }

    val quickChips = remember(recentAirlines) {
        (recentAirlines + listOf("UA", "AA", "DL", "LH", "LO", "BA", "AF", "TK"))
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
    }
    val previewName = remember(airline) {
        val code = airline.trim().uppercase(Locale.ROOT)
        if (code.length >= 2) AirlineDirectory.lookup(code).name.takeIf { it != code } else null
    }
    val departIso = remember(flight.departTs) {
        java.time.Instant.ofEpochSecond(flight.departTs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${flight.fromAirport ?: "Flight"} → ${flight.toAirport ?: ""}",
                fontFamily = Mono,
                fontWeight = FontWeight.Black,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${fmt(flight.departTs)}  •  ${"%,d".format(flight.distanceKm.roundToInt())} km",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlightSourceBadge(flight)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(quickChips) { code ->
                        Chip(
                            code,
                            Modifier.clickable { airline = code },
                            selected = airline.equals(code, ignoreCase = true),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = airline,
                        onValueChange = { airline = it.uppercase(Locale.ROOT).take(3) },
                        label = { Text("Airline (IATA)") },
                        placeholder = { Text("UA") },
                        singleLine = true,
                        supportingText = previewName?.let { { Text(it) } },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it.uppercase(Locale.ROOT).take(8) },
                        label = { Text("Flight #") },
                        placeholder = { Text("123") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                lookupAction?.invoke(airline, number, departIso) { type, cs, reg ->
                    if (type.isNotBlank()) aircraftType = type
                    if (cs.isNotBlank()) callsign = cs
                    if (reg.isNotBlank()) registration = reg
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aircraftType,
                        onValueChange = { aircraftType = it.uppercase(Locale.ROOT).take(12) },
                        label = { Text("Aircraft") },
                        placeholder = { Text("B789") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = callsign,
                        onValueChange = { callsign = it.uppercase(Locale.ROOT).take(10) },
                        label = { Text("Callsign") },
                        placeholder = { Text("UAL123") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = registration,
                    onValueChange = { registration = it.uppercase(Locale.ROOT).take(12) },
                    label = { Text("Registration / Tail # (opt.)") },
                    placeholder = { Text("N27958") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(airline, number, aircraftType, callsign, registration, onDismiss) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Compact origin pill indicating where a flight record came from (Dawarich, Google Calendar, Screenshot, etc.). */
@Composable
internal fun FlightSourceBadge(flight: Flight, modifier: Modifier = Modifier) {
    val icon = when {
        flight.source == "takeout" -> Icons.Default.History
        flight.importOrigin == "google-calendar" || flight.importOrigin == "calendar" -> Icons.Default.CalendarMonth
        flight.importOrigin == "screenshot" -> Icons.Default.Image
        flight.importOrigin == "boarding-pass" -> Icons.Default.QrCode2
        flight.importOrigin == "ics" -> Icons.Default.Event
        flight.importOrigin == "email" -> Icons.Default.Email
        flight.importOrigin == "manual" -> Icons.Default.Edit
        else -> Icons.Default.MyLocation
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                flight.originLabel(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
