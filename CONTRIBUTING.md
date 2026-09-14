# Contributing to MobileForge IDE

## The rules that are not negotiable

These come from the project brief and are the difference between a serious developer tool and a
text editor with an AI chat window bolted on.

### 1. Never ship a feature that pretends to work

No button that shows "coming soon" and does nothing. No green tick for a subsystem that does not
exist. No `TODO()` behind a UI affordance.

If something is not implemented, say which phase delivers it. `ToolStatus.NotImplementedYet` and
`SubsystemState.NotImplemented` exist for exactly this, and the diagnostics screen renders them
verbatim.

### 2. Never fabricate a value

If the app has not measured something, it does not report it. The diagnostics screen shows no PHP
version in Phase 1 because there is no runtime to ask. AI cost reporting uses provider-reported
figures only — if a provider does not report usage, the UI says so rather than estimating.

### 3. The UI never touches a subsystem directly

No Composable opens a file, spawns a process, or calls a model API. `ProcessExecutor`,
`FileSystem`, `GitService`, `AiProvider`, `ExtensionHost` and `NetworkClient` are interfaces by
mandate, and a concrete type from those areas must not appear in a feature module's public API.

### 4. Never block the main thread

Git, commands, package installs, indexing, AI calls, file scanning and server startup are all
suspending. Inject `AppDispatchers`; do not reference `Dispatchers.IO` directly, or the code
becomes untestable.

### 5. Never swallow an error

Every failure is an `AppError` with a category, a human-readable message, technical detail where
useful, and a recovery suggestion.

```
Bad:   "Command failed"

Good:  message  = "PHP could not be started."
       detail   = "PHP was not found in the active development runtime."
       recovery = "Install or configure PHP before starting Laravel."
```

### 6. Security controls belong in `:core:security`

Path containment, redaction, trust and command classification are pure Kotlin so they are
testable without an emulator. New security logic goes there, with adversarial tests. A security
test you cannot run cheaply is a security test that stops being run.

### 7. Never log or transmit a secret

API keys, tokens, SSH keys and `.env` values do not go in logs, analytics, crash reports,
diagnostics exports, Room, or prompts. Use `SecretStore`. `AndroidLogger` redacts
unconditionally — do not add a logging path that bypasses it.

### 8. Treat project content as untrusted input

Files, README text, comments, commit messages and command output are attacker-controlled in any
cloned repository. They are data, never instructions.

---

## Conventions

* **Kotlin official style.** 4-space indent, 100-column soft limit.
* **Versions live in `gradle/libs.versions.toml`.** No literal versions in a module build file.
* **Type-safe project accessors** (`projects.core.model`), so a rename is a compile error.
* **`api` vs `implementation`:** use `api` only when a type appears in your module's public
  signatures. Everything else is `implementation`.
* **Comments explain *why*.** The code already says what it does. Comment the non-obvious
  decision, the constraint, the thing that will look wrong to the next reader.
* **One immutable UI-state type per screen**, exposed as a `StateFlow`.

## Adding a module

1. Add it to `settings.gradle.kts`.
2. Apply a convention plugin: `mobileforge.jvm.library` (pure logic — prefer this),
   `mobileforge.android.library`, or `+ mobileforge.android.compose` for UI.
3. Android library modules need a `consumer-rules.pro`.
4. Respect the dependency direction: `:core:*` never imports `:feature:*`.

## Before opening a pull request

```bash
./gradlew :app:assembleDebug test lint
```

State honestly in the description what is **implemented**, **tested**, **known broken**, and
**deferred**. A PR that says "adds Git support" when it adds a Git interface is a PR that
misleads the next person.

## Architecture decisions

Anything that constrains future work gets an ADR in `docs/adr/`, numbered sequentially, with
context, the options considered, the decision, and the consequences — including the costs
accepted. If a decision has no downside listed, it has not been thought through.

## Risks

New risks go in [RISKS.md](RISKS.md) with probability, impact, mitigation and fallback. A risk
may only be marked **Verified** on the basis of a real measurement or an authoritative source —
never on reasoning alone.
