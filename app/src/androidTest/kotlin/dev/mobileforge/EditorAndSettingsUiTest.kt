package dev.mobileforge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.feature.editor.WebViewCompatibility
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorAndSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var createdWorkspace: WorkspaceId? = null
    private val container get() = (compose.activity.application as MobileForgeApplication).container

    @After fun cleanup() = runBlocking {
        createdWorkspace?.let { container.workspaceRepository.remove(it, deleteFiles = true) }
        Unit
    }

    @Test fun settingsOpensToolchainImporter() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Toolchains").performScrollTo().performClick()
        compose.onNodeWithText("Choose manifest").assertIsDisplayed()
        compose.onNodeWithText("Installed bundles").assertIsDisplayed()
    }

    @Test fun basicEditorTypesAndSavesOnOldWebView() {
        assumeFalse(WebViewCompatibility.check(compose.activity).canRunEditor)
        val name = "Editor verification ${System.nanoTime()}"
        val workspace = runBlocking {
            val result = container.workspaceRepository.create(name, DetectedFramework.Unknown,
                WorkspaceTrust.Trusted)
            val created = (result as AppResult.Success).value
            createdWorkspace = created.id
            container.fileSystemFor(created.rootPath).writeText("hello.txt", "original")
            container.workspaceRepository.setLastActiveFile(created.id, "hello.txt")
            created
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Basic editor · Update Android System WebView for Monaco features")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Code editor").performTextReplacement("Saved from Android UI")
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(15000) { File(workspace.rootPath, "hello.txt").readText() == "Saved from Android UI" }
    }
}
