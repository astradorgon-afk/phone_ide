package dev.mobileforge

import android.app.Application
import android.webkit.WebView
import dev.mobileforge.di.AppContainer
import dev.mobileforge.di.DefaultAppContainer

/**
 * Owns the composition root.
 *
 * Construction is cheap: every collaborator in [DefaultAppContainer] is lazy, so opening the
 * database or touching the Keystore does not happen on the application-start critical path.
 */
class MobileForgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Debug builds only: allows chrome://inspect remote debugging of the Monaco WebView
        // on a USB-attached machine (`adb forward tcp:9222 localabstract:chrome_devtools_remote`).
        // Without this, a broken editor page is a black box — the exact situation that made
        // a real-hardware boot failure undiagnosable. Never enabled in release builds.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
