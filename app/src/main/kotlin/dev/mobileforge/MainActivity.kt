package dev.mobileforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.data.settings.AppSettings
import dev.mobileforge.core.designsystem.theme.ForgeThemeMode
import dev.mobileforge.core.designsystem.theme.MobileForgeTheme
import dev.mobileforge.navigation.MobileForgeNavHost

/**
 * The only Activity.
 *
 * It sets content and nothing else — routing lives in the navigation graph, state lives in
 * ViewModels, and business logic lives below those. A god Activity is explicitly ruled out by
 * ADR-001, and this is where that rule is either kept or lost.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as MobileForgeApplication).container

        setContent {
            val settings: AppSettings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            val themeMode = runCatching { ForgeThemeMode.valueOf(settings.themeMode) }
                .getOrDefault(ForgeThemeMode.System)

            MobileForgeTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MobileForgeNavHost(container = container)
                }
            }
        }
    }
}
