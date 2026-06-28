package com.bydcollector.collector.adb

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

//implements the small local adb client needed on dilink without depending on an external adb server
class AdbLocalClient(
    private val keyDir: File,
    private val eventSink: ((category: String, message: String, detail: String?) -> Unit)? = null,
    private val endpoints: List<AdbEndpoint> = LOCAL_ADB_ENDPOINTS
) {
    init {
        require(endpoints.all { it.isLoopbackHost() }) {
            "AdbLocalClient endpoints must use loopback hosts only"
        }
    }

    fun execShell(
        command: String,
        timeoutMs: Int = SHELL_TIMEOUT_MS,
        allowAuthorizationPrompt: Boolean = false
    ): AdbShellResult {
        //keeps shell calls serialized with auth so one adb socket owns the rsa prompt/handshake sequence
        if (!AUTH_LOCK.tryLock(AUTH_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return AdbShellResult(
                ok = false,
                output = "",
                error = "adb_authorization_unavailable: ADB auth lock timeout",
                elapsedMs = 0
            )
        }
        return try {
            var lastResult: AdbShellResult? = null
            for (endpoint in endpoints) {
                val result = execShell(endpoint, command, timeoutMs, allowAuthorizationPrompt)
                if (!shouldTryNextEndpoint(result.error) || endpoint == endpoints.last()) return result
                lastResult = result
            }
            lastResult ?: AdbShellResult(
                ok = false,
                output = "",
                error = "adb_authorization_unavailable: Local ADB daemon is not reachable",
                elapsedMs = 0
            )
        } finally {
            AUTH_LOCK.unlock()
        }
    }

    private fun execShell(
        endpoint: AdbEndpoint,
        command: String,
        timeoutMs: Int,
        allowAuthorizationPrompt: Boolean
    ): AdbShellResult {
        return try {
            openLocalAdbSocket(endpoint).use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val authResult = connectAuthorized(socket, input, output, allowAuthorizationPrompt)
                if (authResult.category != "adb_authorization_connected") {
                    return AdbShellResult(
                        ok = false,
                        output = "",
                        error = "${authResult.category}: ${authResult.message}",
                        elapsedMs = 0
                    )
                }
                runShell(socket, input, output, command, timeoutMs)
            }
        } catch (error: Exception) {
            AdbShellResult(
                ok = false,
                output = "",
                error = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                elapsedMs = 0
            )
        }
    }

    fun checkAuthorization(): AdbAuthorizationResult {
        return authorizeAcrossEndpoints(
            allowAuthorizationPrompt = false,
            timeoutMessage = "ADB authorization check timed out"
        )
    }

    fun requestAuthorization(): AdbAuthorizationResult {
        return authorizeAcrossEndpoints(
            allowAuthorizationPrompt = true,
            timeoutMessage = "ADB authorization timed out; accept RSA, then tap Grant ADB"
        )
    }

    private fun authorizeAcrossEndpoints(
        allowAuthorizationPrompt: Boolean,
        timeoutMessage: String
    ): AdbAuthorizationResult {
        if (!AUTH_LOCK.tryLock(AUTH_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return AdbAuthorizationResult(
                category = "adb_authorization_unavailable",
                message = "ADB auth lock timeout",
                detail = "another local ADB operation is still running"
            )
        }
        return try {
            var lastResult: AdbAuthorizationResult? = null
            for (endpoint in endpoints) {
                val result = checkAuthorization(endpoint, allowAuthorizationPrompt, timeoutMessage)
                if (!shouldTryNextEndpoint(result.category) || endpoint == endpoints.last()) return result
                lastResult = result
            }
            lastResult ?: AdbAuthorizationResult(
                category = "adb_authorization_unavailable",
                message = "Local ADB daemon is not reachable",
                detail = "no endpoint attempted"
            )
        } finally {
            AUTH_LOCK.unlock()
        }
    }

    private fun checkAuthorization(
        endpoint: AdbEndpoint,
        allowAuthorizationPrompt: Boolean,
        timeoutMessage: String
    ): AdbAuthorizationResult {
        return try {
            openLocalAdbSocket(endpoint).use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                connectAuthorized(socket, input, output, allowAuthorizationPrompt)
            }
        } catch (error: ConnectException) {
            AdbAuthorizationResult(
                category = "adb_authorization_unavailable",
                message = "Local ADB daemon is not reachable",
                detail = "target=${endpoint.host}:${endpoint.port} ${error.message ?: "connect failed"}"
            )
        } catch (error: SocketTimeoutException) {
            AdbAuthorizationResult(
                category = "adb_authorization_timeout",
                message = timeoutMessage,
                detail = "target=${endpoint.host}:${endpoint.port} ${error.message ?: "timeout"}"
            )
        } catch (error: Exception) {
            AdbAuthorizationResult(
                category = "adb_authorization_error",
                message = "ADB authorization check failed",
                detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    fun keyFingerprint(): String {
        val publicKey = loadOrCreateKeyPair().public.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun openLocalAdbSocket(endpoint: AdbEndpoint): Socket {
        val socket = Socket()
        try {
            //normalizes local adb endpoints to loopback because the app must never shell out to remote hosts
            val socketAddress = when (endpoint.port) {
                5555 -> InetSocketAddress("127.0.0.1", 5555)
                1439 -> InetSocketAddress("127.0.0.1", 1439)
                else -> InetSocketAddress(endpoint.host, endpoint.port)
            }
            socket.connect(socketAddress, ADB_CONNECT_TIMEOUT_MS)
            socket.soTimeout = DEFAULT_TIMEOUT_MS
            socket.tcpNoDelay = true
            eventSink?.invoke(
                "adb_socket_connected",
                "Connected to local ADB daemon",
                "target=${endpoint.host}:${endpoint.port} connect_timeout_ms=$ADB_CONNECT_TIMEOUT_MS"
            )
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            eventSink?.invoke(
                "adb_socket_unavailable",
                "Local ADB endpoint is not reachable",
                "target=${endpoint.host}:${endpoint.port} ${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            throw error
        }
    }

    private fun shouldTryNextEndpoint(categoryOrError: String?): Boolean {
        val value = categoryOrError ?: return false
        return value.startsWith("adb_authorization_unavailable") ||
            value.startsWith("ConnectException:")
    }

    private fun connectAuthorized(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        allowAuthorizationPrompt: Boolean
    ): AdbAuthorizationResult {
        val keyPair = loadOrCreateKeyPair()
        writePacket(output, COMMAND_CNXN, ADB_VERSION, ADB_MAX_DATA, ADB_BANNER.toByteArray())
        eventSink?.invoke(
            "adb_cnxn_sent",
            "ADB CNXN sent",
            "banner=${ADB_BANNER.trimEnd('\u0000')} allow_prompt=$allowAuthorizationPrompt"
        )

        var signatureSent = false
        var publicKeySent = false
        //tries signature auth first so already-approved installs avoid showing the rsa prompt again
        while (true) {
            val response = try {
                readPacket(input)
            } catch (error: SocketTimeoutException) {
                if (publicKeySent) {
                    return AdbAuthorizationResult(
                        category = "adb_authorization_timeout",
                        message = "ADB RSA prompt timed out; accept RSA, then tap Grant ADB",
                        detail = "prompt_sent=true ${error.message ?: "timeout"}"
                    )
                }
                throw error
            }
            eventSink?.invoke(
                "adb_handshake_response",
                "ADB handshake response received",
                response.summary()
            )
            if (signatureSent && !publicKeySent) {
                eventSink?.invoke(
                    "adb_signature_response",
                    "ADB signature response received",
                    response.summary()
                )
            }

            if (response.command == COMMAND_CNXN) {
                val authMode = when {
                    publicKeySent -> "public_key"
                    signatureSent -> "signature"
                    else -> "not_required"
                }
                return AdbAuthorizationResult(
                    category = "adb_authorization_connected",
                    message = "ADB authorized",
                    detail = "auth=$authMode"
                )
            }

            if (response.command != COMMAND_AUTH || response.arg0 != AUTH_TOKEN) {
                return AdbAuthorizationResult(
                    category = "adb_authorization_error",
                    message = "ADB returned an unexpected handshake response",
                    detail = response.summary()
                )
            }

            if (!signatureSent) {
                writePacket(output, COMMAND_AUTH, AUTH_SIGNATURE, 0, signToken(keyPair.private, response.payload))
                signatureSent = true
                eventSink?.invoke(
                    "adb_signature_sent",
                    "ADB signature sent",
                    "token_bytes=${response.payload.size}"
                )
                continue
            }

            if (!publicKeySent) {
                if (!allowAuthorizationPrompt) {
                    return AdbAuthorizationResult(
                        category = "adb_authorization_required",
                        message = "ADB key is not authorized; request authorization from the app UI first",
                        detail = response.summary()
                    )
                }
                val publicKeyPayload = androidAdbPublicKey(keyPair.public as RSAPublicKey)
                writePacket(output, COMMAND_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload)
                publicKeySent = true
                eventSink?.invoke(
                    "adb_authorization_prompt_sent",
                    "ADB public key sent; accept RSA, then tap Grant ADB if it times out",
                    "key_comment=bydcollector@collector"
                )
                socket.soTimeout = AUTH_APPROVAL_TIMEOUT_MS
                continue
            }

            return AdbAuthorizationResult(
                category = "adb_authorization_required",
                message = "ADB key is not authorized after RSA public key prompt",
                detail = response.summary()
            )
        }
    }

    private fun runShell(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        command: String,
        timeoutMs: Int
    ): AdbShellResult {
        val startedAt = System.currentTimeMillis()
        val localId = 1
        //adds an explicit marker because adb shell streams do not otherwise expose the remote exit code
        val service = "shell:($command); echo __BYDCOLLECTOR_EXIT_${'$'}?__\u0000"
        writePacket(output, COMMAND_OPEN, localId, 0, service.toByteArray())

        var remoteId = 0
        val outputBuilder = StringBuilder()
        val deadline = startedAt + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            socket.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(100, timeoutMs.toLong()).toInt()
            val packet = readPacket(input)
            when (packet.command) {
                COMMAND_OKAY -> {
                    if (packet.arg1 == localId) remoteId = packet.arg0
                }
                COMMAND_WRTE -> {
                    if (packet.arg0 == remoteId && packet.arg1 == localId) {
                        outputBuilder.append(String(packet.payload))
                        writePacket(output, COMMAND_OKAY, localId, remoteId, ByteArray(0))
                    }
                }
                COMMAND_CLSE -> {
                    if (remoteId != 0 && packet.arg0 == remoteId) {
                        writePacket(output, COMMAND_CLSE, localId, remoteId, ByteArray(0))
                        return shellResult(outputBuilder.toString(), startedAt, timedOut = false)
                    }
                }
            }
        }

        if (remoteId != 0) {
            runCatching { writePacket(output, COMMAND_CLSE, localId, remoteId, ByteArray(0)) }
        }
        return shellResult(outputBuilder.toString(), startedAt, timedOut = true)
    }

    private fun shellResult(output: String, startedAt: Long, timedOut: Boolean): AdbShellResult {
        val marker = "__BYDCOLLECTOR_EXIT_"
        val markerStart = output.lastIndexOf(marker)
        val markerEnd = if (markerStart >= 0) output.indexOf("__", markerStart + marker.length) else -1
        val exitCode = if (markerStart >= 0 && markerEnd > markerStart) {
            output.substring(markerStart + marker.length, markerEnd).trim().toIntOrNull()
        } else {
            null
        }
        val cleanOutput = if (markerStart >= 0 && markerEnd > markerStart) {
            output.removeRange(markerStart, (markerEnd + 2).coerceAtMost(output.length)).trim()
        } else {
            output.trim()
        }
        return AdbShellResult(
            ok = !timedOut && exitCode == 0,
            output = cleanOutput,
            error = when {
                timedOut -> "shell command timed out"
                exitCode == null -> "shell exit marker missing"
                exitCode != 0 -> "shell exit code $exitCode"
                else -> null
            },
            elapsedMs = System.currentTimeMillis() - startedAt
        )
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val privateFile = File(keyDir, "adb_key.priv")
        val publicFile = File(keyDir, "adb_key.pub")
        keyDir.mkdirs()
        //keeps the same adb identity across app updates so the user grant remains stable
        migrateLegacyAdbKeyIfPresent(privateFile, publicFile)
        if (privateFile.exists()) {
            runCatching {
                val keyFactory = KeyFactory.getInstance("RSA")
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
                val publicKey = if (publicFile.exists()) {
                    keyFactory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                } else {
                    derivePublicKey(keyFactory, privateKey).also { publicFile.writeBytes(it.encoded) }
                }
                return KeyPair(publicKey, privateKey)
            }
        }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair().also { keyPair ->
            privateFile.writeBytes(keyPair.private.encoded)
            publicFile.writeBytes(keyPair.public.encoded)
        }
    }

    private fun migrateLegacyAdbKeyIfPresent(privateFile: File, publicFile: File) {
        if (privateFile.exists()) return
        val legacyPrivateFile = File(keyDir.parentFile ?: return, "bydhud_adb_private.pk8")
        if (!legacyPrivateFile.exists()) return
        legacyPrivateFile.copyTo(privateFile, overwrite = false)
        if (publicFile.exists()) publicFile.delete()
        eventSink?.invoke(
            "adb_key_migrated",
            "Migrated legacy ADB private key",
            "source=bydhud_adb_private.pk8 target=${privateFile.absolutePath}"
        )
    }

    private fun derivePublicKey(keyFactory: KeyFactory, privateKey: PrivateKey): PublicKey {
        val rsaPrivateKey = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException("ADB private key does not include RSA CRT public parameters")
        return keyFactory.generatePublic(
            RSAPublicKeySpec(rsaPrivateKey.modulus, rsaPrivateKey.publicExponent)
        )
    }

    private fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        //matches android adb's sha1 digestinfo signing format instead of a generic rsa signature wrapper
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(privateKey)
        signature.update(digestInfo)
        return signature.sign()
    }

    private fun androidAdbPublicKey(publicKey: RSAPublicKey): ByteArray {
        //serializes rsa public key in android adb's little-endian wire format for AUTH_RSAPUBLICKEY
        val wordCount = 64
        val radix = BigInteger.ONE.shiftLeft(32)
        val mask = radix.subtract(BigInteger.ONE)
        var modulus = publicKey.modulus
        var rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val n0inv = modulus.and(mask).modInverse(radix).negate().mod(radix)
        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(wordCount)
        buffer.putInt(n0inv.toInt())
        repeat(wordCount) {
            buffer.putInt(modulus.and(mask).toInt())
            modulus = modulus.shiftRight(32)
        }
        repeat(wordCount) {
            buffer.putInt(rr.and(mask).toInt())
            rr = rr.shiftRight(32)
        }
        buffer.putInt(publicKey.publicExponent.toInt())

        val keyBody = Base64.getEncoder().encodeToString(buffer.array())
        return "$keyBody bydcollector@collector\u0000".toByteArray()
    }

    private fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(payload.sumOf { it.toInt() and 0xff })
        header.putInt(command.inv())
        output.write(header.array())
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun readPacket(input: InputStream): AdbPacket {
        val header = readFully(input, 24)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val payloadLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (payloadLength !in 0..MAX_PAYLOAD) {
            throw IllegalStateException("ADB packet payload length out of range: $payloadLength")
        }
        val payload = if (payloadLength > 0) readFully(input, payloadLength) else ByteArray(0)
        if (payload.sumOf { it.toInt() and 0xff } != checksum) {
            eventSink?.invoke(
                "adb_packet_checksum_mismatch",
                "ADB packet checksum mismatch ignored",
                "command=0x${Integer.toHexString(command)} payload=$payloadLength"
            )
        }
        if (magic != command.inv()) {
            eventSink?.invoke(
                "adb_packet_magic_mismatch",
                "ADB packet magic mismatch ignored",
                "command=0x${Integer.toHexString(command)} payload=$payloadLength"
            )
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("EOF after $offset of $length bytes")
            offset += count
        }
        return bytes
    }

    private fun AdbPacket.summary(): String {
        return "command=0x${Integer.toHexString(command)} arg0=$arg0 arg1=$arg1 payload=${payload.size}"
    }

    private fun AdbEndpoint.isLoopbackHost(): Boolean {
        val value = host.trim().lowercase()
        return value == "localhost" ||
            value == "::1" ||
            value == "[::1]" ||
            value == "0:0:0:0:0:0:0:1" ||
            LOOPBACK_IPV4.matches(value)
    }

    companion object {
        const val COMMAND_CNXN = 0x4e584e43
        const val COMMAND_AUTH = 0x48545541
        const val COMMAND_OPEN = 0x4e45504f
        const val COMMAND_OKAY = 0x59414b4f
        const val COMMAND_CLSE = 0x45534c43
        const val COMMAND_WRTE = 0x45545257
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        private const val ADB_VERSION = 0x01000001
        private const val ADB_MAX_DATA = 262_144
        private const val MAX_PAYLOAD = 1_048_576
        private const val ADB_CONNECT_TIMEOUT_MS = 3_000
        private const val ADB_BANNER = "host::\u0000"
        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val AUTH_APPROVAL_TIMEOUT_MS = 60_000
        private const val AUTH_LOCK_TIMEOUT_MS = 70_000L
        private const val SHELL_TIMEOUT_MS = 15_000
        private val AUTH_LOCK = ReentrantLock()
        private val LOCAL_ADB_ENDPOINTS = listOf(
            AdbEndpoint("127.0.0.1", 5555)
        )
        private val LOOPBACK_IPV4 = Regex("""127(?:\.\d{1,3}){3}""")
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }
}

data class AdbEndpoint(
    val host: String,
    val port: Int
)

data class AdbAuthorizationResult(
    val category: String,
    val message: String,
    val detail: String?
)

data class AdbShellResult(
    val ok: Boolean,
    val output: String,
    val error: String?,
    val elapsedMs: Long
)

private data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray
)
