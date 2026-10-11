package com.bydcollector.collector.adb

import android.Manifest
import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Replaces only socket acquisition; production key, AUTH, grants and permission readback run. */
@Implements(AdbLocalClient::class, isInAndroidSdk = false)
class StartupAdbPeer {
    @Implementation
    fun openLocalAdbSocket(endpoint: AdbEndpoint): Socket = Peer()

    companion object {
        @Volatile var consent = false
        @Volatile var trusted = false
        val events = CopyOnWriteArrayList<String>()
        val publicKeys = CopyOnWriteArrayList<String>()
        fun reset() { consent = false; trusted = false; events.clear(); publicKeys.clear() }
    }

    private class Peer : Socket() {
        val bytes = ArrayDeque<Int>()
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int = if (bytes.isEmpty()) -1 else bytes.removeFirst()
        }
        override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                val buffer = ByteBuffer.wrap(toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                reset()
                val command = buffer.int
                val arg0 = buffer.int
                val arg1 = buffer.int
                val length = buffer.int
                buffer.int; buffer.int
                val payload = ByteArray(length).also(buffer::get)
                receive(command, arg0, arg1, payload)
            }
        }
        fun receive(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            when (command) {
                0x4e584e43 -> send(0x48545541, 1, 0, ByteArray(20) { 42 }) // CNXN → AUTH token
                0x48545541 -> when (arg0) {
                    2 -> {
                        check(payload.size == 256) { "RSA signature was not sent" }
                        events += "signature"
                        if (trusted) send(0x4e584e43, 0x01000000, 4096, "device::\u0000".toByteArray())
                        else send(0x48545541, 1, 0, ByteArray(20) { 42 })
                    }
                    3 -> {
                        publicKeys += payload.toString(Charsets.UTF_8)
                        events += "public-key"
                        if (consent) {
                            trusted = true
                            events += "accepted"
                            send(0x4e584e43, 0x01000000, 4096, "device::\u0000".toByteArray())
                        } else events += "denied"
                    }
                }
                0x4e45504f -> { // OPEN shell
                    check(trusted) { "shell before AUTH" }
                    val commandText = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                    events += commandText
                    val app = RuntimeEnvironment.getApplication()
                    if (commandText.contains("pm grant") && commandText.contains("READ_EXTERNAL_STORAGE")) {
                        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    if (commandText.contains("allow_listener")) {
                            val component = commandText.substringAfter("allow_listener ").substringBefore(')').substringBefore(';').trim()
                            val previous = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
                            Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
                                (previous.split(':').filter(String::isNotBlank) + component).distinct().joinToString(":"))
                    }
                    send(0x59414b4f, 2, arg0, byteArrayOf())
                    send(0x45545257, 2, arg0, "\n__BYDCOLLECTOR_EXIT_0__\n".toByteArray())
                    send(0x45534c43, 2, arg0, byteArrayOf())
                }
            }
        }
        fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            val frame = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(payload).array()
            frame.forEach { bytes.addLast(it.toInt() and 255) }
        }
    }
}
