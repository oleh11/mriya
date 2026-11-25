package dev.mriya.domain

import dev.mriya.data.Flight
import dev.mriya.data.HolderProfile
import dev.mriya.data.TakeoutData
import dev.mriya.data.UserData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Self-contained user backup payload. Intentionally excludes Dawarich `serverUrl` and `apiKey`.
 */
@Serializable
data class BackupBundle(
    val version: Int = 1,
    val createdAt: Long = 0L,
    val userData: UserData = UserData(),
    val imports: List<Flight> = emptyList(),
    val takeout: TakeoutData? = null,
    val profile: HolderProfile? = null,
    val photoBase64: String? = null,
)

/**
 * Passphrase-encrypted backup format (`MRY1`):
 * `4B magic ("MRY1") || 16B salt || 4B PBKDF2 iterations (BE) || 12B GCM IV || AES-256-GCM ciphertext`.
 */
object BackupCrypto {
    const val MIN_PASSPHRASE_LENGTH = 8
    const val PBKDF2_ITERATIONS = 210_000

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'R'.code.toByte(), 'Y'.code.toByte(), '1'.code.toByte())
    private const val SALT_LEN = 16
    private const val HEADER_LEN = 4 + SALT_LEN + 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encrypt(bundle: BackupBundle, passphrase: CharArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters." }
        require(iterations >= 1_000) { "Invalid PBKDF2 iteration count." }

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)
        val plain = json.encodeToString(BackupBundle.serializer(), bundle).toByteArray(Charsets.UTF_8)
        val body = AesGcmCodec.encrypt(key, plain)

        return ByteBuffer.allocate(HEADER_LEN + body.size)
            .put(MAGIC)
            .put(salt)
            .putInt(iterations)
            .put(body)
            .array()
    }

    fun decrypt(data: ByteArray, passphrase: CharArray): BackupBundle {
        require(passphrase.isNotEmpty()) { "Passphrase is required." }
        require(data.size > HEADER_LEN + 12) { "This file is not a valid Mriya backup." }

        val buf = ByteBuffer.wrap(data)
        val magic = ByteArray(4).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "Unsupported backup file format." }

        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iterations = buf.int
        require(iterations in 1_000..5_000_000) { "Corrupt backup header." }

        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val key = deriveKey(passphrase, salt, iterations)
        val plain = AesGcmCodec.decrypt(key, body)
            ?: throw IllegalArgumentException("Wrong passphrase or corrupted backup file.")
        return json.decodeFromString(BackupBundle.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
