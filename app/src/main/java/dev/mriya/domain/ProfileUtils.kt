package dev.mriya.domain

import dev.mriya.data.HolderProfile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProfileUtils {
    private val passportRegex = Regex("^MR\\d{7}$")
    private val dobDigits = Regex("\\d+")

    fun generatePassportNumber(random: SecureRandom = SecureRandom()): String =
        "MR" + (1_000_000 + random.nextInt(9_000_000))

    fun isValidPassportNumber(s: String): Boolean = passportRegex.matches(s.trim().uppercase())

    /** Converts free text into uppercase ICAO-style filler-padded ASCII (`A-Z`, `0-9`, `<`). */
    fun sanitizeMrz(s: String): String {
        val norm = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
        val out = StringBuilder(norm.length)
        for (ch in norm) {
            when {
                ch in 'a'..'z' -> out.append(ch.uppercaseChar())
                ch in 'A'..'Z' || ch in '0'..'9' -> out.append(ch)
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                else -> out.append('<')
            }
        }
        return out.toString().replace(Regex("<+"), "<").trim('<')
    }

    private fun pad44(s: String): String = s.take(44).padEnd(44, '<')

    /** Standard ICAO 9303 mod-10 weighted check digit (`7, 3, 1`) for TD3 MRZ fields. */
    fun mrzCheckDigit(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in field.indices) {
            val ch = field[i]
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                else -> 0
            }
            sum += v * weights[i % 3]
        }
        return ('0' + (sum % 10))
    }

    private fun mrzDob(dob: String): String {
        val parts = dobDigits.findAll(dob).map { it.value }.toList()
        if (parts.size >= 3 && parts[0].length == 4) {
            val yy = parts[0].takeLast(2)
            val mm = parts[1].padStart(2, '0').take(2)
            val dd = parts[2].padStart(2, '0').take(2)
            return "$yy$mm$dd"
        }
        return "<<<<<<"
    }

    /** Builds two 44-character ICAO 9303 TD3 MRZ lines with fictional issuer code `MRY`. */
    fun buildIdentityMrz(p: HolderProfile, expiryYyMmDd: String = "361231"): Pair<String, String> {
        val sur = sanitizeMrz(p.surname).ifEmpty { "TRAVELER" }
        val given = sanitizeMrz(p.givenNames).ifEmpty { "MRIYA" }
        val line1 = pad44("P<MRY$sur<<$given")

        val no = sanitizeMrz(p.passportNumber).ifEmpty { "MR0000000" }.take(9).padEnd(9, '<')
        val noCheck = mrzCheckDigit(no)
        val nat = sanitizeMrz(p.nationality).take(3).ifEmpty { "MRY" }.padEnd(3, '<')
        val yyMmDd = mrzDob(p.dateOfBirth)
        val dobCheck = mrzCheckDigit(yyMmDd)
        val exp = expiryYyMmDd.take(6).padEnd(6, '<')
        val expCheck = mrzCheckDigit(exp)
        val opt = "MRIYA".padEnd(14, '<')
        val optCheck = mrzCheckDigit(opt)
        val composite = mrzCheckDigit("$no$noCheck$yyMmDd$dobCheck$exp$expCheck$opt$optCheck")
        val line2 = "$no$noCheck$nat$yyMmDd$dobCheck<$exp$expCheck$opt$optCheck$composite"
        return line1 to line2
    }
}

/** Pure AES-GCM helper shared by Keystore storage and unit tests. */
object AesGcmCodec {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? {
        if (blob.size <= IV_LEN) return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            }
            c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }.getOrNull()
    }
}

/**
 * Envelope AES-GCM format (`MPV2`) for larger binary blobs like portrait photos:
 * encrypts payload in software with a random 256-bit DEK and wraps only the 32-byte DEK via [wrapDek].
 */
object EnvelopeBlobCodec {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())

    fun encrypt(plain: ByteArray, wrapDek: (ByteArray) -> ByteArray): ByteArray {
        val dek = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val rawDek = dek.encoded
        return try {
            val wrapped = wrapDek(rawDek)
            val body = AesGcmCodec.encrypt(dek, plain)
            ByteBuffer.allocate(4 + 2 + wrapped.size + body.size)
                .put(MAGIC)
                .putShort(wrapped.size.toShort())
                .put(wrapped)
                .put(body)
                .array()
        } finally {
            rawDek.fill(0)
        }
    }

    fun decrypt(
        blob: ByteArray,
        unwrapDek: (ByteArray) -> ByteArray?,
        legacyDecrypt: (ByteArray) -> ByteArray?,
    ): ByteArray? {
        if (blob.size < 8 || !isV2(blob)) return legacyDecrypt(blob)
        val buf = ByteBuffer.wrap(blob)
        buf.position(4)
        val wrappedLen = buf.short.toInt() and 0xFFFF
        if (wrappedLen <= 0 || wrappedLen > buf.remaining()) return null
        val wrapped = ByteArray(wrappedLen).also { buf.get(it) }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val rawDek = unwrapDek(wrapped) ?: return null
        return try {
            if (rawDek.size != 32) return null
            val dek = SecretKeySpec(rawDek, "AES")
            AesGcmCodec.decrypt(dek, body)
        } finally {
            rawDek.fill(0)
        }
    }

    private fun isV2(blob: ByteArray): Boolean =
        blob[0] == MAGIC[0] && blob[1] == MAGIC[1] && blob[2] == MAGIC[2] && blob[3] == MAGIC[3]
}
