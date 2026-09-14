package dev.mobileforge

import android.app.Application
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
    }
}
