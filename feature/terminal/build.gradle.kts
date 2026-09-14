plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
}

android {
    namespace = "dev.mobileforge.feature.terminal"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)

    // The terminal engine: PTY + emulator + session (ADR-010).
    api(projects.runtime.pty)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
