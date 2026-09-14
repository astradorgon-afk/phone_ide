package dev.mobileforge.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.feature.editor.MonacoEditorView
import dev.mobileforge.feature.editor.WebViewCompatibility
import dev.mobileforge.feature.editor.bridge.EditorCommand
import dev.mobileforge.feature.editor.bridge.EditorEvent
import dev.mobileforge.feature.editor.bridge.MonacoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * VERIFIES THAT MONACO ACTUALLY RUNS.
 *
 * Every other editor test so far has either exercised the old-WebView fallback or asserted
 * things about Kotlin state. None of them proved the editor itself works, because no runtime
 * available during development shipped a WebView new enough to load Monaco — so the central
 * feature of the product went unverified while the surrounding code accumulated.
 *
 * This test closes that gap, and it is deliberately hard to pass by accident:
 *
 *  - [EditorEvent.Ready] can only arrive if the bundled Monaco parsed and initialised. An
 *    engine too old fails with a bare SyntaxError, and the CSP (`script-src 'self'`) silently
 *    drops anything inline, so both historical failure modes surface here as a timeout.
 *  - The round trip proves a real text model exists. A page that merely loaded, or a bridge
 *    that echoed the command back, would not return the document under a fresh request id.
 *
 * It is skipped rather than failed on an engine below the supported floor: that is a property
 * of the device, not a defect in the app, and the fallback path is covered separately by
 * [dev.mobileforge.EditorAndSettingsUiTest].
 */
class MonacoRenderVerificationTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = ConcurrentLinkedQueue<EditorEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun monacoBootsAndRoundTripsDocumentContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = WebViewCompatibility.check(context)
        assumeTrue("WebView cannot run Monaco on this device: $status", status.canRunEditor)

        val bridge = MonacoBridge(NoOpLogger)
        scope.launch { bridge.events.collect(events::add) }

        compose.setContent {
            MonacoEditorView(bridge = bridge, modifier = Modifier.fillMaxSize())
        }

        // 1. Monaco's own JavaScript must call back into Kotlin. Nothing else produces Ready.
        compose.waitUntil(BOOT_TIMEOUT_MS) { events.any { it is EditorEvent.Ready } }

        // 2. Give it a document, then ask for it back under a fresh request id.
        val document = "<?php\n// monaco round trip ${UUID.randomUUID()}\nreturn 42;\n"
        val requestId = UUID.randomUUID().toString()

        compose.runOnIdle {
            bridge.send(
                EditorCommand.SetDocument(
                    path = "routes/web.php",
                    content = document,
                    languageId = "php",
                    readOnly = false,
                ),
            )
            bridge.send(EditorCommand.RequestContent(requestId))
        }

        compose.waitUntil(ROUND_TRIP_TIMEOUT_MS) {
            events.any { it is EditorEvent.Content && it.requestId == requestId }
        }

        val reply = events.filterIsInstance<EditorEvent.Content>()
            .first { it.requestId == requestId }

        assertEquals(
            "Monaco returned different text than it was given.",
            document,
            reply.content,
        )

        val errors = events.filterIsInstance<EditorEvent.Error>()
        assertTrue(
            "Monaco reported errors: ${errors.joinToString { it.message }}",
            errors.isEmpty(),
        )
    }

    private companion object {
        /** Generous: a cold WebView start plus Monaco's AMD loader on a slow emulator. */
        const val BOOT_TIMEOUT_MS = 60_000L
        const val ROUND_TRIP_TIMEOUT_MS = 30_000L
    }
}
