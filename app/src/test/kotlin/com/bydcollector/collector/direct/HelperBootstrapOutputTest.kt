package com.bydcollector.collector.direct

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperBootstrapOutputTest {
    @Test
    fun outputUsesOwnedBoundedChunksAndDoesNotWaitForDisk() {
        val chunks = mutableListOf<ByteArray>()
        val output = HelperBootstrapOutput.QueuedOutput { chunks += it }
        val input = ByteArray(HelperBootstrapOutput.MAX_CHUNK_BYTES * 2 + 13) { (it % 251).toByte() }
        val expected = input.copyOf()

        output.write(input, 0, input.size)
        input.fill(0)

        assertEquals(listOf(4096, 4096, 13), chunks.map { it.size })
        assertContentEquals(expected, chunks.flatMap { it.asIterable() }.toByteArray())
    }

    @Test
    fun refusedOutputNeverEscapesIntoHelperControlFlow() {
        var calls = 0
        val output = HelperBootstrapOutput.QueuedOutput {
            calls++
            throw IllegalStateException("diagnostic unavailable")
        }

        output.write(ByteArray(5000))
        output.write(10)
        output.flush()
        output.close()

        assertEquals(3, calls)
    }

    @Test
    fun zeroLengthWriteDoesNotQueueAnythingAndOffsetsArePreserved() {
        val chunks = mutableListOf<ByteArray>()
        val output = HelperBootstrapOutput.QueuedOutput { chunks += it }
        output.write(byteArrayOf(1, 2, 3, 4), 1, 2)
        output.write(byteArrayOf(5), 0, 0)
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(2, 3), chunks.single())
    }

    @Test
    fun nativeAndJavaOutputShareTheBoundedSinkWithoutAnotherProcess() {
        val source = source("HelperBootstrapOutput.java")
        assertTrue(source.contains("System.setOut(output)"))
        assertTrue(source.contains("System.setErr(output)"))
        assertTrue(source.contains("Os.dup2(pipe[1], 1)"))
        assertTrue(source.contains("Os.dup2(pipe[1], 2)"))
        assertTrue(source.contains("reader.setDaemon(true)"))
        assertTrue(source.contains("diagnostics.enqueueBootstrap(Arrays.copyOf(buffer, count))"))
        assertTrue(source.contains("muteNativeOutput();"))
        assertFalse(source.contains("ProcessBuilder"))
        assertFalse(source.contains("FileOutputStream"))
        assertFalse(source.contains("Thread.sleep"))
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/java/com/bydcollector/collector/direct/$name"),
        File("app/src/main/java/com/bydcollector/collector/direct/$name")
    ).first { it.isFile }.readText()
}
