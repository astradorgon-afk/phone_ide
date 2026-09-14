# ADR-009 — Structure of the execution core

* **Status:** Accepted
* **Date:** 2026-09-06
* **Affects:** `:runtime:exec`, Phase 2 onward
* **Builds on:** [ADR-002](ADR-002-runtime-strategy.md), which decided *what* mechanism to use

## Context

ADR-002 chose system-linker exec. This decides how that choice is structured in code, and the
driving constraint is uncomfortable: **the mechanism cannot be tested on the development
machine.** A Windows or Linux laptop cannot run Android binaries, has no SELinux W^X policy,
and has no `/system/bin/linker64`.

If the exec logic were written directly against `android.os.Build` and `java.io.File`, none of
it would be verifiable until an APK was on a phone. For the single most failure-prone part of
the product — where the failure mode is "nothing runs at all" — that is unacceptable.

## Decision

### 1. `:runtime:exec` is a pure-JVM module

No `android.*` import. The platform facts it needs arrive as a data class:

```kotlin
data class ExecEnvironment(
    val deviceSdkInt: Int,
    val appTargetSdk: Int,
    val primaryAbi: String,
    val filesDir: String,
    val nativeLibraryDir: String,
)
```

`AndroidRuntimeFactory` in `:app` is the only code that reads `Build.VERSION` and
`ApplicationInfo`. Everything else is a function of injected data, so every combination of API
level, target SDK and ABI is testable — including combinations no single device could produce.

### 2. Three separable concerns, three types

| Type | Responsibility | Purity |
|---|---|---|
| `ExecStrategySelector` | *How* would this path be invoked? | Pure — no I/O |
| `ShebangResolver` | Parse `#!` and ELF headers | Pure — operates on a byte array |
| `ExecCommandBuilder` | Compose both into a final argv | I/O only via `FileHeaderReader` |

Splitting them means the strategy table can be exhaustively tested without touching a disk, and
the argv construction can be tested with in-memory file headers.

### 3. Shebang handling is ours, not the kernel's

This is the non-obvious consequence of ADR-002 and the one most likely to be missed.

Under system-linker exec, the kernel is handed `/system/bin/linker64` — **it never sees the
script**, so its `#!` handling does not run. Since `composer`, `artisan`, `npm` and most of a
Termux `bin/` are scripts, an implementation that forgot this would fail on nearly everything
with a baffling ELF error.

So `ExecCommandBuilder` does what the kernel would have: reads the interpreter line and execs
the *interpreter*, with the script as an argument. It matches the kernel deliberately, including
the 127-byte truncation limit and the refusal to split past the first space — a script that
behaves differently inside the IDE than in a shell is a debugging nightmare.

Nested interpreter scripts are refused, as Linux refuses them, rather than resolved recursively.

### 4. `ProcessLauncher` is an interface

`DefaultProcessManager` holds the behaviour that actually matters — SIGKILL classification,
bounded buffering, exit reporting, refusing to forget a live process — and none of it could be
tested against real processes. With a fake launcher, all of it is.

That is how a manager for a runtime that does not exist yet ships with 17 passing lifecycle
tests.

### 5. Failure is a value, not an exception

`ExecStrategy.Unsupported` and `AppError` carry a specific reason. The launcher translates
`error=13` into "EACCES — files in app storage cannot be executed directly on Android 10+",
because "Cannot run program" tells a user nothing and would send a contributor down the wrong
path entirely.

### 6. The probe measures; it does not infer

`SystemLinkerRuntimeProbe` reports a tool as available **only after running it and reading a
version string**. It also self-tests the mechanism (`ExecSelfTest`), so diagnostics can state
whether execution from app storage actually works on *this* device rather than assuming ADR-002
generalises.

## Verification

**On the JVM (runs today):** 64 unit tests covering strategy selection across API/target/ABI
combinations, argv construction for binaries and scripts, shebang edge cases, ELF type
detection, output eviction, and process lifecycle including OS-kill classification.

**On a device (`ExecMechanismVerificationTest`):** copies `/system/bin/sh` — a real dynamically
linked ELF that exists everywhere — into app storage and:

* asserts direct execution is **blocked**, proving the constraint is real on that device;
* asserts execution **through the linker succeeds**, proving the workaround works.

No bootstrapped toolchain is required, so this runs on any device from the moment the app
installs. If the first assertion ever stops holding, the workaround has become unnecessary; if
the second fails, that device needs ADR-002's fallback chain. Either way the test says so.

## Consequences

* An extra indirection (`ExecEnvironment`) that a device-coupled design would not have. It buys
  testability of the least testable part of the product; that is a good trade.
* `ProcessBuilder` gives pipes, not a TTY. Correct for builds and servers, **insufficient for
  the interactive terminal**, which needs a real PTY via the NDK. That is tracked as Phase 2b
  rather than faked with pipes — a "terminal" without a TTY cannot do line discipline, job
  control or `isatty()`, and shipping one would be exactly the fake this project refuses.
* The bootstrap payload (actual PHP/Node/Git binaries) is a separate problem: sourcing,
  licensing, ABI and 16 KB page-size compliance. Also Phase 2b.
