package dev.mobileforge.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.model.LanguageDetector
import dev.mobileforge.feature.editor.bridge.EditorCommand
import dev.mobileforge.feature.editor.bridge.EditorEvent
import dev.mobileforge.feature.editor.bridge.MonacoBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns editor state. The WebView is a view; this is the source of truth.
 *
 * That split is what makes the editor survivable on Android: if the WebView is reclaimed under
 * memory pressure or crashes, the buffer, the dirty flag and the open path all still live here,
 * and recovery is a reload rather than data loss.
 */
class EditorViewModel(
    private val fileSystem: WorkspaceFileSystem,
    val bridge: MonacoBridge,
    private val logger: Logger,
    val nativeEditing: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Outstanding content requests, keyed by requestId, awaiting the WebView's reply. */
    private val contentRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var editorReady = false
    private var editRevision = 0L
    private var openSequence = 0L

    init {
        viewModelScope.launch {
            bridge.events.collect(::onEditorEvent)
        }
    }

    fun openFile(relativePath: String, readOnly: Boolean) {
        val sequence = ++openSequence
        editRevision++
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = fileSystem.readText(relativePath)
            if (sequence != openSequence) return@launch
            when (result) {
                is AppResult.Failure -> {
                    _state.update { it.copy(isLoading = false, error = result.error) }
                }

                is AppResult.Success -> {
                    val languageId = LanguageDetector.detect(relativePath.substringAfterLast('/'))
                    _state.update {
                        it.copy(
                            openPath = relativePath,
                            content = result.value,
                            languageId = languageId,
                            isDirty = false,
                            isLoading = false,
                            readOnly = readOnly,
                            error = null,
                        )
                    }
                    pushDocument()
                }
            }
        }
    }

    /**
     * Saves the buffer.
     *
     * The authoritative text lives in the WebView while the user is typing, so a save is a
     * round trip: ask the editor for its content, then write it. The round trip is bounded —
     * if the WebView does not answer, we report a real error rather than writing a stale
     * buffer over the user's file, which would silently destroy their edits.
     */
    fun save() {
        val path = _state.value.openPath ?: return
        if (_state.value.isSaving) return
        if (_state.value.readOnly) {
            _state.update { it.copy(error = readOnlyError()) }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        val revision = editRevision
        viewModelScope.launch {

            val content = requestEditorContent()
            if (content == null) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = AppError(
                            category = dev.mobileforge.core.common.ErrorCategory.Internal,
                            message = "The file was not saved.",
                            detail = "The editor did not return its contents in time.",
                            recovery = "Try saving again. If it keeps failing, reopen the file.",
                            retryable = true,
                        ),
                    )
                }
                return@launch
            }

            when (val result = fileSystem.writeText(path, content)) {
                is AppResult.Failure ->
                    _state.update { it.copy(isSaving = false, error = result.error) }

                is AppResult.Success ->
                    _state.update {
                        if (it.openPath == path && editRevision == revision) {
                            it.copy(isSaving = false, isDirty = false, content = content, error = null)
                        } else it.copy(isSaving = false)
                    }
            }
        }
    }

    /** Native input shares the same trust checks and atomic file-save path as Monaco. */
    fun editNativeContent(content: String) {
        val current = _state.value
        if (!nativeEditing || current.readOnly || current.openPath == null || current.isLoading) return
        editRevision++
        _state.update { it.copy(content = content, isDirty = true) }
    }

    fun runAction(action: String) {
        bridge.send(EditorCommand.RunAction(action))
    }

    fun applyOptions(fontSizeSp: Int, wordWrap: Boolean, minimap: Boolean) {
        bridge.send(EditorCommand.SetOptions(fontSizeSp, wordWrap, minimap))
    }

    fun applyTheme(isDark: Boolean) {
        bridge.send(EditorCommand.SetTheme(if (isDark) "dark" else "light"))
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun closeFile() {
        openSequence++
        editRevision++
        _state.value = EditorUiState()
    }

    // -----------------------------------------------------------------------------

    private suspend fun requestEditorContent(): String? {
        if (nativeEditing) return _state.value.content
        if (!editorReady) return null
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        contentRequests[requestId] = deferred

        bridge.send(EditorCommand.RequestContent(requestId))

        return try {
            withTimeoutOrNull(CONTENT_TIMEOUT_MS) { deferred.await() }
        } finally {
            contentRequests.remove(requestId)
        }
    }

    private fun onEditorEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.Ready -> {
                editorReady = true
                // Re-push whatever is open: this also handles the WebView being recreated
                // after a configuration change or a low-memory teardown.
                if (_state.value.openPath != null) pushDocument()
            }

            is EditorEvent.Changed -> {
                editRevision++
                _state.update { it.copy(isDirty = true) }
            }

            is EditorEvent.Content -> {
                contentRequests.remove(event.requestId)?.complete(event.content)
            }

            is EditorEvent.Cursor ->
                _state.update { it.copy(cursorLine = event.line, cursorColumn = event.column) }

            is EditorEvent.SaveRequested -> save()

            is EditorEvent.Error -> {
                logger.error(TAG, "Editor reported: ${event.message}")
                _state.update {
                    it.copy(
                        error = AppError(
                            category = dev.mobileforge.core.common.ErrorCategory.Internal,
                            message = "The editor reported a problem.",
                            detail = event.message,
                            recovery = "Reopen the file. If it persists, please report it.",
                        ),
                    )
                }
            }
        }
    }

    private fun pushDocument() {
        val s = _state.value
        val path = s.openPath ?: return
        bridge.send(
            EditorCommand.SetDocument(
                path = path,
                content = s.content,
                languageId = s.languageId,
                readOnly = s.readOnly,
            ),
        )
    }

    private fun readOnlyError() = AppError(
        category = dev.mobileforge.core.common.ErrorCategory.Security,
        message = "This project is open in restricted mode.",
        detail = "Untrusted projects are read-only until you trust them.",
        recovery = "Trust the project from the project menu to enable editing.",
    )

    private companion object {
        const val TAG = "EditorViewModel"
        const val CONTENT_TIMEOUT_MS = 5_000L
    }
}

data class EditorUiState(
    val openPath: String? = null,
    val content: String = "",
    val languageId: String = "plaintext",
    val isDirty: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val readOnly: Boolean = false,
    val cursorLine: Int = 1,
    val cursorColumn: Int = 1,
    val error: AppError? = null,
) {
    val fileName: String? get() = openPath?.substringAfterLast('/')

    /** Rendered as "routes/web.php *" — the dirty marker the brief asks for. */
    val title: String get() = buildString {
        append(fileName ?: "No file open")
        if (isDirty) append(" *")
    }
}
