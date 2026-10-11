package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The acquire-and-install sequence.
 *
 * The cases that matter: bundles are installed base-first regardless of catalog order,
 * already-installed bundles are skipped rather than redownloaded, a download failure names the
 * bundle and stops the run, and an incompatible bundle is refused before its archive is
 * fetched. That last one is the whole point of fetching the manifest first.
 */
class CatalogInstallerTest {

    @get:Rule val temp = TemporaryFolder()

    private val catalog = BundleCatalog(
        baseUrl = "https://bundles.example.com",
        bundles = listOf(
            // Deliberately out of dependency order to prove ordering is enforced.
            CatalogBundle("php", "PHP", "8.5.1", "x86_64", "php-8.5.1-x86_64"),
            CatalogBundle("composer", "Composer", "2.10.3", "x86_64", "composer-2.10.3-x86_64"),
            CatalogBundle("base", "Base", "1.0.0", "x86_64", "base-1.0.0-x86_64"),
            CatalogBundle("git", "Git", "2.4.0", "arm64-v8a", "git-2.4.0-arm64-v8a"),
        ),
    )

    private class FakeFetcher(
        private val archiveSize: Map<String, Long> = emptyMap(),
        private val failOn: Set<String> = emptySet(),
    ) : BundleFetcher {
        val fetched = mutableListOf<String>()

        override suspend fun fetch(url: String, destination: File, onProgress: (Float) -> Unit) {
            if (failOn.any { url.contains(it) }) throw IOException("connection reset")
            fetched += url
            destination.parentFile?.mkdirs()
            if (url.endsWith(".json")) {
                destination.writeText("{}")
            } else {
                val id = url.substringAfterLast('/').substringBefore('-')
                destination.writeBytes(ByteArray(archiveSize[id]?.toInt() ?: 32))
            }
            onProgress(1f)
        }
    }

    private class FakeManager(
        private val installedIds: MutableSet<String> = mutableSetOf(),
        private val sizes: Map<String, Long> = emptyMap(),
        private val incompatible: String? = null,
    ) : ToolchainManager {
        val installed = mutableListOf<String>()

        override suspend fun inspect(manifestDocument: String): AppResult<ToolchainImport> {
            val file = manifestDocument.substringAfterLast('/')
            val id = file.substringBefore('-')
            return ToolchainImport(
                token = "token-$id",
                name = id,
                version = "1",
                abi = "x86_64",
                prefix = "/prefix",
                license = "MIT",
                sourceUrl = "https://example.invalid",
                sha256 = "a".repeat(64),
                sizeBytes = sizes[id] ?: 32,
                incompatibility = if (incompatible != null && id == "php") incompatible else null,
            ).asSuccess()
        }

        override suspend fun install(
            importToken: String,
            archiveDocument: String,
            onProgress: (Float) -> Unit,
        ): AppResult<Unit> {
            val id = importToken.removePrefix("token-")
            installed += id
            installedIds += id
            onProgress(1f)
            return Unit.asSuccess()
        }

        override suspend fun installed(): AppResult<List<ToolchainRecord>> =
            installedIds.map {
                ToolchainRecord(it, it, "1", "x86_64", "MIT", "https://example.invalid", true)
            }.asSuccess()
    }

    private fun installer(
        fetcher: BundleFetcher,
        manager: ToolchainManager,
    ) = CatalogInstaller(fetcher, manager, temp.newFolder("downloads"), "x86_64")

    @Test
    fun `installs dependencies base first and ignores other abis`() = runTest {
        val manager = FakeManager(sizes = mapOf("base" to 32L, "php" to 32L, "composer" to 32L))
        val fetcher = FakeFetcher(archiveSize = mapOf("base" to 32L, "php" to 32L, "composer" to 32L))

        val result = installer(fetcher, manager).installAll(catalog)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(manager.installed).containsExactly("base", "php", "composer").inOrder()
        // Git is arm64; it must not even be looked up.
        assertThat(fetcher.fetched.none { it.contains("git") }).isTrue()
    }

    @Test
    fun `already-installed bundles are skipped, not redownloaded`() = runTest {
        val manager = FakeManager(installedIds = mutableSetOf("base", "composer"))
        val fetcher = FakeFetcher()

        installer(fetcher, manager).installAll(catalog)

        assertThat(manager.installed).containsExactly("php")
        assertThat(fetcher.fetched.none { it.contains("base-") }).isTrue()
        assertThat(fetcher.fetched.none { it.contains("composer-") }).isTrue()
    }

    @Test
    fun `nothing to do succeeds without touching the network`() = runTest {
        val manager = FakeManager(installedIds = mutableSetOf("base", "php", "composer"))
        val fetcher = FakeFetcher()

        val result = installer(fetcher, manager).installAll(catalog)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(fetcher.fetched).isEmpty()
    }

    @Test
    fun `a failed download names the bundle and stops the run`() = runTest {
        val manager = FakeManager()
        val fetcher = FakeFetcher(failOn = setOf("php-8.5.1"))

        val result = installer(fetcher, manager).installAll(catalog) as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Network)
        assertThat(result.error.message).contains("PHP")
        // Base installed, PHP failed, Composer never attempted.
        assertThat(manager.installed).containsExactly("base")
    }

    @Test
    fun `an incompatible bundle is refused before its archive is fetched`() = runTest {
        val manager = FakeManager(incompatible = "Wrong ABI")
        val fetcher = FakeFetcher()

        val result = installer(fetcher, manager).installAll(catalog) as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Unavailable)
        assertThat(result.error.detail).isEqualTo("Wrong ABI")
        // Base is fetched and installed; it is PHP's archive that must never be requested.
        assertThat(fetcher.fetched.none { it.contains("php") && it.endsWith(".zip") }).isTrue()
    }

    @Test
    fun `a catalog with nothing for this device says so`() = runTest {
        val deviceOnly = catalog.copy(
            bundles = catalog.bundles.filter { it.abi == "arm64-v8a" },
        )
        val manager = FakeManager()

        val result = installer(FakeFetcher(), manager).installAll(deviceOnly)
            as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Unavailable)
        assertThat(result.error.detail).contains("x86_64")
    }

    @Test
    fun `progress runs from zero to one across the whole set`() = runTest {
        val manager = FakeManager()
        val fetcher = FakeFetcher()
        val seen = mutableListOf<Float>()

        installer(fetcher, manager).installAll(catalog) { seen += it.overall }

        assertThat(seen.first()).isEqualTo(0f)
        assertThat(seen.last()).isEqualTo(1f)
        assertThat(seen).isInOrder()
    }
}
