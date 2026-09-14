plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
}

android {
    namespace = "dev.mobileforge.feature.settings"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(projects.core.filesystem)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)

    // Contracts only: the diagnostics screen reports runtime capability, it does not use it.
    implementation(projects.runtime.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
