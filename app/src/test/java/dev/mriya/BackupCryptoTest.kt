package dev.mriya

import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.HolderProfile
import dev.mriya.data.LatLon
import dev.mriya.data.TakeoutData
import dev.mriya.data.UserData
import dev.mriya.domain.BackupBundle
import dev.mriya.domain.BackupCrypto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class BackupCryptoTest {
    private fun sampleBundle(): BackupBundle {
        val photo = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))
        return BackupBundle(
            createdAt = 1_750_000_000L,
            userData = UserData(
                dismissedFlights = setOf("BER-LHR-20000"),
                hiddenCountries = setOf("XX"),
                seenTrophies = setOf("countries_1"),
            ),
            imports = listOf(
                Flight(LatLon(52.36, 13.5), LatLon(51.47, -0.45), 1_750_000_000L, 1_750_007_200L, 930.0, "BER", "LHR", source = "imported")
            ),
            takeout = TakeoutData(
                stamps = mapOf("DE" to CountryStamp("Germany", "DE", 100L, 200L, setOf("2024-01-01"), emptySet())),
                flights = emptyList(),
                points = 42L,
                importedAt = 1_750_000_000L,
            ),
            profile = HolderProfile("Olena", "Kovalenko", "UKR", "1994-08-24", "Lviv", "MR1234567"),
            photoBase64 = photo,
        )
    }

    @Test
    fun `encrypt and decrypt round trips full backup bundle`() {
        val bundle = sampleBundle()
        val encrypted = BackupCrypto.encrypt(bundle, "correct-horse-battery".toCharArray(), iterations = 1_000)
        val decoded = BackupCrypto.decrypt(encrypted, "correct-horse-battery".toCharArray())
        assertEquals(bundle, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong passphrase fails decryption`() {
        val encrypted = BackupCrypto.encrypt(sampleBundle(), "correct-horse-battery".toCharArray(), iterations = 1_000)
        BackupCrypto.decrypt(encrypted, "wrong-passphrase".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tampered ciphertext fails decryption`() {
        val encrypted = BackupCrypto.encrypt(sampleBundle(), "correct-horse-battery".toCharArray(), iterations = 1_000)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0xFF).toByte()
        BackupCrypto.decrypt(encrypted, "correct-horse-battery".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bad magic header is rejected`() {
        val encrypted = BackupCrypto.encrypt(sampleBundle(), "correct-horse-battery".toCharArray(), iterations = 1_000)
        encrypted[0] = 'X'.code.toByte()
        BackupCrypto.decrypt(encrypted, "correct-horse-battery".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passphrase is rejected`() {
        BackupCrypto.encrypt(sampleBundle(), "short".toCharArray(), iterations = 1_000)
    }

    @Test
    fun `serialized backup never includes serverUrl or apiKey fields`() {
        val raw = Json.encodeToString(BackupBundle.serializer(), sampleBundle())
        assertFalse(raw.contains("apiKey", ignoreCase = true))
        assertFalse(raw.contains("api_key", ignoreCase = true))
        assertFalse(raw.contains("serverUrl", ignoreCase = true))
    }
}
