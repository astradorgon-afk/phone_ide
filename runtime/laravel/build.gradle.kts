plugins {
    id("mobileforge.jvm.library")
}

// Pure JVM, for the same reason as :runtime:exec and :runtime:toolchain (ADR-009): command
// validation, risk gating and output parsing are where the mistakes live, and they are all
// testable without a device. Executing the process is someone else's job, behind an interface.
dependencies {
    api(projects.runtime.api)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.security)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
