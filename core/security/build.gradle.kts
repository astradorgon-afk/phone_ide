plugins {
    id("mobileforge.jvm.library")
}

// Pure JVM so that path-traversal, redaction and trust rules are unit-testable
// on the JVM with no emulator. Android-backed implementations live in :core:data.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
