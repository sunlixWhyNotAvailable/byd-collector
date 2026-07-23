package com.bydcollector.collector.security

import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeystoreSecretStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Test
    fun encryptDecryptRoundTripUsesNameAsAssociatedData() {
        val plaintext = "collector-secret".toByteArray()
        val payload = AesGcmSecretCipher.encrypt(key, plaintext, "mqtt.password".toByteArray())

        assertContentEquals(
            plaintext,
            AesGcmSecretCipher.decrypt(key, payload, "mqtt.password".toByteArray())
        )
        assertFailsWith<GeneralSecurityException> {
            AesGcmSecretCipher.decrypt(key, payload, "influx.password".toByteArray())
        }
    }

    @Test
    fun encryptUsesFreshIvForEachPayload() {
        val first = AesGcmSecretCipher.encrypt(key, "same".toByteArray(), "name".toByteArray())
        val second = AesGcmSecretCipher.encrypt(key, "same".toByteArray(), "name".toByteArray())

        assertTrue(first.iv.size == 12)
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun encryptionLetsTheCipherProviderGenerateItsIv() {
        val source = sourceFile("com/bydcollector/collector/security/AesGcmSecretCipher.kt").readText()

        assertTrue(source.contains("cipher.init(Cipher.ENCRYPT_MODE, key)"))
        assertTrue(source.contains("AesGcmPayload(cipher.iv, cipher.doFinal(plaintext))"))
        assertFalse(source.contains("Cipher.ENCRYPT_MODE, key, GCMParameterSpec"))
        assertFalse(source.contains("SecureRandom"))
    }

    @Test
    fun encodedPayloadRoundTripsAndMalformedPayloadsAreRejected() {
        val payload = AesGcmSecretCipher.encrypt(key, "value".toByteArray(), "name".toByteArray())
        val decoded = assertNotNull(AesGcmSecretCipher.decode(AesGcmSecretCipher.encode(payload)))

        assertContentEquals(payload.iv, decoded.iv)
        assertContentEquals(payload.ciphertext, decoded.ciphertext)
        assertNull(AesGcmSecretCipher.decode("v2.invalid.payload"))
        assertNull(AesGcmSecretCipher.decode("v1.***.***"))

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val shortCiphertext = "v1.${encoder.encodeToString(ByteArray(12))}.${encoder.encodeToString(ByteArray(15))}"
        assertNull(AesGcmSecretCipher.decode(shortCiphertext))
    }

    @Test
    fun androidStoreContractUsesOneSharedAliasAndDoesNotLog() {
        val source = sourceFile("com/bydcollector/collector/security/KeystoreSecretStore.kt").readText()

        assertTrue(source.contains("KeyGenParameterSpec.Builder(\n                    KEY_ALIAS,"))
        assertTrue(source.contains("putString(payloadKey(name),"))
        assertTrue(source.contains("fun write(name: String, value: String): Boolean"))
        assertTrue(source.contains("fun read(name: String): String?"))
        assertTrue(source.contains("fun clear(name: String): Boolean"))
        assertFalse(source.contains("android.util.Log"))
        assertFalse(source.contains("Log."))
    }

    @Test
    fun settingsVerifyEachNonEmptySecretWriteByReadingItBack() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(source.contains("secretStore.write(name, value) && secretStore.read(name) == value"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
