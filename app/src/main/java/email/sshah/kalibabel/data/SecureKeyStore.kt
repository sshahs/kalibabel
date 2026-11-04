package email.sshah.kalibabel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import email.sshah.kalibabel.providers.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores bring-your-own API keys encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed where available) and never leaves the device. Decrypted values are
 * cached in memory so the hot path (every request) does not pay for a Keystore round-trip.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("kalibabel_keys", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, String>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever a key is added, changed or removed. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(id: ProviderId): String? {
        cache[id.name]?.let { return it }
        val stored = prefs.getString(id.name, null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { Log.w(TAG, "Could not decrypt key for $id; it will need to be re-entered", it) }
            .getOrNull()
            ?.also { cache[id.name] = it }
    }

    fun has(id: ProviderId): Boolean = prefs.contains(id.name)

    fun put(id: ProviderId, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            cache.remove(id.name)
            prefs.edit { remove(id.name) }
        } else {
            cache[id.name] = trimmed
            prefs.edit { putString(id.name, encrypt(trimmed)) }
        }
        _version.value++
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "SecureKeyStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kalibabel_api_keys_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
