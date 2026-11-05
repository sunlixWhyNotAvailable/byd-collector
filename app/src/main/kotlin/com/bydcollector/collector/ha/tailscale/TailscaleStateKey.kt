package com.bydcollector.collector.ha.tailscale

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.security.AesGcmSecretCipher
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Unlike a missing login, an unreadable key must never silently create a new identity. */
internal object TailscaleStateKey {
    private const val ALIAS = "com.bydcollector.collector.tailscale.v1"
    private val aad = ALIAS.toByteArray(Charsets.UTF_8)

    @Synchronized fun readOrCreate(context: Context): ByteArray {
        check(!BuildConfig.RUNTIME_CLIENT && context.packageName == "com.bydcollector.collector")
        val root = File(context.noBackupFilesDir, "ha-tailscale")
        check(root.isDirectory || root.mkdirs()) { "Cannot create private Tailscale state directory" }
        val file = AtomicFile(File(root, "wrapped-key"))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val key = keys.getKey(ALIAS, null) as? SecretKey
                ?: error("Tailscale wrapping key is unavailable; encrypted identity retained")
            val payload = AesGcmSecretCipher.decode(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                ?: error("Invalid encrypted Tailscale key envelope; identity retained")
            return AesGcmSecretCipher.decrypt(key, payload, aad).also {
                check(it.size == 32) { "Invalid decrypted Tailscale state key length" }
            }
        }
        check(File(root, "state").listFiles().orEmpty().none { it.extension == "state" }) {
            "Tailscale key envelope missing while encrypted state exists; identity retained"
        }
        val key = (keys.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = AesGcmSecretCipher.encode(AesGcmSecretCipher.encrypt(key, bytes, aad))
        val output = file.startWrite()
        try {
            output.write(encoded.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            bytes.fill(0)
            try { file.failWrite(output) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        return bytes
    }
}
