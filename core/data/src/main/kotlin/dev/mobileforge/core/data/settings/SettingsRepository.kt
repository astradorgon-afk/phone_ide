package dev.mobileforge.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User preferences.
 *
 * DataStore rather than SharedPreferences because reads are a Flow and writes are suspending —
 * the brief forbids blocking the UI thread, and SharedPreferences' synchronous reads are a
 * quiet violation of that on a cold start.
 *
 * NEVER put a credential here. Preferences are plaintext on disk; secrets go to
 * [dev.mobileforge.core.data.secure.SecretStore].
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: String)
    suspend fun setEditorFontSize(sizeSp: Int)
    suspend fun setShowHiddenFiles(show: Boolean)
    suspend fun setWordWrap(enabled: Boolean)
    suspend fun setAutoSave(enabled: Boolean)
    suspend fun setMinimapEnabled(enabled: Boolean)
    suspend fun setCompletedFirstRun(completed: Boolean)
}

/**
 * All Phase 1 settings.
 *
 * Defaults are chosen for a phone, not for a desktop: word wrap on (horizontal scrolling on a
 * 6-inch screen is miserable), minimap off (it costs pixels and memory that a phone does not
 * have to spare), auto-save off (surprising writes to a user's source tree should be opt-in).
 */
data class AppSettings(
    val themeMode: String = "System",
    val editorFontSizeSp: Int = 13,
    val showHiddenFiles: Boolean = false,
    val wordWrap: Boolean = true,
    val autoSave: Boolean = false,
    val minimapEnabled: Boolean = false,
    val completedFirstRun: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val store = context.applicationContext.dataStore

    override val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.themeMode] ?: "System",
            editorFontSizeSp = (prefs[Keys.editorFontSize] ?: 13)
                .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
            showHiddenFiles = prefs[Keys.showHiddenFiles] ?: false,
            wordWrap = prefs[Keys.wordWrap] ?: true,
            autoSave = prefs[Keys.autoSave] ?: false,
            minimapEnabled = prefs[Keys.minimap] ?: false,
            completedFirstRun = prefs[Keys.completedFirstRun] ?: false,
        )
    }

    override suspend fun setThemeMode(mode: String) {
        store.edit { it[Keys.themeMode] = mode }
    }

    override suspend fun setEditorFontSize(sizeSp: Int) {
        // Clamped on write as well as on read: a bad value must not be persisted at all.
        store.edit { it[Keys.editorFontSize] = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) }
    }

    override suspend fun setShowHiddenFiles(show: Boolean) {
        store.edit { it[Keys.showHiddenFiles] = show }
    }

    override suspend fun setWordWrap(enabled: Boolean) {
        store.edit { it[Keys.wordWrap] = enabled }
    }

    override suspend fun setAutoSave(enabled: Boolean) {
        store.edit { it[Keys.autoSave] = enabled }
    }

    override suspend fun setMinimapEnabled(enabled: Boolean) {
        store.edit { it[Keys.minimap] = enabled }
    }

    override suspend fun setCompletedFirstRun(completed: Boolean) {
        store.edit { it[Keys.completedFirstRun] = completed }
    }

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val editorFontSize = intPreferencesKey("editor_font_size")
        val showHiddenFiles = booleanPreferencesKey("show_hidden_files")
        val wordWrap = booleanPreferencesKey("word_wrap")
        val autoSave = booleanPreferencesKey("auto_save")
        val minimap = booleanPreferencesKey("minimap")
        val completedFirstRun = booleanPreferencesKey("completed_first_run")
    }

    private companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 28
    }
}
