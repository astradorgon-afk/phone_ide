# ADR-007 — Build toolchain and SDK level pinning

* **Status:** Accepted, with one tracked follow-up required before any store release
* **Date:** 2026-09-06
* **Affects:** every module, release readiness

## Context

The build must actually build. Choosing the newest of everything is the usual way to get an
unbuildable multi-module Android project, because AGP, Gradle, Kotlin, KSP and the Compose
compiler are mutually version-constrained.

## Environment as measured

| Component | Present on this machine |
|---|---|
| OS | Windows 11 Pro 26200 |
| JDK | Temurin 17.0.14 (the only JDK installed) |
| Gradle | 8.10.2 distribution present in the wrapper cache |
| Android SDK platforms | android-33, 34, 35, 36 |
| Build tools | 34.0.0, 36.0.0 |
| NDK | 26.3.11579264, 27.0.12077973 |
| Node / npm | 26.4.0 / 11.17.0 (used only to fetch Monaco) |
| Connected devices | **none** |

## Decision

| Component | Pinned version | Why |
|---|---|---|
| Gradle | **8.10.2** | Already in the local wrapper cache; supported by AGP 8.7.x. |
| AGP | **8.7.3** | Already in the Gradle module cache; the newest AGP that pairs with Gradle 8.10.2 without a distribution upgrade. |
| Kotlin | **2.0.21** | Stable; Compose compiler is a first-party Kotlin plugin from 2.0, removing the old compiler-extension version matrix entirely. |
| KSP | **2.0.21-1.0.28** | Must match the Kotlin version exactly. |
| JDK | **17** | The only installed JDK; AGP 8.7 targets 17. |
| `compileSdk` / `targetSdk` | **35** | Highest supported by AGP 8.7.3. |
| `minSdk` | **30** | Product decision from ADR-002 — see below. |

Every version above was **verified resolvable** against Google Maven and Maven Central before
being written into `gradle/libs.versions.toml`, rather than recalled from memory.

## `minSdk = 30` is a product decision, not a default

Android 11 is the floor at which the Phase 2 system-linker-exec runtime is viable. Supporting
Android 10 or below would ship the IDE to devices where its central promise — running PHP,
Node and Git — can never work. Shipping a device tier that can only ever be a text editor is
the kind of quiet dishonesty this project is explicitly avoiding.

## Known follow-up: `compileSdk`/`targetSdk` 36 before release

Google Play's target API requirement moved to **API 36 for new apps and updates as of
31 August 2026**; API 35 covers existing apps only. Today's date is after that boundary, so
**this project cannot be published to Google Play at `targetSdk 35`.**

This is accepted *for Phase 1 only*, because Phase 1's deliverable is a verified-compiling,
locally-tested IDE shell, and AGP 8.7.3 + Gradle 8.10.2 were already cached — making the build
reproducible now without a toolchain download that introduces its own failure surface.

**Required before any store release** (tracked as a Phase 9 gate, and safe to do earlier):

1. Gradle → 8.13+ (`./gradlew wrapper --gradle-version 8.13`)
2. AGP → 8.11.x or newer
3. `compileSdk` and `targetSdk` → 36 in `libs.versions.toml`
4. Re-run `./gradlew build` and audit API-36 behaviour changes

This is a version bump in one catalogue file plus a wrapper update, precisely because versions
are centralised. It is not a refactor.

## Structural choices

* **Convention plugins in `build-logic/`** rather than repeated per-module config. Four plugins:
  `android.application`, `android.library`, `android.compose`, `jvm.library`. Compose is
  separate so non-UI Android modules never pay the Compose compiler cost.
* **Type-safe project accessors** (`projects.core.model`) so a renamed module is a compile
  error, not a runtime surprise.
* **Version catalogue only.** No module declares a literal dependency version.
* **Configuration cache and build cache on.** A 13-module build is otherwise slow enough that
  people stop running tests.
* **`abortOnError = true` for lint, `warningsAsErrors = false`.** Phase 1 has deliberately
  unfinished seams; failing on a deprecation warning pushes contributors toward suppressing
  rather than fixing. Revisited at the end of Phase 2.

### Revisited 2026-09-11 — resolved as per-issue severity, not a blanket switch

`warningsAsErrors = true` was rejected, for a specific reason rather than a general reluctance.
Of the 55 warnings at the time, **33 were "a newer version exists"** (`GradleDependency`,
`NewerVersionAvailable`, `AndroidGradlePluginVersion`). Those are time-dependent: the build
would start failing when an unrelated library publishes a release, with no change to this
repository. That is precisely the pressure toward blanket suppression the original decision was
trying to avoid.

Instead:

* **`Aligned16KB` is promoted to an error.** It caught a real, shipped defect —
  `libmfexec.so` and `libmfpty.so` were built without `-Wl,-z,max-page-size=16384`, so on a
  16 KB-page device (all new hardware from 2025) neither would load. For libmfexec that is not
  degradation but total failure: it is `LD_PRELOAD`ed into every process, so nothing installed
  could be executed from a shell. The project was refusing *bundles* that lacked this alignment
  while shipping unaligned libraries of its own.
* **Version-nag checks are disabled.** Dependency upgrades are a deliberate, reviewed activity
  tracked in ROADMAP.md, not a per-build prompt.

Result: 55 warnings to 11, all of them real code issues, and a regression in 16 KB alignment now
fails the build rather than adding a line to a report nobody reads.

The remaining 11 were then fixed: a dead `SDK_INT >= O` branch removed (minSdk is 30), the
`SharedPreferences.edit`/`String.toUri` KTX extensions adopted, a monochrome layer added to the
adaptive icon, `android:roundIcon` declared so `ic_launcher_round` is no longer an unused
resource, and the toolchain installer switched from `usableSpace` to
`StorageManager.getAllocatableBytes` — the latter matters because that number *gates* an
install, and refusing a 20 MB toolchain on a device with reclaimable cache is a bug the user
cannot diagnose. Diagnostics deliberately keeps `usableSpace`, since it reports what is free
rather than what could be freed.

**One warning is left deliberately.** Lint reports that the `mipmap-anydpi-v26` qualifier is
unnecessary at minSdk 30. Renaming the folder to `mipmap-anydpi` makes AAPT2 fail outright with
`resource mipmap/ic_launcher not found` — verified, twice, including after a clean. The
conventional `-v26` layout is kept and the warning left visible rather than suppressed, so the
next person does not repeat the experiment.

## Consequences

* `assembleRelease` is not a supported target in Phase 1 — there is no signing config, and
  R8 rules have not been validated. `assembleDebug` is the verified artifact.
* The toolchain upgrade above is real work that must not be forgotten; it is recorded in
  ROADMAP.md as a release gate.

## References

* [Meet Google Play's target API level requirement](https://support.google.com/googleplay/android-developer/answer/11926878)
