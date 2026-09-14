plugins {
    id("mobileforge.jvm.library")
}

// Phase 1 ships CONTRACTS ONLY. No process is executed by this module.
// The implementation lands in Phase 2 as :runtime:exec — see docs/adr/ADR-002-runtime-strategy.md.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
