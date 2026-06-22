package com.bydcollector.collector.adb

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbLocalClientTest {
    @Test
    fun acceptsBydReferenceAdbFramesWithoutStrictChecksumFallback() {
        BrokenAdbServer().use { broken ->
            ConnectedAdbServer().use { connected ->
                val client = AdbLocalClient(
                    keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                    endpoints = listOf(
                        AdbEndpoint("127.0.0.1", broken.port),
                        AdbEndpoint("127.0.0.1", connected.port)
                    )
                )

                val result = client.checkAuthorization()

                assertEquals("adb_authorization_connected", result.category)
                assertTrue(broken.awaitAccepted(), "first endpoint should have been tried")
                assertFalse(connected.awaitAccepted(), "collector must not hide 5555 framing behind a fallback port")
            }
        }
    }

    @Test
    fun requestAuthorizationSendsBydReferencePublicKeyComment() {
        AuthPromptAdbServer().use { server ->
            val events = mutableListOf<String>()
            val client = AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port)),
                eventSink = { category, message, detail -> events += "$category|$message|$detail" }
            )

            val result = client.requestAuthorization()

            assertEquals("adb_authorization_connected", result.category, events.joinToString("\n"))
            assertTrue(server.awaitPublicKey(), "public key should have been sent")
            assertTrue(
                server.publicKeyPayload.endsWith(" bydcollector@collector\u0000"),
                "ADB public key should use collector's own key comment"
            )
        }
    }

    private class BrokenAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val accepted = CountDownLatch(1)
        val port: Int = server.localPort

        init {
            thread(name = "broken-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        accepted.countDown()
                        readAdbPacket(socket.getInputStream())
                        socket.getOutputStream().write(adbPacket(COMMAND_CNXN, 0, 0, byteArrayOf(1), checksum = 999))
                        socket.getOutputStream().flush()
                    }
                }
            }
        }

        fun awaitAccepted(): Boolean = accepted.await(3, TimeUnit.SECONDS)

        override fun close() {
            server.close()
        }
    }

    private class ConnectedAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val accepted = CountDownLatch(1)
        val port: Int = server.localPort

        init {
            thread(name = "connected-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        accepted.countDown()
                        readAdbPacket(socket.getInputStream())
                        writeAdbPacket(socket.getOutputStream(), COMMAND_CNXN, ADB_VERSION, ADB_MAX_DATA, "device::\u0000".toByteArray())
                    }
                }
            }
        }

        fun awaitAccepted(): Boolean = accepted.await(3, TimeUnit.SECONDS)

        override fun close() {
            server.close()
        }
    }

    private class AuthPromptAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val publicKeySent = CountDownLatch(1)
        @Volatile
        var publicKeyPayload: String = ""
            private set
        val port: Int = server.localPort

        init {
            thread(name = "auth-prompt-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        readAdbPacket(socket.getInputStream())
                        writeAdbPacket(socket.getOutputStream(), COMMAND_AUTH, AUTH_TOKEN, 0, ByteArray(20) { 7 })
                        readAdbPacket(socket.getInputStream())
                        writeAdbPacket(socket.getOutputStream(), COMMAND_AUTH, AUTH_TOKEN, 0, ByteArray(20) { 9 })
                        publicKeyPayload = String(readAdbPacket(socket.getInputStream()))
                        publicKeySent.countDown()
                        writeAdbPacket(socket.getOutputStream(), COMMAND_CNXN, ADB_VERSION, ADB_MAX_DATA, "device::\u0000".toByteArray())
                    }
                }
            }
        }

        fun awaitPublicKey(): Boolean = publicKeySent.await(3, TimeUnit.SECONDS)

        override fun close() {
            server.close()
        }
    }

    companion object {
        private const val COMMAND_CNXN = 0x4e584e43
        private const val COMMAND_AUTH = 0x48545541
        private const val AUTH_TOKEN = 1
        private const val ADB_VERSION = 0x01000001
        private const val ADB_MAX_DATA = 262_144

        private fun writeAdbPacket(
            output: OutputStream,
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray
        ) {
            output.write(adbPacket(command, arg0, arg1, payload))
            output.flush()
        }

        private fun adbPacket(
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray,
            checksum: Int = payload.sumOf { it.toInt() and 0xff }
        ): ByteArray {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(command)
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(payload.size)
            header.putInt(checksum)
            header.putInt(command.inv())
            return header.array() + payload
        }

        private fun readAdbPacket(input: InputStream): ByteArray {
            val header = input.readNBytes(24)
            if (header.size < 24) return ByteArray(0)
            val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            return if (length > 0) input.readNBytes(length) else ByteArray(0)
        }
    }
}
