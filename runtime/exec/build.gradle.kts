plugins {
    id("mobileforge.jvm.library")
}

// PURE JVM, deliberately.
//
// Everything that decides HOW a binary is invoked — linker selection, argv construction,
// shebang resolution, environment assembly — is platform-agnostic logic operating on injected
// facts (ExecEnvironment). That means the single most security- and correctness-critical part
// of the runtime is unit-testable on a laptop with no emulator, which matters a great deal for
// a mechanism whose failure mode is "nothing runs at all".
//
// The Android-specific facts (Build.VERSION.SDK_INT, nativeLibraryDir, filesDir) are gathered
// by the caller and passed in. See docs/adr/ADR-009-exec-core.md.
dependencies {
    api(projects.runtime.api)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
