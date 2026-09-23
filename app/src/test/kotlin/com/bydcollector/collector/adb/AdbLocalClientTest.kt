package com.bydcollector.collector.adb

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbLocalClientTest {
    @Test
    fun rejectsNonLoopbackEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                endpoints = listOf(AdbEndpoint("192.168.1.10", 5555))
            )
        }
    }

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

    @Test
    fun cancellationClosesAnActiveAuthorizationSocket() {
        BlockingAdbServer().use { server ->
            val cancellation = AdbCancellation()
            val cancelled = AtomicBoolean(false)
            val finished = CountDownLatch(1)
            val client = AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port)),
                cancellation = cancellation
            )

            thread(name = "adb-cancellation-test") {
                try {
                    client.checkAuthorization()
                } catch (_: AdbOperationCancelledException) {
                    cancelled.set(true)
                } finally {
                    finished.countDown()
                }
            }

            assertTrue(server.awaitAccepted(), "client should connect before cancellation")
            cancellation.cancel()
            assertTrue(finished.await(2, TimeUnit.SECONDS), "cancelled ADB call should terminate promptly")
            assertTrue(cancelled.get())
        }
    }

    @Test
    fun shutdownShellHasABoundedWaitForAnOccupiedAuthorizationLock() {
        BlockingAdbServer().use { server ->
            val cancellation = AdbCancellation()
            val client = AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-shutdown").toFile(),
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port)),
                cancellation = cancellation
            )
            val busy = thread(name = "adb-busy-before-shutdown") {
                runCatching { client.checkAuthorization() }
            }
            try {
                assertTrue(server.awaitAccepted())
                val started = System.nanoTime()
                val result = client.execShell("must-not-execute", authLockTimeoutMs = 25)
                assertFalse(result.ok)
                assertTrue(result.error.orEmpty().contains("auth lock timeout"))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            } finally {
                cancellation.cancel()
                busy.join(2_000)
                assertFalse(busy.isAlive)
            }
        }
    }

    @Test
    fun invalidPersistentKeyIsNotAutomaticallyReplaced() {
        val keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile()
        val privateFile = keyDir.resolve("adb_key.priv")
        val invalidKey = byteArrayOf(1, 2, 3, 4)
        privateFile.writeBytes(invalidKey)

        val client = AdbLocalClient(keyDir = keyDir)

        assertFailsWith<IllegalStateException> { client.keyFingerprint() }
        assertTrue(privateFile.readBytes().contentEquals(invalidKey))
    }

    @Test
    fun readOnlyAuthorizationWithoutAnExistingKeyDoesNotCreateKeyStorage() {
        val root = Files.createTempDirectory("bydcollector-adb-read-only-test").toFile()
        val keyDir = root.resolve("not-created/adb_keys")
        val client = AdbLocalClient(keyDir = keyDir)

        val result = client.checkAuthorizationReadOnly()

        assertEquals("adb_authorization_required", result.category)
        assertFalse(keyDir.exists(), "observation must not create ADB key storage")
    }

    @Test
    fun readOnlyAuthorizationUsesExistingKeyWithoutPromptOrKeyFileWrites() {
        ReadOnlyAuthAdbServer().use { server ->
            val keyDir = Files.createTempDirectory("bydcollector-adb-read-only-test").toFile()
            val privateFile = keyDir.resolve("adb_key.priv")
            val publicFile = keyDir.resolve("adb_key.pub")
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateFile.writeBytes(keyPair.private.encoded)
            val originalPrivateBytes = privateFile.readBytes()
            val client = AdbLocalClient(
                keyDir = keyDir,
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port))
            )

            val result = client.checkAuthorizationReadOnly()

            assertEquals("adb_authorization_required", result.category)
            assertTrue(server.awaitFollowup(), "server should receive the existing-key signature challenge")
            assertEquals(0, server.followupPacketSize, "read-only checks must not send an RSA public-key prompt")
            assertTrue(privateFile.readBytes().contentEquals(originalPrivateBytes))
            assertFalse(publicFile.exists(), "deriving a missing public key must remain in memory only")
        }
    }

    @Test
    fun shellTimeoutPreservesPartialOutputAndElapsedTime() {
        PartialShellAdbServer().use { server ->
            val client = AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port))
            )

            val result = client.execShell("ignored", timeoutMs = 250)

            assertFalse(result.ok)
            assertEquals("partial output", result.output)
            assertEquals("shell command timed out", result.error)
            assertTrue(result.elapsedMs >= 100, "timeout elapsed time must not be reset to zero")
        }
    }

    @Test
    fun shellStreamUsesExactCommandAndClosesRemoteSocket() {
        StreamingAdbServer().use { server ->
            val client = AdbLocalClient(
                keyDir = Files.createTempDirectory("bydcollector-adb-test").toFile(),
                endpoints = listOf(AdbEndpoint("127.0.0.1", server.port))
            )
            val output = ByteArrayOutputStream()
            val stream = client.openShellStream("logcat -b all -v threadtime", output)

            assertTrue(server.awaitOpened())
            assertTrue(server.awaitAcknowledged())
            assertEquals("system log\n", output.toString(Charsets.UTF_8.name()))
            assertTrue(server.command.contains("shell:logcat -b all -v threadtime\u0000"))

            stream.close()
            assertTrue(server.awaitClosed())
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

    private class ReadOnlyAuthAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val followupRead = CountDownLatch(1)
        @Volatile
        var followupPacketSize: Int = -1
            private set
        val port: Int = server.localPort

        init {
            thread(name = "read-only-auth-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        readAdbPacket(input)
                        writeAdbPacket(output, COMMAND_AUTH, AUTH_TOKEN, 0, ByteArray(20) { 7 })
                        readAdbPacket(input) // Signature generated from the existing private key.
                        writeAdbPacket(output, COMMAND_AUTH, AUTH_TOKEN, 0, ByteArray(20) { 9 })
                        followupPacketSize = readAdbPacket(input).size
                        followupRead.countDown()
                    }
                }
            }
        }

        fun awaitFollowup(): Boolean = followupRead.await(3, TimeUnit.SECONDS)

        override fun close() {
            server.close()
        }
    }

    private class BlockingAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val accepted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val port: Int = server.localPort

        init {
            thread(name = "blocking-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        readAdbPacket(socket.getInputStream())
                        accepted.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

        fun awaitAccepted(): Boolean = accepted.await(3, TimeUnit.SECONDS)

        override fun close() {
            release.countDown()
            server.close()
        }
    }

    private class PartialShellAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        val port: Int = server.localPort

        init {
            thread(name = "partial-shell-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        readAdbPacket(input)
                        writeAdbPacket(output, COMMAND_CNXN, ADB_VERSION, ADB_MAX_DATA, "device::\u0000".toByteArray())
                        readAdbPacket(input)
                        writeAdbPacket(output, COMMAND_OKAY, 2, 1, ByteArray(0))
                        writeAdbPacket(output, COMMAND_WRTE, 2, 1, "partial output\n".toByteArray())
                        readAdbPacket(input)
                        release.await(3, TimeUnit.SECONDS)
                    }
                }
            }
        }

        override fun close() {
            release.countDown()
            server.close()
        }
    }

    private class StreamingAdbServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val opened = CountDownLatch(1)
        private val acknowledged = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        @Volatile
        var command: String = ""
            private set
        val port: Int = server.localPort

        init {
            thread(name = "streaming-adb-test-server", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        readAdbPacket(input)
                        writeAdbPacket(output, COMMAND_CNXN, ADB_VERSION, ADB_MAX_DATA, "device::\u0000".toByteArray())
                        command = String(readAdbPacket(input))
                        opened.countDown()
                        writeAdbPacket(output, COMMAND_OKAY, 2, 1, ByteArray(0))
                        writeAdbPacket(output, COMMAND_WRTE, 2, 1, "system log\n".toByteArray())
                        readAdbPacket(input)
                        acknowledged.countDown()
                        readAdbPacket(input)
                        closed.countDown()
                    }
                }
            }
        }

        fun awaitOpened(): Boolean = opened.await(3, TimeUnit.SECONDS)
        fun awaitAcknowledged(): Boolean = acknowledged.await(3, TimeUnit.SECONDS)
        fun awaitClosed(): Boolean = closed.await(3, TimeUnit.SECONDS)

        override fun close() {
            server.close()
        }
    }

    companion object {
        private const val COMMAND_CNXN = 0x4e584e43
        private const val COMMAND_AUTH = 0x48545541
        private const val COMMAND_OKAY = 0x59414b4f
        private const val COMMAND_WRTE = 0x45545257
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
