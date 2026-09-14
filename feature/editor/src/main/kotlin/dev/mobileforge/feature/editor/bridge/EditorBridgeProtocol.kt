package dev.mobileforge.feature.editor.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The complete, closed message vocabulary between Kotlin and the editor WebView.
 *
 * Everything crossing this boundary is one of these types. There is no generic
 * "invoke this Kotlin method" escape hatch, because project files rendered in the WebView are
 * attacker-controlled content and the bridge is the boundary that keeps them from reaching
 * Android APIs (RISK-010, ADR-003).
 *
 * Sealed and versioned on purpose: an unknown or malformed message is dropped and logged at
 * SECURITY level rather than best-effort parsed. Being lenient here is how bridges turn into
 * remote-code-execution surfaces.
 */
object BridgeProtocol {
    /** Bumped on any breaking change to the message shapes below. */
    const val VERSION: Int = 1

    /**
     * Hard cap on a single inbound message. Generous enough for a large paste, small enough
     * that a hostile page cannot exhaust memory by posting an unbounded string.
     */
    const val MAX_INBOUND_BYTES: Int = 8 * 1024 * 1024
}

// ---------------------------------------------------------------------------------
// Kotlin -> WebView
// ---------------------------------------------------------------------------------

@Serializable
sealed interface EditorCommand {

    @Serializable
    @SerialName("setDocument")
    data class SetDocument(
        val path: String,
        val content: String,
        val languageId: String,
        val readOnly: Boolean,
    ) : EditorCommand

    @Serializable
    @SerialName("setTheme")
    data class SetTheme(val theme: String) : EditorCommand

    @Serializable
    @SerialName("setOptions")
    data class SetOptions(
        val fontSizeSp: Int,
        val wordWrap: Boolean,
        val minimap: Boolean,
    ) : EditorCommand

    @Serializable
    @SerialName("requestContent")
    data class RequestContent(val requestId: String) : EditorCommand

    @Serializable
    @SerialName("runAction")
    data class RunAction(val action: String) : EditorCommand

    @Serializable
    @SerialName("setDiagnostics")
    data class SetDiagnostics(val diagnostics: List<Diagnostic>) : EditorCommand
}

@Serializable
data class Diagnostic(
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val message: String,
    val severity: String,
)

// ---------------------------------------------------------------------------------
// WebView -> Kotlin
// ---------------------------------------------------------------------------------

@Serializable
sealed interface EditorEvent {

    /** Monaco finished mounting. Kotlin does not push a document before this arrives. */
    @Serializable
    @SerialName("ready")
    data class Ready(val protocolVersion: Int) : EditorEvent

    /**
     * The buffer changed. Carries only a dirty flag and a version, never the whole document —
     * shipping the full text on every keystroke is what makes WebView editors feel slow.
     */
    @Serializable
    @SerialName("changed")
    data class Changed(val path: String, val version: Int) : EditorEvent

    /** Reply to [EditorCommand.RequestContent]. This is how a save gets its text. */
    @Serializable
    @SerialName("content")
    data class Content(
        val requestId: String,
        val path: String,
        val content: String,
    ) : EditorEvent

    @Serializable
    @SerialName("cursor")
    data class Cursor(val line: Int, val column: Int) : EditorEvent

    /** The in-editor save gesture (Ctrl+S). Kotlin decides whether a save actually happens. */
    @Serializable
    @SerialName("saveRequested")
    data class SaveRequested(val path: String) : EditorEvent

    /** A failure inside the WebView, surfaced rather than swallowed. */
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : EditorEvent
}
