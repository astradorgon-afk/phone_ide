plugins {
    id("mobileforge.android.library")
}

android {
    namespace = "dev.mobileforge.core.filesystem"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.security)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
