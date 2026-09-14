package dev.mobileforge.core.filesystem

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.security.PathValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Tests for the real filesystem implementation.
 *
 * These run as plain JVM tests against a temporary directory — [LocalWorkspaceFileSystem]
 * touches no Android API, which is deliberate: it keeps the containment logic verifiable
 * without an emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorkspaceFileSystemTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fs: LocalWorkspaceFileSystem

    private val dispatchers = object : AppDispatchers {
        private val test: CoroutineDispatcher = StandardTestDispatcher()
        override val main = test
        override val io = test
        override val default = test
    }

    @Before
    fun setUp() {
        root = temp.newFolder("workspace")
        fs = LocalWorkspaceFileSystem(
            workspaceRoot = root,
            pathValidator = PathValidator(),
            dispatchers = dispatchers,
            logger = NoOpLogger,
        )
    }

    // ---------- reading and writing ----------

    @Test
    fun `writes then reads a file`() = runTest(dispatchers.io) {
        val write = fs.writeText("routes/web.php", "<?php echo 'hi';")
        assertThat(write.isSuccess).isTrue()

        val read = fs.readText("routes/web.php")
        assertThat(read.getOrNull()).isEqualTo("<?php echo 'hi';")
    }

    @Test
    fun `writing creates missing parent directories`() = runTest(dispatchers.io) {
        fs.writeText("app/Http/Controllers/HomeController.php", "x")
        assertThat(File(root, "app/Http/Controllers/HomeController.php").isFile).isTrue()
    }

    @Test
    fun `an atomic write leaves no temp file behind`() = runTest(dispatchers.io) {
        fs.writeText("a.txt", "content")
        val strays = root.listFiles()?.filter { it.name.endsWith(".mf-tmp") }.orEmpty()
        assertThat(strays).isEmpty()
    }

    @Test
    fun `overwriting replaces the previous content entirely`() = runTest(dispatchers.io) {
        fs.writeText("a.txt", "the original longer content")
        fs.writeText("a.txt", "short")
        assertThat(fs.readText("a.txt").getOrNull()).isEqualTo("short")
    }

    @Test
    fun `reading a missing file is a filesystem error not a crash`() = runTest(dispatchers.io) {
        val result = fs.readText("nope.txt")
        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.FileSystem)
    }

    @Test
    fun `refuses to open a file larger than the editor limit`() = runTest(dispatchers.io) {
        val big = File(root, "big.bin")
        big.writeBytes(ByteArray((FileLimits.MAX_EDITABLE_BYTES + 1024).toInt()))

        val result = fs.readText("big.bin")

        assertThat(result.isSuccess).isFalse()
        // Validation, not FileSystem: the file is fine, the request is out of bounds.
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.Validation)
        assertThat(result.errorOrNull()!!.recovery).isNotNull()
    }

    // ---------- containment ----------

    @Test
    fun `refuses to read outside the workspace`() = runTest(dispatchers.io) {
        File(temp.root, "outside.txt").writeText("secret")

        val result = fs.readText("../outside.txt")

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.Security)
    }

    @Test
    fun `refuses to write outside the workspace`() = runTest(dispatchers.io) {
        val result = fs.writeText("../escaped.txt", "payload")

        assertThat(result.isSuccess).isFalse()
        assertThat(File(temp.root, "escaped.txt").exists()).isFalse()
    }

    /**
     * The check a logical path validator cannot make.
     *
     * "link" is a perfectly ordinary relative path; only canonicalisation reveals that it
     * points outside the workspace. Skipped on filesystems that will not create symlinks
     * (Windows without developer mode), because a skipped test is honest and a silently
     * passing one is not.
     */
    @Test
    fun `refuses to follow a symlink that escapes the workspace`() = runTest(dispatchers.io) {
        val outside = File(temp.root, "outside-secrets")
        outside.mkdirs()
        File(outside, "creds.txt").writeText("APP_KEY=super-secret")

        val linkCreated = runCatching {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        }.isSuccess

        org.junit.Assume.assumeTrue(
            "Symlink creation unavailable on this filesystem; skipping",
            linkCreated,
        )

        val result = fs.readText("link/creds.txt")

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.Security)
    }

    @Test
    fun `refuses to delete the workspace root`() = runTest(dispatchers.io) {
        val result = fs.delete("")

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.Security)
        assertThat(root.exists()).isTrue()
    }

    // ---------- listing ----------

    @Test
    fun `lists directories before files and sorts case-insensitively`() =
        runTest(dispatchers.io) {
            File(root, "zeta.txt").writeText("")
            File(root, "Alpha.txt").writeText("")
            File(root, "src").mkdirs()

            val names = fs.list("").getOrNull()!!.map { it.name }

            assertThat(names).containsExactly("src", "Alpha.txt", "zeta.txt").inOrder()
        }

    @Test
    fun `listing does not expose in-flight temp files`() = runTest(dispatchers.io) {
        File(root, "real.txt").writeText("")
        File(root, "real.txt.mf-tmp").writeText("")

        val names = fs.list("").getOrNull()!!.map { it.name }

        assertThat(names).containsExactly("real.txt")
    }

    @Test
    fun `listing a file rather than a directory is an error`() = runTest(dispatchers.io) {
        fs.writeText("a.txt", "x")
        assertThat(fs.list("a.txt").isSuccess).isFalse()
    }

    // ---------- create, rename, delete ----------

    @Test
    fun `creating a file that already exists is rejected`() = runTest(dispatchers.io) {
        fs.createFile("a.txt")
        val second = fs.createFile("a.txt")

        assertThat(second.isSuccess).isFalse()
        assertThat(second.errorOrNull()!!.category).isEqualTo(ErrorCategory.Validation)
    }

    @Test
    fun `renaming onto an existing name is rejected`() = runTest(dispatchers.io) {
        fs.writeText("a.txt", "a")
        fs.writeText("b.txt", "b")

        val result = fs.rename("a.txt", "b.txt")

        assertThat(result.isSuccess).isFalse()
        assertThat(fs.readText("b.txt").getOrNull()).isEqualTo("b")
    }

    @Test
    fun `deleting a directory removes it recursively`() = runTest(dispatchers.io) {
        fs.writeText("pkg/nested/file.txt", "x")

        val result = fs.delete("pkg")

        assertThat(result.isSuccess).isTrue()
        assertThat(File(root, "pkg").exists()).isFalse()
    }

    @Test
    fun `exists reports false for an escaping path rather than throwing`() =
        runTest(dispatchers.io) {
            assertThat(fs.exists("../outside.txt")).isFalse()
        }
}
