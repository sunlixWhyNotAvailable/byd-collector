package com.bydcollector.collector.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** Writes one independently encrypted payload and reports whether it was persisted. */
    fun write(name: String, value: String): Boolean {
        requireValidName(name)
        return synchronized(STORE_LOCK) { writeLocked(name, value) }
    }

    /** Returns null when the secret is absent, malformed, or no longer decryptable. */
    fun read(name: String): String? {
        requireValidName(name)
        return synchronized(STORE_LOCK) { readLocked(name) }
    }

    fun clear(name: String): Boolean {
        requireValidName(name)
        return synchronized(STORE_LOCK) { removePayloadLocked(payloadKey(name)) }
    }

    /** Clears every encrypted payload while retaining the shared Keystore key. */
    fun clearAll(): Boolean = synchronized(STORE_LOCK) { clearAllPayloadsLocked() }

    private fun writeLocked(name: String, value: String): Boolean {
        return try {
            writeOnceLocked(name, value)
        } catch (error: GeneralSecurityException) {
            if (error is InvalidKeyException || error is UnrecoverableKeyException) {
                resetAndWriteLocked(name, value)
            } else {
                false
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun writeOnceLocked(name: String, value: String): Boolean {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        return try {
            val payload = AesGcmSecretCipher.encrypt(
                key = getOrCreateKeyLocked(),
                plaintext = plaintext,
                associatedData = name.toByteArray(Charsets.UTF_8)
            )
            preferences.edit()
                .putString(payloadKey(name), AesGcmSecretCipher.encode(payload))
                .commit()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun resetAndWriteLocked(name: String, value: String): Boolean {
        return try {
            resetKeyAndPayloadsLocked()
            writeOnceLocked(name, value)
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun readLocked(name: String): String? {
        val storageKey = payloadKey(name)
        val encoded = try {
            preferences.getString(storageKey, null)
        } catch (_: ClassCastException) {
            removePayloadLocked(storageKey)
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null

        val payload = AesGcmSecretCipher.decode(encoded) ?: run {
            removePayloadLocked(storageKey)
            return null
        }

        val key = try {
            existingKeyLocked()
        } catch (error: GeneralSecurityException) {
            if (error is InvalidKeyException || error is UnrecoverableKeyException) {
                resetKeyAndPayloadsQuietlyLocked()
            }
            return null
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }

        if (key == null) {
            clearAllPayloadsLocked()
            return null
        }

        return try {
            val plaintext = AesGcmSecretCipher.decrypt(
                key = key,
                payload = payload,
                associatedData = name.toByteArray(Charsets.UTF_8)
            )
            try {
                plaintext.toString(Charsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        } catch (error: GeneralSecurityException) {
            if (error is InvalidKeyException) {
                resetKeyAndPayloadsQuietlyLocked()
            } else {
                removePayloadLocked(storageKey)
            }
            null
        } catch (_: IllegalArgumentException) {
            removePayloadLocked(storageKey)
            null
        } catch (_: RuntimeException) {
            removePayloadLocked(storageKey)
            null
        }
    }

    private fun existingKeyLocked(): SecretKey? {
        return loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKeyLocked(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        clearAllPayloadsLocked()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun resetKeyAndPayloadsLocked() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        clearAllPayloadsLocked()
    }

    private fun resetKeyAndPayloadsQuietlyLocked() {
        try {
            resetKeyAndPayloadsLocked()
        } catch (_: GeneralSecurityException) {
            clearAllPayloadsLocked()
        } catch (_: IOException) {
            clearAllPayloadsLocked()
        } catch (_: RuntimeException) {
            clearAllPayloadsLocked()
        }
    }

    private fun removePayloadLocked(storageKey: String): Boolean {
        return try {
            preferences.edit().remove(storageKey).commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun clearAllPayloadsLocked(): Boolean {
        return try {
            preferences.edit().clear().commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun payloadKey(name: String): String = "$PAYLOAD_PREFIX$name"

    private fun requireValidName(name: String) {
        require(name.isNotBlank()) { "Secret name must not be blank" }
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.bydcollector.collector.secret_store.v1"
        const val KEY_SIZE_BITS = 256
        const val PREFERENCES_NAME = "keystore_secret_payloads"
        const val PAYLOAD_PREFIX = "secret."

        // ponytail: process-wide lock; split only if secret access becomes a hot path.
        val STORE_LOCK = Any()
    }
}
