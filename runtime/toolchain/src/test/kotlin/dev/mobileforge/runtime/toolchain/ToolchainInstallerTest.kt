package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installer behaviour, with the security cases first.
 *
 * A toolchain bundle is downloaded content that gets written into the app's own private
 * storage and then executed. That is the highest-trust operation in the product, so the
 * rejection paths matter more than the happy path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolchainInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        private val test: CoroutineDispatcher = StandardTestDispatcher()
        override val main = test
        override val io = test
        override val default = test
    }

    private lateinit var prefix: File
    private lateinit var staging: File
    private lateinit var installer: ToolchainInstaller

    @Before
    fun setUp() {
        prefix = temp.newFolder("usr")
        staging = temp.newFolder("staging")
        installer = ToolchainInstaller(prefix, staging, dispatchers, NoOpLogger)
    }

    // ---------- helpers ----------

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val archive = temp.newFile("bundle-${entries.hashCode()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private fun manifestFor(
        archive: File,
        executables: List<String> = emptyList(),
        abi: String = "arm64-v8a",
        pageSize: Int = ToolchainManifest.PAGE_SIZE_16K,
        sha: String? = null,
        symlinks: Map<String, String> = emptyMap(),
    ) = ToolchainManifest(
        id = "testtool",
        displayName = "Test Tool",
        version = "1.0.0",
        abi = abi,
        prefix = prefix.absolutePath,
        sha256 = sha ?: sha256(archive),
        sizeBytes = archive.length(),
        pageSize = pageSize,
        executables = executables,
        symlinks = symlinks,
        license = "MIT",
        sourceUrl = "https://example.invalid/src",
    )

    private fun device(
        abi: String = "arm64-v8a",
        pageSize: Int = ToolchainManifest.PAGE_SIZE_4K,
        available: Long = 1_000_000_000,
    ) = DeviceProfile(
        abi = abi,
        pageSize = pageSize,
        prefix = prefix.absolutePath,
        availableBytes = available,
    )

    // ---------- security ----------

    @Test
    fun `refuses an archive whose digest does not match`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "payload")
        val manifest = manifestFor(archive, sha = "0".repeat(64))

        val result = installer.install(manifest, archive, device())

        assertThat(result.isSuccess).isFalse()
        val error = (result as AppResult.Failure).error
        assertThat(error.category).isEqualTo(ErrorCategory.Security)
        assertThat(error.detail).contains("tampered")
        // Nothing may be written when verification fails.
        assertThat(prefix.listFiles()).isEmpty()
    }

    @Test
    fun `refuses an archive entry that escapes the install directory`() =
        runTest(dispatchers.io) {
            // Zip Slip. A bundle is exactly the kind of artefact that carries this.
            val archive = zipOf("../../escaped.txt" to "owned")
            val manifest = manifestFor(archive)

            val result = installer.install(manifest, archive, device())

            assertThat(result.isSuccess).isFalse()
            assertThat((result as AppResult.Failure).error.category)
                .isEqualTo(ErrorCategory.Security)
            assertThat(File(temp.root, "escaped.txt").exists()).isFalse()
        }

    @Test
    fun `a traversal entry leaves the prefix untouched`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/ok" to "fine", "../../evil" to "bad")
        val manifest = manifestFor(archive)

        installer.install(manifest, archive, device())

        // Staging is discarded wholesale, so a partly-extracted bundle never reaches $PREFIX.
        assertThat(File(prefix, "bin/ok").exists()).isFalse()
        assertThat(staging.listFiles()).isEmpty()
    }

    // ---------- compatibility ----------

    @Test
    fun `refuses a bundle built for another ABI`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive, abi = "armeabi-v7a")

        val result = installer.install(manifest, archive, device(abi = "arm64-v8a"))

        val error = (result as AppResult.Failure).error
        assertThat(error.detail).contains("armeabi-v7a")
        assertThat(error.detail).contains("arm64-v8a")
    }

    @Test
    fun `refuses a 4KB-aligned bundle on a 16KB page device`() = runTest(dispatchers.io) {
        // RISK-004: a 4 KB-aligned shared library will not load on a 16 KB-page device.
        val archive = zipOf("lib/libx.so" to "x")
        val manifest = manifestFor(archive, pageSize = ToolchainManifest.PAGE_SIZE_4K)

        val result = installer.install(
            manifest,
            archive,
            device(pageSize = ToolchainManifest.PAGE_SIZE_16K),
        )

        assertThat((result as AppResult.Failure).error.detail).contains("16 KB")
    }

    @Test
    fun `accepts a 16KB-aligned bundle on a 4KB page device`() = runTest(dispatchers.io) {
        // 16 KB alignment is a superset; it loads fine on 4 KB devices.
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive, pageSize = ToolchainManifest.PAGE_SIZE_16K)

        val result = installer.install(
            manifest,
            archive,
            device(pageSize = ToolchainManifest.PAGE_SIZE_4K),
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `refuses a bundle built for a different prefix`() = runTest(dispatchers.io) {
        // Termux binaries bake in /data/data/com.termux/files/usr and are not relocatable.
        val archive = zipOf("bin/php" to "x")
        val manifest = manifestFor(archive).copy(prefix = "/data/data/com.termux/files/usr")

        val result = installer.install(manifest, archive, device())

        val error = (result as AppResult.Failure).error
        assertThat(error.detail).contains("different install location")
        assertThat(error.recovery).contains("not")
    }

    @Test
    fun `refuses when there is not enough free space`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x".repeat(1000))
        val manifest = manifestFor(archive)

        val result = installer.install(manifest, archive, device(available = 100))

        assertThat((result as AppResult.Failure).error.detail).contains("free space")
    }

    // ---------- happy path ----------

    @Test
    fun `manifest traversal cannot delete a staging neighbour`() = runTest(dispatchers.io) {
        val neighbour = temp.newFolder("keep")
        File(neighbour, "important").writeText("untouched")
        val archive = zipOf("bin/tool" to "x")
        val result = installer.install(manifestFor(archive).copy(id = "../keep"), archive, device())
        assertThat(result.isSuccess).isFalse()
        assertThat(File(neighbour, "important").readText()).isEqualTo("untouched")
    }

    @Test
    fun `missing declared executable refuses entire bundle`() = runTest(dispatchers.io) {
        val archive = zipOf("lib/data" to "x")
        val result = installer.install(manifestFor(archive, listOf("bin/missing")), archive, device())
        assertThat(result.isSuccess).isFalse()
        assertThat(prefix.listFiles()).isEmpty()
    }

    @Test
    fun `non zip input with a valid digest is refused`() = runTest(dispatchers.io) {
        val archive = temp.newFile("not-a-zip")
        archive.writeText("not an archive")
        assertThat(installer.install(manifestFor(archive), archive, device()).isSuccess).isFalse()
    }

    @Test
    fun `size mismatch is refused before extraction`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val result = installer.install(manifestFor(archive).copy(sizeBytes = archive.length() + 1), archive, device())
        assertThat(result.isSuccess).isFalse()
        assertThat(prefix.listFiles()).isEmpty()
    }

    @Test
    fun `file directory conflict cannot partially overwrite installed files`() = runTest(dispatchers.io) {
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tool").writeText("original")
        File(prefix, "lib").writeText("directory blocker")
        val archive = zipOf("bin/tool" to "updated", "lib/data" to "x")
        val result = installer.install(manifestFor(archive), archive, device())
        assertThat(result.isSuccess).isFalse()
        assertThat(File(prefix, "bin/tool").readText()).isEqualTo("original")
        assertThat(File(prefix, "lib").readText()).isEqualTo("directory blocker")
    }

    @Test
    fun `upgrade replaces matching files and preserves unrelated files`() = runTest(dispatchers.io) {
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/tool").writeText("old")
        File(prefix, "bin/other").writeText("keep")
        val archive = zipOf("bin/tool" to "new")
        assertThat(installer.install(manifestFor(archive), archive, device()).isSuccess).isTrue()
        assertThat(File(prefix, "bin/tool").readText()).isEqualTo("new")
        assertThat(File(prefix, "bin/other").readText()).isEqualTo("keep")
        assertThat(staging.listFiles()).isEmpty()
    }

    @Test
    fun `installs files into the prefix`() = runTest(dispatchers.io) {
        val archive = zipOf(
            "bin/tool" to "#!/bin/sh\necho hi\n",
            "lib/data.txt" to "payload",
        )
        val manifest = manifestFor(archive, executables = listOf("bin/tool"))

        val result = installer.install(manifest, archive, device())

        assertThat(result.isSuccess).isTrue()
        assertThat(File(prefix, "bin/tool").readText()).contains("echo hi")
        assertThat(File(prefix, "lib/data.txt").readText()).isEqualTo("payload")
    }

    @Test
    fun `reports the installed executables with absolute paths`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive, executables = listOf("bin/tool"))

        val installed = (installer.install(manifest, archive, device()) as AppResult.Success).value

        assertThat(installed.id).isEqualTo("testtool")
        assertThat(installed.executables.single()).isEqualTo("${prefix.absolutePath}/bin/tool")
    }

    @Test
    fun `installing a second bundle does not remove the first`() = runTest(dispatchers.io) {
        // Bundles share $PREFIX/bin, so promotion must merge rather than replace.
        val first = zipOf("bin/one" to "1")
        installer.install(manifestFor(first, executables = listOf("bin/one")), first, device())

        val second = zipOf("bin/two" to "2")
        installer.install(
            manifestFor(second, executables = listOf("bin/two")).copy(id = "second"),
            second,
            device(),
        )

        assertThat(File(prefix, "bin/one").exists()).isTrue()
        assertThat(File(prefix, "bin/two").exists()).isTrue()
    }

    @Test
    fun `reports progress from start to finish`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val seen = mutableListOf<Float>()

        installer.install(manifestFor(archive), archive, device()) { seen += it }

        assertThat(seen).isNotEmpty()
        assertThat(seen.last()).isEqualTo(1.0f)
        assertThat(seen).isInOrder()
    }

    @Test
    fun `a missing archive is reported as a filesystem error`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive)
        archive.delete()

        val result = installer.install(manifest, archive, device())

        assertThat((result as AppResult.Failure).error.category)
            .isEqualTo(ErrorCategory.FileSystem)
    }

    @Test
    fun `staging is cleaned up after a successful install`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")

        installer.install(manifestFor(archive), archive, device())

        assertThat(staging.listFiles()).isEmpty()
    }

    // ---------- symlinks ----------
    //
    // Real toolchains are mostly links: git ships ~50 pointing at one 3.6 MB binary. Because
    // `zip` follows links and stores a full copy of each target, they are declared in the
    // manifest instead — which also means a hostile bundle can ask for any link it likes, so
    // the escape cases below matter as much as the happy path.

    @Test
    fun `declared symlinks are created in the prefix`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/git" to "real binary")
        val manifest = manifestFor(archive, symlinks = mapOf("bin/git-receive-pack" to "git"))

        val result = installer.install(manifest, archive, device())

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val link = File(prefix, "bin/git-receive-pack")
        assertThat(Files.isSymbolicLink(link.toPath())).isTrue()
        assertThat(link.readText()).isEqualTo("real binary")
    }

    @Test
    fun `a symlinked executable satisfies the executables check`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/git" to "real binary")
        val manifest = manifestFor(
            archive,
            executables = listOf("bin/git", "bin/git-upload-pack"),
            symlinks = mapOf("bin/git-upload-pack" to "git"),
        )

        val result = installer.install(manifest, archive, device())

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(File(prefix, "bin/git-upload-pack").canExecute()).isTrue()
    }

    @Test
    fun `a symlink pointing outside the prefix is refused`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(
            archive,
            symlinks = mapOf("bin/escape" to "../../../../data/data/other.app/files/secret"),
        )

        val result = installer.install(manifest, archive, device())

        assertThat((result as AppResult.Failure).error.category).isEqualTo(ErrorCategory.Security)
        assertThat(File(prefix, "bin/escape").exists()).isFalse()
    }

    @Test
    fun `a symlink to an absolute path outside the prefix is refused`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive, symlinks = mapOf("bin/escape" to "/system/bin/sh"))

        val result = installer.install(manifest, archive, device())

        assertThat((result as AppResult.Failure).error.category).isEqualTo(ErrorCategory.Security)
    }

    @Test
    fun `a symlink whose own path escapes the prefix is refused`() = runTest(dispatchers.io) {
        val archive = zipOf("bin/tool" to "x")
        val manifest = manifestFor(archive, symlinks = mapOf("../../evil" to "tool"))

        val result = installer.install(manifest, archive, device())

        assertThat((result as AppResult.Failure).error.category).isEqualTo(ErrorCategory.Security)
    }

    @Test
    fun `links within the prefix but through a subdirectory are allowed`() =
        runTest(dispatchers.io) {
            val archive = zipOf("lib/libfoo.so.1" to "library")
            val manifest = manifestFor(archive, symlinks = mapOf("bin/libfoo.so" to "../lib/libfoo.so.1"))

            val result = installer.install(manifest, archive, device())

            assertThat(result).isInstanceOf(AppResult.Success::class.java)
            assertThat(File(prefix, "bin/libfoo.so").readText()).isEqualTo("library")
        }

    @Test
    fun `installing twice replaces an existing link rather than failing`() =
        runTest(dispatchers.io) {
            val archive = zipOf("bin/git" to "real binary")
            val manifest = manifestFor(archive, symlinks = mapOf("bin/git-x" to "git"))

            installer.install(manifest, archive, device())
            val second = installer.install(manifest, archive, device())

            assertThat(second).isInstanceOf(AppResult.Success::class.java)
            assertThat(Files.isSymbolicLink(File(prefix, "bin/git-x").toPath())).isTrue()
        }
}
