package dev.mobileforge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Navigation and the workspace trust gate, driven through the real UI.
 *
 * Trust is a security control, and until now it had only unit coverage — which proves the view
 * model does the right thing when asked, not that the user is ever actually asked. These tests
 * exercise the path a person takes: the dialog appears *before* an untrusted project opens, and
 * each of its two answers leads somewhere different and correct.
 *
 * The dialog is deliberately pre-emptive. By the time a hostile repository has been browsed, an
 * agent has read it, or a build script has run, a prompt would be too late.
 */
class NavigationAndTrustUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val created = mutableListOf<WorkspaceId>()
    private val container get() = (compose.activity.application as MobileForgeApplication).container

    @After
    fun cleanup() = runBlocking {
        created.forEach { container.workspaceRepository.remove(it, deleteFiles = true) }
        created.clear()
    }

    // --- Navigation ---------------------------------------------------------------

    @Test
    fun settingsAndDiagnosticsAreReachableAndBackReturns() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Run system check").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("MobileForge").assertIsDisplayed()
    }

    // --- Trust gate ---------------------------------------------------------------

    @Test
    fun anUntrustedProjectPromptsBeforeItOpens() {
        val workspace = untrustedWorkspace()
        openProject(workspace)

        compose.onNodeWithText("Do you trust this project?").assertIsDisplayed()
        // The project must NOT be open behind the dialog.
        assertTextAbsent("Files")
    }

    @Test
    fun openingRestrictedKeepsTheProjectReadOnly() {
        val workspace = untrustedWorkspace()
        openProject(workspace)

        compose.onNodeWithText("Open restricted").performClick()

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(RESTRICTED_BANNER).fetchSemanticsNodes().isNotEmpty()
        }
        // Read-only means the save affordance is absent, not merely disabled.
        assertIconAbsent("Save")

        // Trust is not silently granted by opening.
        assert(storedTrust(workspace.id) == WorkspaceTrust.Untrusted) {
            "Opening in restricted mode must not change stored trust, but it became " +
                "${storedTrust(workspace.id)}"
        }
    }

    @Test
    fun trustingTheProjectRemovesTheRestrictedBannerAndPersists() {
        val workspace = untrustedWorkspace()
        openProject(workspace)

        compose.onNodeWithText("Trust project").performClick()

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("Files").fetchSemanticsNodes().isNotEmpty()
        }
        assertTextAbsent(RESTRICTED_BANNER)

        assert(storedTrust(workspace.id) == WorkspaceTrust.Trusted) {
            "Trust must be persisted, but the stored value was ${storedTrust(workspace.id)}"
        }
    }

    // --- Helpers ------------------------------------------------------------------

    private fun untrustedWorkspace(): Workspace = runBlocking {
        val name = "Trust check ${System.nanoTime()}"
        val result = container.workspaceRepository.create(
            name,
            DetectedFramework.Unknown,
            WorkspaceTrust.Untrusted,
        )
        val workspace = (result as AppResult.Success).value
        created += workspace.id
        container.fileSystemFor(workspace.rootPath).writeText("readme.txt", "untrusted content")
        workspace
    }

    private fun openProject(workspace: Workspace) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(workspace.name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(workspace.name).performClick()
    }

    /** Reads trust back from the database, not from the UI, so persistence is what is checked. */
    private fun storedTrust(id: WorkspaceId): WorkspaceTrust? = runBlocking {
        (container.workspaceRepository.find(id) as? AppResult.Success)?.value?.trust
    }

    private fun assertTextAbsent(text: String) {
        assert(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            "Did not expect \"$text\" to be on screen"
        }
    }

    private fun assertIconAbsent(contentDescription: String) {
        val nodes = compose.onAllNodesWithContentDescription(contentDescription)
        assert(nodes.fetchSemanticsNodes().isEmpty()) {
            "Did not expect \"$contentDescription\" to be on screen"
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val RESTRICTED_BANNER = "Restricted mode — read only"
    }
}
