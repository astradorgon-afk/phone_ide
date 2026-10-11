package dev.mobileforge.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import dev.mobileforge.runtime.toolchain.BundleCatalog
import dev.mobileforge.runtime.toolchain.CatalogBundle
import dev.mobileforge.runtime.toolchain.CatalogInstallProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolchainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Manager : ToolchainManager {
        var rejected = false
        var failInstall = false
        var calls = 0
        var installedRecords: List<ToolchainRecord> = emptyList()
        override suspend fun inspect(manifestDocument: String) = AppResult.Success(ToolchainImport(
            "token", "Git", "1", "arm64-v8a", "/prefix", "GPL-2.0", "https://example.com/source",
            "a".repeat(64), 100, if (rejected) "Wrong ABI" else null))
        override suspend fun install(importToken: String, archiveDocument: String,
            onProgress: (Float) -> Unit): AppResult<Unit> {
            calls++
            onProgress(0.5f)
            return if (failInstall) AppResult.Failure(AppError(category = ErrorCategory.Security,
                message = "Digest mismatch")) else AppResult.Success(Unit)
        }
        override suspend fun installed() = AppResult.Success(installedRecords)
    }

    private class Store(
        var catalogResult: AppResult<BundleCatalog> = AppResult.Success(
            BundleCatalog(baseUrl = "https://bundles.example.com", bundles = listOf(
                CatalogBundle("base", "Base", "1.0.0", "arm64-v8a", "base-1.0.0-arm64-v8a"),
                CatalogBundle("composer", "Composer", "2.10.3", "arm64-v8a", "composer-2.10.3-arm64-v8a")))),
        var installResult: AppResult<Unit> = AppResult.Success(Unit),
    ) : BundleStore {
        var installs = 0
        var source: String? = null
        override suspend fun catalog() = catalogResult
        override suspend fun installAll(
            catalog: BundleCatalog,
            onProgress: (CatalogInstallProgress) -> Unit,
        ): AppResult<Unit> {
            installs++
            onProgress(CatalogInstallProgress("base", "Base", 1f))
            return installResult
        }
        override suspend fun sourceUrl() = source
        override suspend fun setSourceUrl(url: String?) { source = url }
    }

    private fun record(id: String) = ToolchainRecord(id, id, "1", "arm64-v8a", "MIT",
        "https://example.com/source", true)

    @Test fun `incompatible bundle cannot install even when action is called directly`() = runTest(dispatcher) {
        val manager = Manager().apply { rejected = true }
        val model = ToolchainViewModel(manager, Store())
        advanceUntilIdle()
        model.selectManifest("manifest")
        advanceUntilIdle()
        model.selectArchive("zip")
        model.install()
        advanceUntilIdle()
        assertThat(manager.calls).isEqualTo(0)
    }

    @Test fun `changing manifest clears previous archive selection`() = runTest(dispatcher) {
        val model = ToolchainViewModel(Manager(), Store())
        advanceUntilIdle()
        model.selectManifest("first")
        advanceUntilIdle()
        model.selectArchive("zip")
        model.selectManifest("second")
        advanceUntilIdle()
        assertThat(model.state.value.archiveDocument).isNull()
    }

    @Test fun `failed installation remains retryable without a success message`() = runTest(dispatcher) {
        val manager = Manager().apply { failInstall = true }
        val model = ToolchainViewModel(manager, Store())
        advanceUntilIdle()
        model.selectManifest("manifest")
        advanceUntilIdle()
        model.selectArchive("zip")
        model.install()
        model.install()
        advanceUntilIdle()
        assertThat(manager.calls).isEqualTo(1)
        assertThat(model.state.value.error?.message).isEqualTo("Digest mismatch")
        assertThat(model.state.value.message).isNull()
        assertThat(model.state.value.busy).isFalse()
        manager.failInstall = false
        model.install()
        advanceUntilIdle()
        assertThat(manager.calls).isEqualTo(2)
        assertThat(model.state.value.selection).isNull()
        assertThat(model.state.value.message).contains("Git installed")
    }

    @Test fun `the catalog is shown and installed bundles are marked`() = runTest(dispatcher) {
        val manager = Manager().apply { installedRecords = listOf(record("base")) }
        val model = ToolchainViewModel(manager, Store())
        advanceUntilIdle()

        assertThat(model.state.value.catalog?.bundles).hasSize(2)
        assertThat(model.state.value.installedIds).containsExactly("base")
    }

    @Test fun `installMissing downloads and installs, then reports success`() = runTest(dispatcher) {
        val store = Store()
        val model = ToolchainViewModel(Manager(), store)
        advanceUntilIdle()

        model.installMissing()
        advanceUntilIdle()

        assertThat(store.installs).isEqualTo(1)
        assertThat(model.state.value.message).contains("Toolchain ready")
        assertThat(model.state.value.busy).isFalse()
        assertThat(model.state.value.catalogProgress).isNull()
    }

    @Test fun `a failed download surfaces the error and stays retryable`() = runTest(dispatcher) {
        val store = Store(installResult = AppResult.Failure(AppError(
            category = ErrorCategory.Network, message = "PHP could not be downloaded.")))
        // base installed before the failure: the list must reflect it, the error must survive.
        val manager = Manager().apply { installedRecords = listOf(record("base")) }
        val model = ToolchainViewModel(manager, store)
        advanceUntilIdle()

        model.installMissing()
        advanceUntilIdle()
        model.installMissing()
        advanceUntilIdle()

        assertThat(model.state.value.error?.message).isEqualTo("PHP could not be downloaded.")
        assertThat(store.installs).isEqualTo(2)
        assertThat(model.state.value.busy).isFalse()
        assertThat(model.state.value.installedIds).contains("base")
    }

    @Test fun `installMissing does nothing when no bundle is available`() = runTest(dispatcher) {
        val store = Store(catalogResult = AppResult.Success(
            BundleCatalog(baseUrl = "https://bundles.example.com")))
        val model = ToolchainViewModel(Manager(), store)
        advanceUntilIdle()

        model.installMissing()
        advanceUntilIdle()

        assertThat(store.installs).isEqualTo(0)
    }

    @Test fun `an unreachable source still shows installed bundles`() = runTest(dispatcher) {
        val store = Store(catalogResult = AppResult.Failure(AppError(
            category = ErrorCategory.Unavailable, message = "The bundle source is not usable.")))
        val manager = Manager().apply { installedRecords = listOf(record("base")) }
        val model = ToolchainViewModel(manager, store)
        advanceUntilIdle()

        assertThat(model.state.value.installed).hasSize(1)
        assertThat(model.state.value.catalog).isNull()
        assertThat(model.state.value.catalogError?.message).isEqualTo("The bundle source is not usable.")
    }

    @Test fun `setting a source persists it and reloads the catalog`() = runTest(dispatcher) {
        val store = Store()
        val model = ToolchainViewModel(Manager(), store)
        advanceUntilIdle()

        model.setSourceUrl("http://127.0.0.1:8080")
        advanceUntilIdle()

        assertThat(store.source).isEqualTo("http://127.0.0.1:8080")
        assertThat(model.state.value.message).contains("Bundle source updated")
    }
}
