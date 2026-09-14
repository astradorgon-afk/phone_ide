plugins {
    id("mobileforge.jvm.library")
}

// Phase 1 ships CONTRACTS ONLY. No provider talks to a network in Phase 1.
// Adapters land in Phase 5 — see docs/adr/ADR-004-ai-abstraction.md.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
