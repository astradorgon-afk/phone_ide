pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "MobileForge"

// ---- Application shell ----
include(":app")

// ---- Core: domain + platform-agnostic infrastructure ----
include(":core:common")
include(":core:model")
include(":core:security")
include(":core:filesystem")
include(":core:designsystem")
include(":core:data")

// ---- Features: user-facing vertical slices ----
include(":feature:projects")
include(":feature:workspace")
include(":feature:editor")
include(":feature:settings")
include(":feature:terminal")

// ---- Development runtime ----
include(":runtime:api")

// Phase 2: the execution core. Pure JVM on purpose — see docs/adr/ADR-009-exec-core.md.
include(":runtime:exec")

// Phase 2b: the PTY. Native, because a real terminal needs a real TTY - see ADR-010.
include(":runtime:pty")

// Phase 2b-iii: toolchain install + integrity. See docs/adr/ADR-011-toolchain-strategy.md.
include(":runtime:toolchain")

// Phase 3: Laravel. Pure JVM — artisan command validation and result parsing are testable
// without a device; only the executor behind them is platform-specific.
include(":runtime:laravel")

// ---- Contracts for later phases (interfaces only) ----
include(":ai:api")
