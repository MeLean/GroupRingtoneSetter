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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// DataStore instance (file will be secure_prefs.preferences_pb)
private val Application.secureDataStore by preferencesDataStore(name = "secure_prefs")

/**
 * Migrates once from EncryptedSharedPreferences to DataStore(with manual AES-GCM encryption),
 * keeps the SAME keys to avoid data loss, and then uses only the new path.
 * Public API mirrors the old helper.
 */
internal class SecurePreferences(private val app: Application) {

    // --- LEGACY (read-only) ----
    // Kept only for migration. Same file name and schemes as before.
    @Suppress("DEPRECATION")
    private val legacyPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            app,
            "encrypted_prefs", // your original file name
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

    // Helpers for typed keys (keeps your original key names)
    private fun sKey(name: String) = stringPreferencesKey(name)

    /** Call once at app start (e.g., in Application.onCreate()). */
    fun initMigration() {
        runBlocking(Dispatchers.IO) {
            val already = app.secureDataStore.data.first()[migrated] == true
            if (!already) {
                val all = legacyPrefs.all
                app.secureDataStore.edit { ds ->
                    for ((k, v) in all) {
                        if (v is String) {
                            val enc = encryptToBase64(v.encodeToByteArray())
                            ds[sKey(k)] = enc
                        }
                        // If you used other types before, handle them here (encode to String, then encrypt)
                    }
                    ds[migrated] = true
                }
                // Optional: legacyPrefs.edit().clear().apply()
            }
        }
    }

    // --- PUBLIC API (same as before) ---
    fun saveString(key: String, value: String) {
        runBlocking(Dispatchers.IO) {
            val enc = encryptToBase64(value.encodeToByteArray())
            app.secureDataStore.edit { it[sKey(key)] = enc }
        }
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return runBlocking(Dispatchers.IO) {
            val enc = app.secureDataStore.data.first()[sKey(key)]
            if (enc != null) {
                decryptFromBase64(enc)?.decodeToString() ?: defaultValue
            } else {
                // Fallback to legacy in case migration missed a key
                @Suppress("DEPRECATION")
                legacyPrefs.getString(key, defaultValue)
            }
        }
    }

    fun remove(key: String) {
        runBlocking(Dispatchers.IO) {
            app.secureDataStore.edit { it.remove(sKey(key)) }
        }
    }
}
