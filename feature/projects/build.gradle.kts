plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
}

android {
    namespace = "dev.mobileforge.feature.projects"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(projects.core.filesystem)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)

    // The Laravel wizard drives the runner contract and the toolchain records.
    implementation(projects.runtime.api)
    implementation(projects.runtime.laravel)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
