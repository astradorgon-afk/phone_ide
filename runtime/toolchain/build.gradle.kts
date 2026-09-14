plugins {
    id("mobileforge.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM: manifest parsing, integrity verification, ABI and page-size compatibility and the
// install/extract logic are all platform-agnostic given injected facts. Same reasoning as
// :runtime:exec (ADR-009) — the parts most likely to be wrong are the parts we can test here.
dependencies {
    api(projects.runtime.api)
    api(projects.runtime.exec)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
