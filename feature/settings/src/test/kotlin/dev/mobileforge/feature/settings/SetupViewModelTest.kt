package dev.mobileforge.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.data.settings.AppSettings
import dev.mobileforge.core.data.settings.SettingsRepository
import dev.mobileforge.runtime.api.EmbeddedBundle
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * First-run setup sequencing, against fakes.
 *
 * Order is the whole point — base before the tools that need it — so the tests that
 * matter assert the install sequence and that a failure stops it rather than charging
 * into the next bundle. Already-installed bundles are skipped, never reinstalled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private class FakeManager : ToolchainManager {
        var installedIds: Set<String> = emptySet()
        val inspected = mutableListOf<String>()
        val installed = mutableListOf<String>()
        var failInstallId: String? = null
        var incompatibleId: String? = null

        override suspend fun inspect(manifestDocument: String): AppResult<ToolchainImport> {
            inspected += manifestDocument
            val id = manifestDocument.substringAfter("manifest-").substringBefore(".json")
            return ToolchainImport(
                token = "token-$id",
                name = id,
                version = "1",
                abi = "x86_64",
                prefix = "/prefix",
                license = "MIT",
                sourceUrl = "https://example.invalid",
                sha256 = "0",
                sizeBytes = 1,
                incompatibility = if (id == incompatibleId) "nope" else null,
            ).asSuccess()
        }

        override suspend fun install(
            importToken: String,
            archiveDocument: String,
            onProgress: (Float) -> Unit,
        ): AppResult<Unit> {
            val id = importToken.removePrefix("token-")
            installed += id
            if (id == failInstallId) {
                return AppError(
                    category = ErrorCategory.FileSystem,
                    message = "boom",
                ).asFailure()
            }
            onProgress(1f)
            installedIds += id
            return Unit.asSuccess()
        }

        override suspend fun installed(): AppResult<List<ToolchainRecord>> =
            installedIds.map {
                ToolchainRecord(it, it, "1", "x86_64", "MIT", "https://example.invalid", true)
            }.asSuccess()
    }

    private class FakeSettings : SettingsRepository {
        private val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        var dismissed = false
            private set

        override suspend fun setThemeMode(mode: String) = Unit
        override suspend fun setEditorFontSize(sizeSp: Int) = Unit
        override suspend fun setShowHiddenFiles(show: Boolean) = Unit
        override suspend fun setWordWrap(enabled: Boolean) = Unit
        override suspend fun setAutoSave(enabled: Boolean) = Unit
        override suspend fun setMinimapEnabled(enabled: Boolean) = Unit
        override suspend fun setCompletedFirstRun(completed: Boolean) {
            dismissed = completed
        }
        override suspend fun setBundleSourceUrl(url: String?) = Unit
    }

    private fun bundle(id: String) = EmbeddedBundle(
        id = id,
        displayName = id,
        version = "1",
        abi = "x86_64",
        sizeBytes = 10,
        manifestDocument = "manifest-$id.json",
        archiveDocument = "archive-$id.zip",
    )

    private val scheduler = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun `loads required bundles in order, marking installed done`() = runTest(scheduler) {
        val manager = FakeManager().apply { installedIds = setOf("base") }
        val settings = FakeSettings()
        val model = SetupViewModel(manager, settings) {
            listOf(bundle("base"), bundle("php"), bundle("composer")).asSuccess()
        }
        advanceUntilIdle()

        val items = model.state.value.items
        assertThat(items.map { it.id }).containsExactly("base", "php", "composer").inOrder()
        assertThat(items.map { it.status }).containsExactly(
            SetupItemStatus.DONE, SetupItemStatus.PENDING, SetupItemStatus.PENDING,
        ).inOrder()
        assertThat(model.state.value.loading).isFalse()
    }

    @Test fun `installs pending bundles front to back, then done`() = runTest(scheduler) {
        val manager = FakeManager()
        val model = SetupViewModel(manager, FakeSettings()) {
            listOf(bundle("base"), bundle("php"), bundle("composer")).asSuccess()
        }
        advanceUntilIdle()

        model.installAll()
        advanceUntilIdle()

        assertThat(manager.installed).containsExactly("base", "php", "composer").inOrder()
        assertThat(model.state.value.done).isTrue()
        assertThat(model.state.value.busy).isFalse()
        assertThat(model.state.value.overallProgress).isEqualTo(1f)
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `a failure stops the sequence and keeps the error`() = runTest(scheduler) {
        val manager = FakeManager().apply { failInstallId = "php" }
        val model = SetupViewModel(manager, FakeSettings()) {
            listOf(bundle("base"), bundle("php"), bundle("composer")).asSuccess()
        }
        advanceUntilIdle()

        model.installAll()
        advanceUntilIdle()

        // base landed, php failed, composer never started.
        assertThat(manager.installed).containsExactly("base", "php").inOrder()
        assertThat(model.state.value.error).isNotNull()
        assertThat(model.state.value.busy).isFalse()
        assertThat(model.state.value.done).isFalse()
    }

    @Test fun `a failure resets the item so retry works`() = runTest(scheduler) {
        val manager = FakeManager().apply { failInstallId = "php" }
        val model = SetupViewModel(manager, FakeSettings()) {
            listOf(bundle("base"), bundle("php"), bundle("composer")).asSuccess()
        }
        advanceUntilIdle()

        model.installAll()
        advanceUntilIdle()

        // The failed item is pending again — without this, Retry would silently do nothing.
        assertThat(model.state.value.items.first { it.id == "php" }.status)
            .isEqualTo(SetupItemStatus.PENDING)

        manager.failInstallId = null
        model.installAll()
        advanceUntilIdle()

        // Base is not reinstalled; the run resumes after it.
        assertThat(manager.installed.takeLast(2))
            .containsExactly("php", "composer").inOrder()
        assertThat(model.state.value.done).isTrue()
    }

    @Test fun `an incompatible bundle refuses with its reason`() = runTest(scheduler) {
        val manager = FakeManager().apply { incompatibleId = "php" }
        val model = SetupViewModel(manager, FakeSettings()) {
            listOf(bundle("base"), bundle("php")).asSuccess()
        }
        advanceUntilIdle()

        model.installAll()
        advanceUntilIdle()

        assertThat(model.state.value.error?.detail).isEqualTo("nope")
        assertThat(manager.installed).containsExactly("base")
    }

    @Test fun `unbundled requirements are reported missing, not installed`() =
        runTest(scheduler) {
            val manager = FakeManager()
            val model = SetupViewModel(manager, FakeSettings()) {
                listOf(bundle("base")).asSuccess()
            }
            advanceUntilIdle()

            assertThat(model.state.value.items.map { it.status }).containsExactly(
                SetupItemStatus.PENDING, SetupItemStatus.MISSING, SetupItemStatus.MISSING,
            ).inOrder()

            model.installAll()
            advanceUntilIdle()

            assertThat(manager.installed).containsExactly("base")
            assertThat(model.state.value.done).isFalse()
        }

    @Test fun `finish and skip both persist dismissal`() = runTest(scheduler) {
        val first = FakeSettings()
        SetupViewModel(FakeManager(), first) { emptyList<EmbeddedBundle>().asSuccess() }
        advanceUntilIdle()

        val second = FakeSettings()
        val model = SetupViewModel(FakeManager(), second) {
            emptyList<EmbeddedBundle>().asSuccess()
        }
        advanceUntilIdle()

        model.skip()
        assertThat(second.dismissed).isTrue()
        model.finish()
        assertThat(second.dismissed).isTrue()
        assertThat(first.dismissed).isFalse()
    }
}
