package dev.mobileforge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.data.settings.SettingsRepository
import dev.mobileforge.runtime.api.EmbeddedBundle
import dev.mobileforge.runtime.api.REQUIRED_TOOLCHAIN_IDS
import dev.mobileforge.runtime.api.ToolchainManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupItemStatus {
    PENDING,
    INSTALLING,
    DONE,
    MISSING,
}

data class SetupItem(
    val id: String,
    val displayName: String,
    val version: String,
    val sizeBytes: Long,
    val status: SetupItemStatus,
)

data class SetupUiState(
    val loading: Boolean = true,
    val items: List<SetupItem> = emptyList(),
    val currentName: String? = null,
    val overallProgress: Float = 0f,
    val busy: Boolean = false,
    val error: AppError? = null,
    val done: Boolean = false,
) {
    val pending: List<SetupItem> get() = items.filter { it.status == SetupItemStatus.PENDING }
    val pendingBytes: Long get() = pending.sumOf { it.sizeBytes }
}

/**
 * First-run toolchain setup: installs the embedded bundles front to back.
 *
 * Order is the whole point — [REQUIRED_TOOLCHAIN_IDS] walks base before the tools that
 * need it, and the install refuses out-of-order requirements anyway, so a partial run
 * always leaves a working prefix subset rather than a broken one. A failure stops the
 * sequence with its error instead of charging into the next bundle; retry resumes after
 * the completed ones because installed records are re-read, not assumed.
 *
 * Finishing and skipping both persist dismissal: a user who said "not now" must not be
 * asked again on every launch. They can still install later from Settings.
 */
class SetupViewModel(
    private val manager: ToolchainManager,
    private val settings: SettingsRepository,
    private val embedded: suspend () -> AppResult<List<EmbeddedBundle>>,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SetupUiState())
    val state = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (state.value.busy) return
        mutableState.update { SetupUiState(loading = true) }
        viewModelScope.launch {
            val bundles = when (val result = embedded()) {
                is AppResult.Failure -> {
                    mutableState.update {
                        SetupUiState(loading = false, error = result.error)
                    }
                    return@launch
                }
                is AppResult.Success -> result.value
            }
            val installedIds = when (val result = manager.installed()) {
                is AppResult.Failure -> {
                    mutableState.update {
                        SetupUiState(loading = false, error = result.error)
                    }
                    return@launch
                }
                is AppResult.Success -> result.value.map { it.id }.toSet()
            }
            val byId = bundles.associateBy { it.id }
            val items = REQUIRED_TOOLCHAIN_IDS.map { id ->
                val bundle = byId[id]
                when {
                    id in installedIds -> SetupItem(id, bundle?.displayName ?: id,
                        bundle?.version ?: "", 0L, SetupItemStatus.DONE)
                    bundle == null -> SetupItem(id, id, "", 0L, SetupItemStatus.MISSING)
                    else -> SetupItem(bundle.id, bundle.displayName, bundle.version,
                        bundle.sizeBytes, SetupItemStatus.PENDING)
                }
            }
            mutableState.update {
                SetupUiState(loading = false, items = items,
                    done = items.all { it.status == SetupItemStatus.DONE })
            }
        }
    }

    fun installAll() {
        val before = state.value
        if (before.busy || before.loading) return
        val queue = before.pending
        if (queue.isEmpty()) return
        mutableState.update { it.copy(busy = true, overallProgress = 0f, error = null) }
        viewModelScope.launch {
            val bundles = (embedded() as? AppResult.Success)?.value.orEmpty()
                .associateBy { it.id }
            val total = queue.size
            var finished = 0
            for (item in queue) {
                val bundle = bundles[item.id]
                if (bundle == null) {
                    mutableState.update { it.copy(busy = false,
                        error = missingError(item.id)) }
                    return@launch
                }
                mutableState.update { state ->
                    state.copy(
                        items = state.items.map {
                            if (it.id == item.id) it.copy(status = SetupItemStatus.INSTALLING)
                            else it
                        },
                        currentName = item.displayName,
                    )
                }
                when (val inspected = manager.inspect(bundle.manifestDocument)) {
                    is AppResult.Failure -> {
                        mutableState.update { state ->
                            state.copy(
                                busy = false,
                                currentName = null,
                                error = inspected.error,
                                items = state.items.map {
                                    if (it.id == item.id) {
                                        it.copy(status = SetupItemStatus.PENDING)
                                    } else it
                                },
                            )
                        }
                        return@launch
                    }
                    is AppResult.Success -> {
                        val incompatibility = inspected.value.incompatibility
                        if (incompatibility != null) {
                            mutableState.update { state ->
                                state.copy(
                                    busy = false,
                                    currentName = null,
                                    error = incompatibleError(inspected.value.name,
                                        incompatibility),
                                    items = state.items.map {
                                        if (it.id == item.id) {
                                            it.copy(status = SetupItemStatus.PENDING)
                                        } else it
                                    },
                                )
                            }
                            return@launch
                        }
                        when (val installed = manager.install(
                            inspected.value.token, bundle.archiveDocument,
                        ) { progress ->
                            mutableState.update {
                                it.copy(overallProgress = (finished + progress) / total)
                            }
                        }) {
                            is AppResult.Failure -> {
                                mutableState.update { state ->
                                    state.copy(
                                        busy = false,
                                        currentName = null,
                                        error = installed.error,
                                        items = state.items.map {
                                            if (it.id == item.id) {
                                                it.copy(status = SetupItemStatus.PENDING)
                                            } else it
                                        },
                                    )
                                }
                                return@launch
                            }
                            is AppResult.Success -> {
                                finished += 1
                                mutableState.update { state ->
                                    state.copy(
                                        items = state.items.map {
                                            if (it.id == item.id) {
                                                it.copy(status = SetupItemStatus.DONE)
                                            } else it
                                        },
                                        overallProgress = finished.toFloat() / total,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            mutableState.update { state ->
                state.copy(
                    busy = false,
                    currentName = null,
                    // Done only when nothing is left wanting — MISSING items keep this false
                    // so the screen shows Continue only when there is nothing left to do.
                    done = state.items.all { it.status == SetupItemStatus.DONE },
                )
            }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    /**
     * Persists dismissal, suspending so callers navigate only after it lands — otherwise a
     * cleared ViewModel could cancel the write and the setup would nag again next launch.
     */
    suspend fun finish() {
        settings.setCompletedFirstRun(true)
        mutableState.update { it.copy(done = true) }
    }

    suspend fun skip() {
        settings.setCompletedFirstRun(true)
    }

    private fun missingError(id: String) = AppError(
        category = ErrorCategory.Unavailable,
        message = "“$id” is not included in the app itself.",
        detail = "The embedded set changed since this screen loaded.",
        recovery = "Install it from Settings → Toolchains, or continue without it.",
    )

    private fun incompatibleError(name: String, reason: String) = AppError(
        category = ErrorCategory.Unavailable,
        message = "$name cannot be installed on this device.",
        detail = reason,
        recovery = "Continue without it — the rest of the app still works.",
    )
}
