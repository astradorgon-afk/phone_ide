package dev.mobileforge.feature.editor.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.mobileforge.core.common.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The single, narrow channel between the editor WebView and Kotlin.
 *
 * Security posture (RISK-010, ADR-003):
 *   - Exactly ONE @JavascriptInterface method is exposed: [postMessage]. There is no object
 *     graph reachable from JavaScript, no filesystem access, and no path to secrets.
 *   - Inbound payloads are size-capped BEFORE parsing, so a hostile page cannot exhaust memory
 *     by posting an unbounded string.
 *   - Parsing is strict. An unknown message type or a malformed payload is dropped and logged
 *     at SECURITY level, never best-effort interpreted.
 *   - Outbound commands are serialised by kotlinx.serialization and passed as a JSON string
 *     argument, so no caller can build JavaScript by concatenation.
 *
 * The editor is a view, not a source of truth: Kotlin holds the document. A WebView crash costs
 * a reload, not the user's unsaved work.
 */
class MonacoBridge(
    private val logger: Logger,
) {

    private val json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    private val _events = MutableSharedFlow<EditorEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        // The WebView must never block waiting on a slow collector; dropping the oldest event
        // is correct because state is reconciled from Kotlin, not accumulated from the view.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<EditorEvent> = _events.asSharedFlow()

    private var webView: WebView? = null

    fun attach(webView: WebView) {
        this.webView = webView
    }

    fun detach() {
        webView = null
    }

    /**
     * The only entry point callable from JavaScript.
     *
     * Runs on the WebView's JS thread. It does no work beyond validation and a non-blocking
     * emit, so a slow consumer can never stall rendering.
     */
    @JavascriptInterface
    fun postMessage(payload: String?) {
        if (payload == null) {
            logger.security(TAG, "Dropped null bridge message")
            return
        }
        if (payload.length > BridgeProtocol.MAX_INBOUND_BYTES) {
            logger.security(
                TAG,
                "Dropped oversized bridge message (${payload.length} chars)",
            )
            return
        }

        val event = try {
            json.decodeFromString<EditorEvent>(payload)
        } catch (e: Exception) {
            // Never log the payload itself: it can contain the user's source code.
            logger.security(TAG, "Dropped malformed bridge message: ${e.javaClass.simpleName}")
            return
        }

        if (!_events.tryEmit(event)) {
            logger.warn(TAG, "Bridge event buffer full; dropped ${event::class.simpleName}")
        }
    }

    /** Sends a command to the editor. Safe to call before the WebView exists — it no-ops. */
    fun send(command: EditorCommand) {
        val view = webView ?: return
        val encoded = json.encodeToString(EditorCommand.serializer(), command)

        // evaluateJavascript with a single JSON-string argument. The payload is a *string
        // literal* produced by the JSON encoder, so editor content containing quotes, newlines
        // or </script> cannot break out into executable JavaScript.
        val argument = json.encodeToString(String.serializer(), encoded)
        view.post {
            view.evaluateJavascript("window.__mfReceive($argument);", null)
        }
    }

    private companion object {
        const val TAG = "MonacoBridge"
    }
}
