package com.bydcollector.collector.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AesGcmPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray
)

internal object AesGcmSecretCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8

    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): AesGcmPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        return AesGcmPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    fun decrypt(
        key: SecretKey,
        payload: AesGcmPayload,
        associatedData: ByteArray
    ): ByteArray {
        require(payload.iv.size == IV_SIZE_BYTES) { "Invalid AES/GCM IV" }
        require(payload.ciphertext.size >= TAG_SIZE_BYTES) { "Invalid AES/GCM ciphertext" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, payload.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.ciphertext)
    }

    fun encode(payload: AesGcmPayload): String {
        require(payload.iv.size == IV_SIZE_BYTES) { "Invalid AES/GCM IV" }
        require(payload.ciphertext.size >= TAG_SIZE_BYTES) { "Invalid AES/GCM ciphertext" }

        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            VERSION,
            encoder.encodeToString(payload.iv),
            encoder.encodeToString(payload.ciphertext)
        ).joinToString(".")
    }

    fun decode(encoded: String): AesGcmPayload? {
        val parts = encoded.split('.', limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) return null

        return try {
            val decoder = Base64.getUrlDecoder()
            val payload = AesGcmPayload(
                iv = decoder.decode(parts[1]),
                ciphertext = decoder.decode(parts[2])
            )
            payload.takeIf {
                it.iv.size == IV_SIZE_BYTES && it.ciphertext.size >= TAG_SIZE_BYTES
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
