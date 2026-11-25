package dev.mriya.domain

import dev.mriya.data.Flight
import dev.mriya.data.FlightEdit
import dev.mriya.data.PassportData
import dev.mriya.data.UserData
import dev.mriya.data.stableKey
import java.util.Locale

private fun resolveField(editValue: String?, baseValue: String?): String? = when {
    editValue == null -> baseValue
    editValue.isBlank() -> null
    else -> editValue.trim().uppercase(Locale.ROOT)
}

fun buildFlightEditForInputs(
    currentFlight: Flight,
    airline: String,
    flightNumber: String,
    aircraftType: String,
    callsign: String,
    registration: String,
): FlightEdit {
    fun field(input: String, current: String?): String? {
        val clean = input.trim().uppercase(Locale.ROOT)
        return when {
            clean.isNotEmpty() -> clean
            current != null -> ""
            else -> null
        }
    }
    return FlightEdit(
        airline = field(airline, currentFlight.airline),
        flightNumber = field(flightNumber, currentFlight.flightNumber),
        aircraftType = field(aircraftType, currentFlight.aircraftType),
        callsign = field(callsign, currentFlight.callsign),
        registration = field(registration, currentFlight.registration),
    )
}

/** Applies the user's own choices on top of the derived data: dismissed flights, flight edits, and hidden stamps. */
fun applyUserData(d: PassportData, ud: UserData): PassportData = d.copy(
    flights = d.flights.map { base ->
        var f = if (!base.dismissed && base.stableKey() in ud.dismissedFlights) base.copy(dismissed = true) else base
        val edit = ud.flightEdits[f.stableKey()]
        if (edit != null) {
            f = f.copy(
                airline = resolveField(edit.airline, f.airline),
                flightNumber = resolveField(edit.flightNumber, f.flightNumber),
                aircraftType = resolveField(edit.aircraftType, f.aircraftType),
                callsign = resolveField(edit.callsign, f.callsign),
                registration = resolveField(edit.registration, f.registration),
            )
        }
        f
    },
    stamps = d.stamps.filterKeys { it !in ud.hiddenCountries },
    hiddenCountries = ud.hiddenCountries,
    seenTrophies = ud.seenTrophies,
)
