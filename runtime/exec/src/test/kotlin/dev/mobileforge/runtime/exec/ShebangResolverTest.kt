package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Shebang handling has to match the Linux kernel's, because under system-linker exec we are
 * standing in for it. Divergence would mean a script that works in a normal shell behaves
 * differently inside the IDE, which is the worst kind of bug to debug.
 */
class ShebangResolverTest {

    private fun header(text: String) = text.toByteArray()

    @Test
    fun `parses a bare interpreter`() {
        val shebang = ShebangResolver.parse(header("#!/usr/bin/php\n<?php\n"))!!
        assertThat(shebang.interpreter).isEqualTo("/usr/bin/php")
        assertThat(shebang.argument).isNull()
    }

    @Test
    fun `parses an interpreter with one argument`() {
        val shebang = ShebangResolver.parse(header("#!/usr/bin/env node\n"))!!
        assertThat(shebang.interpreter).isEqualTo("/usr/bin/env")
        assertThat(shebang.argument).isEqualTo("node")
    }

    @Test
    fun `keeps multi-word arguments as one entry like the kernel does`() {
        val shebang = ShebangResolver.parse(header("#!/usr/bin/env -S node --flag\n"))!!
        assertThat(shebang.interpreter).isEqualTo("/usr/bin/env")
        assertThat(shebang.argument).isEqualTo("-S node --flag")
        assertThat(shebang.argvPrefix()).containsExactly("/usr/bin/env", "-S node --flag").inOrder()
    }

    @Test
    fun `tolerates whitespace around the argument`() {
        val shebang = ShebangResolver.parse(header("#!/bin/sh   -e  \n"))!!
        assertThat(shebang.interpreter).isEqualTo("/bin/sh")
        assertThat(shebang.argument).isEqualTo("-e")
    }

    @Test
    fun `handles CRLF line endings`() {
        // A script authored on Windows must not carry a stray CR into the interpreter path.
        val shebang = ShebangResolver.parse(header("#!/bin/sh\r\necho hi\r\n"))!!
        assertThat(shebang.interpreter).isEqualTo("/bin/sh")
    }

    @Test
    fun `handles a file with no trailing newline`() {
        val shebang = ShebangResolver.parse(header("#!/bin/sh"))!!
        assertThat(shebang.interpreter).isEqualTo("/bin/sh")
    }

    @Test
    fun `returns null when there is no shebang`() {
        assertThat(ShebangResolver.parse(header("echo hi\n"))).isNull()
    }

    @Test
    fun `returns null for an empty file`() {
        assertThat(ShebangResolver.parse(ByteArray(0))).isNull()
    }

    @Test
    fun `returns null for a one-byte file`() {
        assertThat(ShebangResolver.parse(byteArrayOf('#'.code.toByte()))).isNull()
    }

    @Test
    fun `returns null for a bare hash with no bang`() {
        assertThat(ShebangResolver.parse(header("# not a shebang\n"))).isNull()
    }

    @Test
    fun `returns null when the shebang line is empty`() {
        assertThat(ShebangResolver.parse(header("#!\n"))).isNull()
    }

    @Test
    fun `truncates at the kernel's 127-byte limit`() {
        val longPath = "/" + "a".repeat(400)
        val shebang = ShebangResolver.parse(header("#!$longPath\n"))!!
        // Matches the kernel rather than being more generous than it.
        assertThat(shebang.interpreter.length).isAtMost(ShebangResolver.MAX_SHEBANG_BYTES)
    }

    // ---------- ELF detection ----------

    private fun elf(eType: Int) = ByteArray(32).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte()
        it[16] = eType.toByte()
    }

    @Test
    fun `recognises an ELF header`() {
        assertThat(ShebangResolver.isElf(elf(3))).isTrue()
    }

    @Test
    fun `does not mistake a script for ELF`() {
        assertThat(ShebangResolver.isElf(header("#!/bin/sh\n"))).isFalse()
    }

    @Test
    fun `identifies a PIE executable as dynamically linked`() {
        // ET_DYN (3) is what the NDK produces and what the system linker can load.
        assertThat(ShebangResolver.isDynamicElf(elf(3))).isTrue()
    }

    @Test
    fun `identifies ET_EXEC as not linker-loadable`() {
        assertThat(ShebangResolver.isDynamicElf(elf(2))).isFalse()
    }

    @Test
    fun `a truncated ELF header is not treated as dynamic`() {
        assertThat(ShebangResolver.isDynamicElf(byteArrayOf(0x7F, 'E'.code.toByte()))).isFalse()
    }
}

class BoundedOutputBufferTest {

    private fun line(n: Int) = dev.mobileforge.runtime.api.ProcessOutput(
        stream = dev.mobileforge.runtime.api.OutputStream.Stdout,
        line = "line $n",
        timestampEpochMs = n.toLong(),
    )

    @Test
    fun `retains output below capacity`() {
        val buffer = BoundedOutputBuffer(capacity = 10)
        repeat(5) { buffer.add(line(it)) }

        assertThat(buffer.size).isEqualTo(5)
        assertThat(buffer.droppedCount).isEqualTo(0)
    }

    @Test
    fun `evicts oldest lines beyond capacity`() {
        // A build printing a million lines must not become a million retained strings.
        val buffer = BoundedOutputBuffer(capacity = 3)
        repeat(10) { buffer.add(line(it)) }

        assertThat(buffer.size).isEqualTo(3)
        assertThat(buffer.snapshot().map { it.line })
            .containsExactly("line 7", "line 8", "line 9").inOrder()
    }

    @Test
    fun `reports how many lines were dropped`() {
        val buffer = BoundedOutputBuffer(capacity = 3)
        repeat(10) { buffer.add(line(it)) }

        assertThat(buffer.totalProduced).isEqualTo(10)
        assertThat(buffer.droppedCount).isEqualTo(7)
    }

    @Test
    fun `truncates an over-long single line`() {
        // One minified bundle line can be megabytes; a line-count cap alone does not help.
        val buffer = BoundedOutputBuffer(capacity = 10, maxLineLength = 20)
        buffer.add(
            dev.mobileforge.runtime.api.ProcessOutput(
                stream = dev.mobileforge.runtime.api.OutputStream.Stdout,
                line = "x".repeat(5_000),
                timestampEpochMs = 0,
            ),
        )

        val stored = buffer.snapshot().single().line
        assertThat(stored).hasLength(20 + BoundedOutputBuffer.TRUNCATION_MARKER.length)
        assertThat(stored).endsWith(BoundedOutputBuffer.TRUNCATION_MARKER)
    }

    @Test
    fun `snapshot is a copy`() {
        val buffer = BoundedOutputBuffer(capacity = 5)
        buffer.add(line(1))
        val snapshot = buffer.snapshot()
        buffer.add(line(2))

        assertThat(snapshot).hasSize(1)
    }

    @Test
    fun `clear resets counters`() {
        val buffer = BoundedOutputBuffer(capacity = 2)
        repeat(10) { buffer.add(line(it)) }
        buffer.clear()

        assertThat(buffer.size).isEqualTo(0)
        assertThat(buffer.droppedCount).isEqualTo(0)
    }

    @Test
    fun `rejects a non-positive capacity`() {
        assertThat(runCatching { BoundedOutputBuffer(capacity = 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
