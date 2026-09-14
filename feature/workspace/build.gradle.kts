plugins {
    id("mobileforge.android.library")
    id("mobileforge.android.compose")
}

android {
    namespace = "dev.mobileforge.feature.workspace"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(projects.core.filesystem)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.feature.editor)
    implementation(projects.feature.terminal)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
