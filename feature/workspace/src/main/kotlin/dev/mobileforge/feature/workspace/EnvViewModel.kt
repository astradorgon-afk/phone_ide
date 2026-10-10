package dev.mobileforge.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.runtime.laravel.EnvFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns structured `.env` editing state.
 *
 * The screen edits values; this holds the whole file. Round-trip fidelity comes from keeping
 * the parsed [EnvFile] — comments, blanks and quoting survive because edits go through
 * [EnvFile.withValue] and saving writes [EnvFile.render], never a re-serialised key list.
 *
 * Secrets are masked at render time only. State always holds real values, so the mask can
 * never be written back over a secret — there is no path where "••••••••" reaches the disk.
 * Nothing here is logged either: values must never cross into a log sink (rule 7).
 */
class EnvViewModel(
    private val fileSystem: WorkspaceFileSystem,
) : ViewModel() {

    private val _state = MutableStateFlow(EnvUiState())
    val state: StateFlow<EnvUiState> = _state.asStateFlow()

    private var openedPath: String? = null
    private var openSequence = 0L

    /**
     * Loads the `.env` once, then only refreshes flags.
     *
     * Laziness is deliberate: the file holds secrets, so it is not read until the user opens
     * the Env pane. Re-calls (trust change, recomposition) update [EnvUiState.readOnly] without
     * re-reading the disk.
     */
    fun open(
        relativePath: String = ENV_FILE_PATH,
        readOnly: Boolean,
        hasEnvExample: Boolean,
    ) {
        val sequence = ++openSequence
        if (openedPath == relativePath && _state.value.opened) {
            _state.update { it.copy(readOnly = readOnly, hasEnvExample = hasEnvExample) }
            return
        }
        openedPath = relativePath
        _state.value = EnvUiState(
            openPath = relativePath,
            opened = true,
            isLoading = true,
            readOnly = readOnly,
            hasEnvExample = hasEnvExample,
        )
        viewModelScope.launch {
            if (!fileSystem.exists(relativePath)) {
                if (sequence != openSequence) return@launch
                _state.update { it.copy(isLoading = false, found = false) }
                return@launch
            }
            when (val result = fileSystem.readText(relativePath)) {
                is AppResult.Failure -> {
                    if (sequence != openSequence) return@launch
                    _state.update { it.copy(isLoading = false, error = result.error) }
                }

                is AppResult.Success -> {
                    if (sequence != openSequence) return@launch
                    val parsed = EnvFile.parse(result.value)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            found = true,
                            original = parsed,
                            edited = parsed,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    /** Sets an existing entry's value. Read-only workspaces refuse; the UI also disables input. */
    fun setValue(key: String, value: String) {
        val edited = _state.value.edited ?: return
        if (_state.value.readOnly || !_state.value.found) return
        if (edited[key]?.value == value) return
        _state.update { it.copy(edited = edited.withValue(key, value), error = null) }
    }

    /**
     * Adds a new key, or updates it when it already exists.
     *
     * The key shape mirrors the parser's own pattern, so anything accepted here round-trips.
     */
    fun addEntry(key: String, value: String) {
        if (_state.value.readOnly || !_state.value.found) return
        val trimmed = key.trim()
        val rejection = validateKey(trimmed)
        if (rejection != null) {
            _state.update { it.copy(error = rejection) }
            return
        }
        val edited = _state.value.edited ?: return
        _state.update { it.copy(edited = edited.withValue(trimmed, value), error = null) }
    }

    /** A sensitive value is shown in clear only after an explicit tap, per entry. */
    fun reveal(key: String) {
        _state.update { it.copy(revealed = it.revealed + key) }
    }

    fun conceal(key: String) {
        _state.update { it.copy(revealed = it.revealed - key) }
    }

    /**
     * Writes the edited file atomically.
     *
     * The snapshot taken at save start becomes the new baseline, while newer keystrokes stay
     * in [EnvUiState.edited] — so typing during a save keeps its dirty flag instead of being
     * silently marked clean.
     */
    fun save() {
        val current = _state.value
        val edited = current.edited ?: return
        if (current.isSaving) return
        if (current.readOnly) {
            _state.update { it.copy(error = readOnlyError()) }
            return
        }
        if (!current.isDirty) return

        _state.update { it.copy(isSaving = true, error = null) }
        val snapshot = edited
        val text = snapshot.render()
        val path = current.openPath
        viewModelScope.launch {
            when (val result = fileSystem.writeText(path, text)) {
                is AppResult.Failure ->
                    _state.update { it.copy(isSaving = false, error = result.error) }

                is AppResult.Success ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            original = snapshot,
                            savedRevision = it.savedRevision + 1,
                            error = null,
                        )
                    }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun validateKey(key: String): AppError? = when {
        key.isEmpty() -> AppError(
            category = ErrorCategory.Validation,
            message = "Enter a key name.",
            detail = "An empty key cannot be written to .env.",
            recovery = "Use letters, digits and underscores, starting with a letter or underscore.",
        )

        !KEY_PATTERN.matches(key) -> AppError(
            category = ErrorCategory.Validation,
            message = "\"$key\" is not a valid .env key.",
            detail = "Keys start with a letter or underscore, followed by letters, digits, " +
                "underscores or dots.",
            recovery = "Rename the key to match that shape.",
        )

        else -> null
    }

    private fun readOnlyError() = AppError(
        category = ErrorCategory.Security,
        message = "This project is open in restricted mode.",
        detail = "Untrusted projects are read-only until you trust them.",
        recovery = "Trust the project from the project menu to enable editing.",
    )
}

/**
 * One immutable state per screen, exposed as a StateFlow (rule: conventions).
 *
 * [original] vs [edited] is what makes [isDirty] honest: dirty means the rendered text
 * differs, not that a field was touched and reverted.
 */
data class EnvUiState(
    val openPath: String = ENV_FILE_PATH,
    val opened: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val readOnly: Boolean = false,
    val hasEnvExample: Boolean = false,
    val found: Boolean = false,
    val original: EnvFile? = null,
    val edited: EnvFile? = null,
    val revealed: Set<String> = emptySet(),
    val savedRevision: Long = 0L,
    val error: AppError? = null,
) {
    val entries: List<EnvFile.Entry> get() = edited?.entries.orEmpty()

    val isDirty: Boolean get() {
        val before = original ?: return false
        val after = edited ?: return false
        return before.render() != after.render()
    }
}

/** The project-root `.env`, addressed workspace-relative like every other project file. */
const val ENV_FILE_PATH = ".env"

/** Mirrors the parser's own key shape, so accepted keys always round-trip. */
private val KEY_PATTERN = Regex("""^[A-Za-z_][A-Za-z0-9_.]*$""")
