package dev.mobileforge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.data.settings.AppSettings
import dev.mobileforge.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val diagnostics: DiagnosticsProvider,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _diagnosticsReport = MutableStateFlow<DiagnosticsReport?>(null)
    val diagnosticsReport: StateFlow<DiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    fun setThemeMode(mode: String) = viewModelScope.launch { repository.setThemeMode(mode) }

    fun setEditorFontSize(size: Int) =
        viewModelScope.launch { repository.setEditorFontSize(size) }

    fun setWordWrap(enabled: Boolean) = viewModelScope.launch { repository.setWordWrap(enabled) }

    fun setMinimap(enabled: Boolean) =
        viewModelScope.launch { repository.setMinimapEnabled(enabled) }

    fun setShowHiddenFiles(show: Boolean) =
        viewModelScope.launch { repository.setShowHiddenFiles(show) }

    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { repository.setAutoSave(enabled) }

    fun runDiagnostics() {
        viewModelScope.launch {
            _diagnosticsReport.value = null
            _diagnosticsReport.value = diagnostics.collect()
        }
    }
}
