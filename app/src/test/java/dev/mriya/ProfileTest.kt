package dev.mriya

import dev.mriya.data.HolderProfile
import dev.mriya.domain.AesGcmCodec
import dev.mriya.domain.EnvelopeBlobCodec
import dev.mriya.domain.ProfileUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class ProfileTest {
    @Test
    fun `passport number matches MR plus seven digits`() {
        repeat(20) {
            val n = ProfileUtils.generatePassportNumber()
            assertTrue("Expected MR####### but got $n", ProfileUtils.isValidPassportNumber(n))
        }
        assertFalse(ProfileUtils.isValidPassportNumber("AB1234567"))
        assertFalse(ProfileUtils.isValidPassportNumber("MR123"))
    }

    @Test
    fun `mrz check digit follows ICAO 9303 mod-10 weighting`() {
        assertEquals('3', ProfileUtils.mrzCheckDigit("L898902C<"))
        assertEquals('3', ProfileUtils.mrzCheckDigit("MR1234567"))
        assertEquals('1', ProfileUtils.mrzCheckDigit("940824"))
    }

    @Test
    fun `identity MRZ lines are 44 chars with TD3 check digits and MRY issuer`() {
        val p = HolderProfile(
            givenNames = "Olena Maria",
            surname = "Kovalenko",
            nationality = "UKR",
            dateOfBirth = "1994-08-24",
            placeOfBirth = "Lviv",
            passportNumber = "MR1234567",
        )
        val (l1, l2) = ProfileUtils.buildIdentityMrz(p)
        assertEquals(44, l1.length)
        assertEquals(44, l2.length)
        assertTrue(l1.startsWith("P<MRYKOVALENKO<<OLENA<MARIA"))
        assertEquals("MR12345673UKR9408241<3612314MRIYA<<<<<<<<<12", l2)
    }

    @Test
    fun `AES GCM codec round trips and rejects tampered ciphertext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plain = "encrypted-passport-profile".toByteArray()
        val blob = AesGcmCodec.encrypt(key, plain)
        assertArrayEquals(plain, AesGcmCodec.decrypt(key, blob))

        val tampered = blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0xFF).toByte() }
        assertNull(AesGcmCodec.decrypt(key, tampered))
    }

    @Test
    fun `envelope blob codec round trips large payload and falls back to legacy v1`() {
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val largePhoto = ByteArray(120 * 1024) { (it * 31).toByte() }

        val wrapped = EnvelopeBlobCodec.encrypt(largePhoto) { dek -> AesGcmCodec.encrypt(wrappingKey, dek) }
        val decoded = EnvelopeBlobCodec.decrypt(
            blob = wrapped,
            unwrapDek = { AesGcmCodec.decrypt(wrappingKey, it) },
            legacyDecrypt = { AesGcmCodec.decrypt(wrappingKey, it) },
        )
        assertArrayEquals(largePhoto, decoded)

        val tampered = wrapped.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0xFF).toByte() }
        assertNull(
            EnvelopeBlobCodec.decrypt(
                blob = tampered,
                unwrapDek = { AesGcmCodec.decrypt(wrappingKey, it) },
                legacyDecrypt = { AesGcmCodec.decrypt(wrappingKey, it) },
            )
        )

        val legacy = AesGcmCodec.encrypt(wrappingKey, largePhoto)
        val decodedLegacy = EnvelopeBlobCodec.decrypt(
            blob = legacy,
            unwrapDek = { AesGcmCodec.decrypt(wrappingKey, it) },
            legacyDecrypt = { AesGcmCodec.decrypt(wrappingKey, it) },
        )
        assertArrayEquals(largePhoto, decodedLegacy)
    }
}
