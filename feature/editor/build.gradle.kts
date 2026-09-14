plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.mobileforge.feature.editor"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(projects.core.filesystem)
    implementation(projects.core.designsystem)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // androidx.webkit gives us WebViewAssetLoader + feature detection instead of
    // the unsafe file:// origin. See docs/adr/ADR-003-monaco-integration.md.
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
