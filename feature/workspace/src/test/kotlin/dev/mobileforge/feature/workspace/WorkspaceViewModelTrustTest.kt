package dev.mobileforge.feature.workspace

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.FrameworkDetector
import dev.mobileforge.core.filesystem.LocalWorkspaceFileSystem
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.core.security.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The trust flow that the shell, Artisan and Serve view models read before they run anything.
 *
 * The property that matters is the direction of every error: whatever is not yet known, or could
 * not be read, is [WorkspaceTrust.Untrusted]. The other three view models trust this flow, so a
 * default of "trusted" here would silently switch all of their gates off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTrustTest {

    @get:Rule val temp = TemporaryFolder()

    private val scheduler = StandardTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val main = scheduler
        override val io = scheduler
        override val default = scheduler
    }

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    private class FakeRepository(var workspace: Workspace?) : WorkspaceRepository {
        override fun observeWorkspaces(): Flow<List<Workspace>> = flowOf(listOfNotNull(workspace))

        override suspend fun find(id: WorkspaceId): AppResult<Workspace> =
            workspace?.asSuccess()
                ?: AppError(category = ErrorCategory.FileSystem, message = "missing").asFailure()

        override suspend fun create(
            name: String,
            framework: DetectedFramework,
            trust: WorkspaceTrust,
        ): AppResult<Workspace> =
            AppError(category = ErrorCategory.Internal, message = "not used here").asFailure()

        override suspend fun setTrust(id: WorkspaceId, trust: WorkspaceTrust): AppResult<Unit> {
            workspace = workspace?.copy(trust = trust)
            return Unit.asSuccess()
        }

        override suspend fun markOpened(id: WorkspaceId) = Unit.asSuccess()
        override suspend fun setLastActiveFile(id: WorkspaceId, relativePath: String?) =
            Unit.asSuccess()

        override suspend fun remove(id: WorkspaceId, deleteFiles: Boolean) = Unit.asSuccess()
        override suspend fun rootPathOf(id: WorkspaceId): AppResult<String> =
            workspace?.rootPath?.asSuccess()
                ?: AppError(category = ErrorCategory.FileSystem, message = "missing").asFailure()
    }

    private fun workspaceWith(trust: WorkspaceTrust) = Workspace(
        id = WorkspaceId("w"),
        name = "w",
        rootPath = temp.root.absolutePath,
        trust = trust,
        framework = DetectedFramework.Unknown,
        createdAtEpochMs = 0,
        lastOpenedAtEpochMs = 0,
    )

    private fun model(repository: FakeRepository) = WorkspaceViewModel(
        workspaceId = WorkspaceId("w"),
        repository = repository,
        fileSystem = LocalWorkspaceFileSystem(temp.root, PathValidator(), dispatchers, NoOpLogger),
        frameworkDetector = FrameworkDetector(dispatchers),
    )

    @Test fun `trust is untrusted until the workspace has loaded, even for a trusted project`() =
        runTest(scheduler) {
            // Nothing has been dispatched, so the row has not been read. A project that is
            // trusted on disk must still read as untrusted here: unknown must never mean allowed.
            val model = model(FakeRepository(workspaceWith(WorkspaceTrust.Trusted)))

            assertThat(model.trust.value).isEqualTo(WorkspaceTrust.Untrusted)
        }

    @Test fun `a trusted project reads as trusted once loaded`() = runTest(scheduler) {
        val model = model(FakeRepository(workspaceWith(WorkspaceTrust.Trusted)))

        advanceUntilIdle()

        assertThat(model.trust.value).isEqualTo(WorkspaceTrust.Trusted)
    }

    @Test fun `an untrusted project stays untrusted once loaded`() = runTest(scheduler) {
        val model = model(FakeRepository(workspaceWith(WorkspaceTrust.Untrusted)))

        advanceUntilIdle()

        assertThat(model.trust.value).isEqualTo(WorkspaceTrust.Untrusted)
    }

    @Test fun `a workspace that cannot be read is untrusted`() = runTest(scheduler) {
        // The row vanished or the database failed. Neither is a reason to run anything.
        val model = model(FakeRepository(workspace = null))

        advanceUntilIdle()

        assertThat(model.trust.value).isEqualTo(WorkspaceTrust.Untrusted)
        assertThat(model.state.value.error).isNotNull()
    }

    @Test fun `trusting the project flips the flow and is persisted`() = runTest(scheduler) {
        val repository = FakeRepository(workspaceWith(WorkspaceTrust.Untrusted))
        val model = model(repository)
        advanceUntilIdle()

        model.trustWorkspace()
        advanceUntilIdle()

        assertThat(model.trust.value).isEqualTo(WorkspaceTrust.Trusted)
        // Read back from the repository, not the screen: persistence is what is being checked.
        assertThat(repository.workspace?.trust).isEqualTo(WorkspaceTrust.Trusted)
    }
}
