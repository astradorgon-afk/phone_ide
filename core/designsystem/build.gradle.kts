plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
}

android {
    namespace = "dev.mobileforge.core.designsystem"
}

dependencies {
    // api, not implementation: ErrorBanner takes an AppError in its public signature, so every
    // consumer of this module needs the type on its compile classpath.
    api(projects.core.common)

    implementation(libs.androidx.core.ktx)
    // material-icons-extended is deliberately NOT used: it added ~44 MB of dex to the debug
    // APK for a few dozen glyphs. The core icon set that ships with material3 covers the
    // explorer, settings and toolbar needs. Any icon it lacks is added as a local vector asset.
    api(libs.androidx.compose.material3.adaptive)
    api(libs.androidx.window)
}
