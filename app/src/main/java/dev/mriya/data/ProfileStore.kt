package dev.mriya.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import dev.mriya.domain.EnvelopeBlobCodec
import dev.mriya.domain.ProfileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stores the holder profile and square 512x512 portrait photo encrypted in app-private storage
 * (`profile.enc` and `profile_photo.enc` with `.bak` recovery). Never uploaded.
 */
class ProfileStore(context: Context) {
    private val ctx = context.applicationContext
    private val secrets by lazy { SecureStore(ctx) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileFile get() = File(ctx.filesDir, "profile.enc")
    private val photoFile get() = File(ctx.filesDir, "profile_photo.enc")

    suspend fun load(): Pair<HolderProfile?, ByteArray?> = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = readEncryptedWithBackup(profileFile) { blob ->
                val plain = secrets.decryptBytes(blob) ?: return@readEncryptedWithBackup null
                json.decodeFromString(HolderProfile.serializer(), String(plain, Charsets.UTF_8))
            }
            val photo = readEncryptedWithBackup(photoFile) { blob ->
                EnvelopeBlobCodec.decrypt(
                    blob = blob,
                    unwrapDek = { secrets.decryptBytes(it) },
                    legacyDecrypt = { secrets.decryptBytes(it) },
                )
            }
            p to photo
        }
    }

    suspend fun saveProfile(profile: HolderProfile): HolderProfile = withContext(Dispatchers.IO) {
        val normalized = if (ProfileUtils.isValidPassportNumber(profile.passportNumber)) {
            profile.copy(passportNumber = profile.passportNumber.trim().uppercase())
        } else {
            profile.copy(passportNumber = ProfileUtils.generatePassportNumber())
        }
        val encrypted = secrets.encryptBytes(json.encodeToString(HolderProfile.serializer(), normalized).toByteArray(Charsets.UTF_8))
        lock.withLock { AtomicFileStore.writeAtomicWithBackup(profileFile, encrypted) }
        normalized
    }

    suspend fun savePhotoFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val jpeg = decodeSquare512(uri) ?: return@withContext null
        val encrypted = EnvelopeBlobCodec.encrypt(jpeg) { secrets.encryptBytes(it) }
        lock.withLock { AtomicFileStore.writeAtomicWithBackup(photoFile, encrypted) }
        jpeg
    }

    suspend fun savePhotoBytes(jpegBytes: ByteArray) = withContext(Dispatchers.IO) {
        val encrypted = EnvelopeBlobCodec.encrypt(jpegBytes) { secrets.encryptBytes(it) }
        lock.withLock { AtomicFileStore.writeAtomicWithBackup(photoFile, encrypted) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            AtomicFileStore.deleteWithBackup(profileFile)
            AtomicFileStore.deleteWithBackup(photoFile)
            Unit
        }
    }

    private fun <T> readEncryptedWithBackup(file: File, decode: (ByteArray) -> T?): T? {
        for (candidate in listOf(file, AtomicFileStore.backupFile(file))) {
            if (!candidate.exists()) continue
            val parsed = runCatching { decode(candidate.readBytes()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun decodeSquare512(uri: Uri): ByteArray? {
        val decoded = decodeViaImageDecoder(uri) ?: decodeViaStreamBytes(uri) ?: return null
        return try {
            val side = min(decoded.width, decoded.height)
            if (side <= 0) return null
            val x = (decoded.width - side) / 2
            val y = (decoded.height - side) / 2
            val cropped = if (x == 0 && y == 0 && decoded.width == side && decoded.height == side) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, x, y, side, side).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
            val scaled = if (side == TARGET_PX) cropped else cropped.scale(TARGET_PX, TARGET_PX).also {
                if (it !== cropped) cropped.recycle()
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 84, out)
            if (scaled !== cropped) scaled.recycle() else cropped.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            runCatching { decoded.recycle() }
            null
        }
    }

    private fun decodeViaImageDecoder(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val minSide = min(w, h)
                if (minSide > TARGET_PX) {
                    val ratio = TARGET_PX.toFloat() / minSide.toFloat()
                    val tw = max(TARGET_PX, (w * ratio).roundToInt())
                    val th = max(TARGET_PX, (h * ratio).roundToInt())
                    decoder.setTargetSize(tw, th)
                }
            }
        }.getOrNull()
    }

    private fun decodeViaStreamBytes(uri: Uri): Bitmap? {
        val bytes = readBoundedBytes(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val minDim = min(bounds.outWidth, bounds.outHeight)
        while (minDim / (sample * 2) >= TARGET_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun readBoundedBytes(uri: Uri): ByteArray? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        private const val TARGET_PX = 512
        private const val MAX_INPUT_BYTES = 25 * 1024 * 1024
        private val lock = Mutex()
    }
}
