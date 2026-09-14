# ADR-002 — Development runtime strategy on Android

* **Status:** Accepted
* **Date:** 2026-09-06
* **Deciders:** Architecture
* **Supersedes:** —
* **Affects:** `minSdk`, `:runtime:*`, Phases 2, 3, 6, 7, 8

## Context

MobileForge must run PHP, Composer, Node, npm, Git, Artisan and Vite on-device. That requires
executing native Linux-style binaries from an Android app. This decision is load-bearing: if
it is wrong, the product thesis collapses, so it was researched **before** any code was written.

Android is not a Linux desktop. `/bin/bash`, `/usr/bin/php` and `/usr/bin/node` do not exist,
and the paths where an app may write are not paths from which it may execute.

## The constraint

Android 10 (API 29) enforces **W^X** (write XOR execute) through SELinux policy. An app whose
`targetSdkVersion` is ≥ 29 **cannot `execve()` a file located in its own data directory**
(`/data/data/<package>/…`). A file may be writable or executable, never both.

The historical workaround was to pin `targetSdkVersion` to 28, which is what Termux did for
years. That option is closed for any app distributed through Google Play, which requires a
current target API level (API 35 for existing apps, API 36 for new apps and updates as of
31 August 2026). Pinning to 28 means no Play distribution.

## Options considered

### 1. Execute from the app data directory
Blocked by SELinux at `targetSdk ≥ 29`. **Rejected — not technically possible.**

### 2. Pin `targetSdkVersion` to 28
Works today for sideloaded builds. **Rejected** — forfeits Play distribution and is a dead end
as platform requirements advance.

### 3. Ship binaries in `nativeLibraryDir`
Files under the APK's native library directory remain executable (with
`android:extractNativeLibs="true"`), because that directory is read-only. This is the approach
the platform documentation points to.

**Limitation that disqualifies it as the primary mechanism:** binaries are fixed at build
time. Every file must be packaged as `lib*.so` inside the APK. There is no way to install a
new package at runtime, which kills `pkg install`, Composer-installed platform tools, and
per-project tool versions. **Retained as a fallback for a small, fixed core set.**

### 4. System-linker exec — **CHOSEN**
Instead of executing the target binary directly, execute the Android dynamic linker and pass
the binary as its argument:

```
execve("/system/bin/linker64", ["/data/data/dev.mobileforge/files/usr/bin/php", "-v"])
                ^ this is what the kernel sees being executed
```

`/system/bin/linker64` (or `/system/bin/linker` on 32-bit) lives on the read-only system
partition and is freely executable. The app-data file only needs to be **readable**. The W^X
policy is satisfied because the app never asks to execute a file it can write.

This is not a theory. It is the mechanism implemented by `termux-exec`
(`TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE`), and it is how **Termux ships on Google Play today
with a current `targetSdkVersion`** — the strongest possible evidence that it is viable and
maintainable under current platform and store policy.

### 5. PRoot / proot-distro
Provides a fuller Linux userland, but still needs a working `exec` primitive (so it inherits
this same decision) and adds substantial syscall-interception overhead on a phone.
**Deferred** — possible later for full-distro workflows, not for the core runtime.

### 6. Delegate to Termux via `RUN_COMMAND` intent
A third-party app can run commands inside an installed Termux by sending an intent to
Termux's `RunCommandService`. It requires **both** the `com.termux.permission.RUN_COMMAND`
permission and `allow-external-apps=true` in `~/.termux/termux.properties`.

**Rejected as a default** — it makes a separate app a hard dependency, requires manual user
configuration, and hands broad access across an app boundary. **Retained as an opt-in
integration** for users who already run Termux.

### 7. Remote / code-server style architecture
Sound, but it contradicts the offline-first requirement and the product thesis of developing
*on* the device. **Retained as a future workspace type, not as the runtime.**

## Decision

**Primary:** system-linker exec, with an abstraction (`ProcessExecutor` in `:runtime:api`)
that hides the mechanism entirely from callers.

**Fallback chain:** `nativeLibraryDir` (fixed core tools) → Termux `RUN_COMMAND` (opt-in) →
remote workspace.

## Consequences

**Accepted costs:**

* **`minSdk = 30` (Android 11).** Termux-on-Play documents Android 11+ for this mechanism.
  Supporting Android 10 or lower would ship devices where the product's core promise can never
  work — that would be dishonest, so we do not.
* **Dynamically-linked ELF only.** Statically-linked binaries cannot be linker-exec'd.
* **Shebang scripts need explicit handling** — the interpreter must be exec'd through the
  linker with the script as an argument; the kernel's own `#!` handling is not available here.
* **`/proc/self/exe` reports the linker**, not the running program. Tools that self-locate
  need an environment shim (Termux uses `TERMUX_EXEC__PROC_SELF_EXE` for exactly this).
* **Absolute paths are mandatory** — the linker will not resolve a relative path.
* Native binaries must be built against the NDK/Bionic with correct `DT_RUNPATH`, and must
  support 16 KB page sizes for newer devices (see RISK-004).

**Benefits:**

* Compatible with current and foreseeable Play target-API requirements.
* Runtime package installation stays possible, so per-project tool versions remain reachable.
* No dependency on another app, no root, no special device configuration.

## Impact on Phase 1

**None — deliberately.** Phase 1 ships `:runtime:api` as *contracts only*: `ProcessExecutor`,
`ProcessManager`, `ToolRuntime`, and a `RuntimeCapability` probe that reports
`NotImplemented` for every tool. The IDE shell, file management and editor work with zero
runtime support, so a Phase 2 setback cannot invalidate Phase 1 work.

The Phase 1 diagnostics screen reports runtime tools as **"Not installed — Phase 2"**. It does
not show a fake green check, and it does not show a button that throws.

## References

* [Behavior changes: apps targeting API 29+ — Android Developers](https://developer.android.com/about/versions/10/behavior-changes-10)
* [Termux and Android 10 — termux-packages wiki](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10)
* [Termux execution environment — termux-packages wiki](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
* [System Linker Execution — termux-exec-package](https://deepwiki.com/termux/termux-exec-package/3.3-system-linker-execution)
* [termux-play-store — Termux on Google Play](https://github.com/termux-play-store)
* [RUN_COMMAND Intent — termux-app wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
* [Target API level requirements for Google Play](https://support.google.com/googleplay/android-developer/answer/11926878)
