package com.milen.grounpringtonesetter.data.prefs

import android.app.Application
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// DataStore instance (file: secure_prefs.preferences_pb)
private val Application.secureDataStore by preferencesDataStore(name = "secure_prefs")

/**
 * Migrates once from EncryptedSharedPreferences to DataStore (manual AES-GCM),
 * keeps the SAME keys to avoid data loss, then uses only the new path.
 * Public sync API remains for early app init; new suspend API is preferred elsewhere.
 */
internal class SecurePreferences(
    private val app: Application,
    private val dispatcherProvider: DispatchersProvider = DispatchersProvider,
) {

    // --- LEGACY (read-only, for one-time migration) ---
    @Suppress("DEPRECATION")
    private val legacyPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            app,
            "encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- NEW CRYPTO ---
    private val keyAlias = "secure_prefs_aes_gcm_v1"

    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun encryptToBase64(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        // pack = [ivLen][iv][ciphertext]
        val pack = ByteBuffer.allocate(4 + iv.size + ct.size)
            .putInt(iv.size).put(iv).put(ct).array()
        return Base64.encodeToString(pack, Base64.NO_WRAP)
    }

    private fun decryptFromBase64(packed: String): ByteArray? = try {
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(bytes)
        val ivLen = buf.int
        val iv = ByteArray(ivLen).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateAesKey(),
            GCMParameterSpec(128, iv)
        )
        cipher.doFinal(ct)
    } catch (_: Throwable) {
        null
    }

    // --- MIGRATION FLAG ---
    private val migrated = booleanPreferencesKey("__secure_prefs_migrated_v1")

    private fun sKey(name: String) = stringPreferencesKey(name)

    // -------------------------
    // INIT MIGRATION
    // -------------------------

    /** Suspend variant (preferred): run at startup from a coroutine. */
    suspend fun initMigrationAsync() {
        withContext(dispatcherProvider.io) {
            val already = app.secureDataStore.data.first()[migrated] == true
            if (!already) {
                val all = legacyPrefs.all
                app.secureDataStore.edit { ds ->
                    for ((k, v) in all) {
                        if (v is String) {
                            val enc = encryptToBase64(v.encodeToByteArray())
                            ds[sKey(k)] = enc
                        }
                        // If you had other types, encode them to String and encrypt similarly.
                    }
                    ds[migrated] = true
                }
                // Optional: legacyPrefs.edit().clear().apply()
            }
        }
    }

    /** Sync wrapper (kept for convenience in early app init if needed). */
    fun initMigration() {
        runBlocking { initMigrationAsync() }
    }

    // -------------------------
    // PUBLIC SYNC API (unchanged signatures)
    // These delegate to the suspend API internally.
    // -------------------------

    fun saveString(key: String, value: String) {
        runBlocking { putStringAsync(key, value) }
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return runBlocking { getStringAsync(key, defaultValue) }
    }

    fun remove(key: String) {
        runBlocking { removeAsync(key) }
    }

    // -------------------------
    // NEW SUSPEND API (use these from coroutines)
    // -------------------------

    suspend fun putStringAsync(key: String, value: String) {
        withContext(dispatcherProvider.io) {
            val enc = encryptToBase64(value.encodeToByteArray())
            app.secureDataStore.edit { it[sKey(key)] = enc }
        }
    }

    suspend fun getStringAsync(key: String, defaultValue: String? = null): String? {
        return withContext(dispatcherProvider.io) {
            val enc = app.secureDataStore.data.first()[sKey(key)]
            if (enc != null) {
                decryptFromBase64(enc)?.decodeToString() ?: defaultValue
            } else {
                @Suppress("DEPRECATION")
                legacyPrefs.getString(key, defaultValue)
            }
        }
    }

    suspend fun removeAsync(key: String) {
        withContext(dispatcherProvider.io) {
            app.secureDataStore.edit { it.remove(sKey(key)) }
        }
    }

    // Optional: batch editor to minimize multiple writes
    suspend fun editAsync(block: suspend (EditScope) -> Unit) {
        withContext(dispatcherProvider.io) {
            app.secureDataStore.edit { ds ->
                block(EditScope(ds))
            }
        }
    }

    // Helper scope so callers don’t touch DataStore API directly in batch mode
    class EditScope internal constructor(private val ds: androidx.datastore.preferences.core.MutablePreferences) {
        operator fun set(key: String, value: String) {
            ds[stringPreferencesKey(key)] = value
        }

        fun remove(key: String) {
            ds.remove(stringPreferencesKey(key))
        }
    }
}
