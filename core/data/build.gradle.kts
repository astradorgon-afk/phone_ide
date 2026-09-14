plugins {
    id("mobileforge.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.mobileforge.core.data"

    // Room schema export: migrations are reviewed as diffs, not guessed at.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.security)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
