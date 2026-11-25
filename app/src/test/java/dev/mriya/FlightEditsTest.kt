package dev.mriya

import dev.mriya.data.Flight
import dev.mriya.data.LatLon
import dev.mriya.data.PassportData
import dev.mriya.data.UserData
import dev.mriya.data.stableKey
import dev.mriya.domain.AirlineRankBy
import dev.mriya.domain.BackupBundle
import dev.mriya.domain.BackupCrypto
import dev.mriya.domain.applyUserData
import dev.mriya.domain.buildFlightEditForInputs
import dev.mriya.domain.computeTopAirlines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightEditsTest {

    private fun detectedFlight() = Flight(
        from = LatLon(41.9786, -87.9048),
        to = LatLon(37.6189, -122.3750),
        departTs = 1_750_000_000L,
        arriveTs = 1_750_015_000L,
        distanceKm = 2970.0,
        fromAirport = "ORD",
        toAirport = "SFO",
        source = "detected",
    )

    @Test
    fun `flight edit tags airline and metadata on GPS-detected flight and feeds Top Airlines`() {
        val raw = detectedFlight()
        val edit = buildFlightEditForInputs(raw, " ua ", "123", "B789", "UAL123", "N27958")
        val ud = UserData(flightEdits = mapOf(raw.stableKey() to edit))

        val applied = applyUserData(PassportData(flights = listOf(raw)), ud)
        val updated = applied.flights.single()
        assertEquals("UA", updated.airline)
        assertEquals("123", updated.flightNumber)
        assertEquals("B789", updated.aircraftType)
        assertEquals("UAL123", updated.callsign)
        assertEquals("N27958", updated.registration)

        val stats = computeTopAirlines(applied.flights, AirlineRankBy.DISTANCE)
        assertEquals(1, stats.totalAirlines)
        assertEquals("United Airlines", stats.ranked.single().name)
        assertEquals(0, stats.untaggedFlights)
    }

    @Test
    fun `clearing previously tagged field sets it back to null`() {
        val base = detectedFlight().copy(airline = "LO", flightNumber = "3", source = "merged")
        val edit = buildFlightEditForInputs(base, "", "4", "", "", "")
        val applied = applyUserData(
            PassportData(flights = listOf(base)),
            UserData(flightEdits = mapOf(base.stableKey() to edit)),
        ).flights.single()

        assertNull(applied.airline)
        assertEquals("4", applied.flightNumber)
        assertNull(applied.aircraftType)
    }

    @Test
    fun `flightEdits survive MRY1 encrypted backup round-trip`() {
        val raw = detectedFlight()
        val edit = buildFlightEditForInputs(raw, "LH", "431", "A359", "DLH431", "")
        val bundle = BackupBundle(
            createdAt = 1_750_000_000L,
            userData = UserData(flightEdits = mapOf(raw.stableKey() to edit)),
        )
        val encrypted = BackupCrypto.encrypt(bundle, "test-passphrase-123".toCharArray(), iterations = 1_000)
        val decoded = BackupCrypto.decrypt(encrypted, "test-passphrase-123".toCharArray())
        assertEquals(bundle.userData.flightEdits, decoded.userData.flightEdits)
    }
}
