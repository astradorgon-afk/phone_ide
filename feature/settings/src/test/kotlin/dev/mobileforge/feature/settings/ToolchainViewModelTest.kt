package dev.mobileforge.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
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
        override suspend fun installed() = AppResult.Success(emptyList<ToolchainRecord>())
    }

    @Test fun `incompatible bundle cannot install even when action is called directly`() = runTest(dispatcher) {
        val manager = Manager().apply { rejected = true }
        val model = ToolchainViewModel(manager)
        advanceUntilIdle()
        model.selectManifest("manifest")
        advanceUntilIdle()
        model.selectArchive("zip")
        model.install()
        advanceUntilIdle()
        assertThat(manager.calls).isEqualTo(0)
    }

    @Test fun `changing manifest clears previous archive selection`() = runTest(dispatcher) {
        val model = ToolchainViewModel(Manager())
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
        val model = ToolchainViewModel(manager)
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
}
