package dev.mriya.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.mriya.domain.AesGcmCodec
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Stores small secrets (the Dawarich API key) encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves secure hardware where available. The ciphertext is kept in
 * app-private SharedPreferences, which are also excluded from backups.
 */
class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isEmpty()) { prefs.edit { remove(name) }; return }
        val blob = AesGcmCodec.encrypt(key(), value.toByteArray())
        prefs.edit { putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    fun get(name: String): String {
        val raw = prefs.getString(name, null) ?: return ""
        return runCatching {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            AesGcmCodec.decrypt(key(), blob)?.let { String(it) } ?: ""
        }.getOrDefault("") // key invalidated or data corrupt: treat as not set, user re-enters
    }

    fun encryptBytes(plain: ByteArray): ByteArray = AesGcmCodec.encrypt(key(), plain)

    fun decryptBytes(blob: ByteArray): ByteArray? = runCatching { AesGcmCodec.decrypt(key(), blob) }.getOrNull()

    private companion object {
        const val ALIAS = "mriya_secrets_v1"
    }
}
