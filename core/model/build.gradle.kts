plugins {
    id("mobileforge.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM on purpose: the domain model must not be able to reference android.*.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
