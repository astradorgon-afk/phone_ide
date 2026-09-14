package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppResult
import org.junit.Test

/**
 * End-to-end argv construction.
 *
 * These assert the exact command line handed to the OS, because that string is the entire
 * Android workaround. If the linker prefix or the shebang expansion is wrong, every development
 * tool fails at once — and with an error message that points nowhere near the real cause.
 */
class ExecCommandBuilderTest {

    private val env = ExecEnvironment(
        deviceSdkInt = 34,
        appTargetSdk = 35,
        primaryAbi = "arm64-v8a",
        filesDir = "/data/data/dev.mobileforge/files",
        nativeLibraryDir = "/data/app/dev.mobileforge/lib/arm64",
    )

    /** In-memory filesystem of headers, so no temp files are needed. */
    private class FakeHeaders(private val files: Map<String, ByteArray>) : FileHeaderReader {
        override fun readHeader(absolutePath: String, maxBytes: Int): ByteArray? =
            files[absolutePath]?.copyOf(minOf(maxBytes, files.getValue(absolutePath).size))
    }

    private fun builder(files: Map<String, ByteArray>) =
        ExecCommandBuilder(ExecStrategySelector(env), FakeHeaders(files))

    private fun dynamicElf(): ByteArray = ByteArray(64).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte()
        it[16] = 3 // ET_DYN
    }

    private fun staticElf(): ByteArray = ByteArray(64).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte()
        it[16] = 2 // ET_EXEC
    }

    private fun script(line: String): ByteArray = "$line\necho hi\n".toByteArray()

    // ---------- binaries ----------

    @Test
    fun `a dynamic binary in app storage is prefixed with the system linker`() {
        val php = "/data/data/dev.mobileforge/files/usr/bin/php"
        val result = builder(mapOf(php to dynamicElf())).build(php, listOf("-v"))

        val command = (result as AppResult.Success).value
        assertThat(command.argv).containsExactly("/system/bin/linker64", php, "-v").inOrder()
        assertThat(command.realProgramPath).isEqualTo(php)
    }

    @Test
    fun `a system binary is invoked directly with no linker prefix`() {
        val sh = "/system/bin/sh"
        val result = builder(mapOf(sh to dynamicElf())).build(sh, listOf("-c", "echo hi"))

        val command = (result as AppResult.Success).value
        assertThat(command.argv).containsExactly(sh, "-c", "echo hi").inOrder()
        assertThat(command.strategy).isEqualTo(ExecStrategy.Direct)
    }

    @Test
    fun `a statically linked binary is refused with a specific reason`() {
        val tool = "/data/data/dev.mobileforge/files/usr/bin/statictool"
        val result = builder(mapOf(tool to staticElf())).build(tool, emptyList())

        val error = (result as AppResult.Failure).error
        assertThat(error.detail).contains("statically linked")
        assertThat(error.recovery).contains("dynamically linked")
    }

    @Test
    fun `a static binary in the native library directory is still allowed`() {
        // It runs directly there, so the linker limitation does not apply.
        val lib = "/data/app/dev.mobileforge/lib/arm64/libtool.so"
        val result = builder(mapOf(lib to staticElf())).build(lib, emptyList())

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `a missing program reports not installed`() {
        val result = builder(emptyMap()).build("/data/data/x/files/usr/bin/php", emptyList())

        val error = (result as AppResult.Failure).error
        assertThat(error.message).contains("not installed")
    }

    // ---------- scripts ----------

    @Test
    fun `a script is expanded to interpreter plus script plus user args`() {
        // The kernel would do this; under linker exec it never sees the script, so we must.
        val php = "/data/data/dev.mobileforge/files/usr/bin/php"
        val artisan = "/data/data/dev.mobileforge/files/home/app/artisan"

        val result = builder(
            mapOf(artisan to script("#!$php"), php to dynamicElf()),
        ).build(artisan, listOf("migrate", "--seed"))

        val command = (result as AppResult.Success).value
        assertThat(command.argv).containsExactly(
            "/system/bin/linker64", php, artisan, "migrate", "--seed",
        ).inOrder()
        // The linker runs the INTERPRETER, so that is the real program path.
        assertThat(command.realProgramPath).isEqualTo(php)
    }

    @Test
    fun `a shebang argument is preserved before the script path`() {
        val envBin = "/data/data/dev.mobileforge/files/usr/bin/env"
        val runner = "/data/data/dev.mobileforge/files/home/app/run"

        val result = builder(
            mapOf(runner to script("#!$envBin node"), envBin to dynamicElf()),
        ).build(runner, listOf("build"))

        val command = (result as AppResult.Success).value
        assertThat(command.argv).containsExactly(
            "/system/bin/linker64", envBin, "node", runner, "build",
        ).inOrder()
    }

    @Test
    fun `a multi-word shebang argument stays a single argv entry`() {
        // The kernel does not tokenise past the first space; matching that avoids scripts
        // behaving differently here than in a normal shell.
        val envBin = "/data/data/dev.mobileforge/files/usr/bin/env"
        val runner = "/data/data/dev.mobileforge/files/home/app/run"

        val result = builder(
            mapOf(runner to script("#!$envBin -S node --enable-source-maps"), envBin to dynamicElf()),
        ).build(runner, emptyList())

        val command = (result as AppResult.Success).value
        assertThat(command.argv[2]).isEqualTo("-S node --enable-source-maps")
    }

    @Test
    fun `a script whose interpreter is missing names the interpreter`() {
        val artisan = "/data/data/dev.mobileforge/files/home/app/artisan"
        val result = builder(mapOf(artisan to script("#!/usr/bin/php")))
            .build(artisan, emptyList())

        val error = (result as AppResult.Failure).error
        assertThat(error.detail).contains("/usr/bin/php")
        assertThat(error.message).contains("interpreter")
    }

    @Test
    fun `a nested interpreter script is refused rather than looped`() {
        val outer = "/data/data/dev.mobileforge/files/home/a"
        val middle = "/data/data/dev.mobileforge/files/usr/bin/wrapper"

        val result = builder(
            mapOf(outer to script("#!$middle"), middle to script("#!/system/bin/sh")),
        ).build(outer, emptyList())

        assertThat((result as AppResult.Failure).error.detail).contains("itself a script")
    }

    @Test
    fun `a script run on a device without W^X needs no linker`() {
        val relaxed = env.copy(appTargetSdk = 28)
        val php = "/data/data/dev.mobileforge/files/usr/bin/php"
        val artisan = "/data/data/dev.mobileforge/files/home/app/artisan"

        val result = ExecCommandBuilder(
            ExecStrategySelector(relaxed),
            FakeHeaders(mapOf(artisan to script("#!$php"), php to dynamicElf())),
        ).build(artisan, listOf("list"))

        val command = (result as AppResult.Success).value
        assertThat(command.argv).containsExactly(php, artisan, "list").inOrder()
    }
}
