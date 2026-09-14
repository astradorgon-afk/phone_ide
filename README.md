# MobileForge IDE

A mobile-first Android development environment for web and Laravel/PHP work.

> **Status: IDE shell, terminal and a real toolchain working on x86_64.**
> You can create and open projects, browse files, edit code in Monaco (verified on
> Android 14 / Chromium 113), and run **git 2.55, PHP 8.5.1 and Node 24.18 LTS** in a real PTY
> terminal — built from source against this app's own prefix and verified on device, including
> `php` typed by name at the prompt.
>
> **Limits worth knowing before you rely on it:** arm64 bundles are built and structurally
> verified but have never been *executed*, because no arm64 hardware or emulator was available;
> editor ergonomics on a real phone (memory, IME, touch selection) are unmeasured; there is no
> bundle hosting or download yet, so toolchains are installed from a local file; and **AI agents
> remain unimplemented**. See [STATUS.md](STATUS.md) for the full verification record.
>
> Nothing in this app pretends to work. Where a subsystem is absent, the UI says which phase
> delivers it — there are no "coming soon" buttons that throw.

---

## What works today

| | |
|---|---|
| **Projects** | Create, open, remove; recent list; framework detection from on-disk evidence |
| **Trust** | Untrusted projects open read-only; trust is asked before the project opens |
| **Explorer** | Lazy per-directory loading, dotfile toggle, `node_modules`/`vendor` dimmed |
| **Editor** | Monaco, bundled offline (needs a Chromium 94+ WebView; the app detects and reports otherwise); PHP, Blade, JS/TS, HTML, CSS, JSON, YAML, Markdown, SQL, Bash, XML, Kotlin |
| **Keyboard** | Hardware Ctrl/Alt/function keys mapped to terminal sequences; Ctrl+C sends a signal, not a byte |
| **Saving** | Atomic (temp → fsync → rename); a crash mid-save cannot truncate a source file |
| **Security** | Path containment incl. symlink escape, secret redaction, hardened WebView bridge |
| **Terminal** | Working interactive shell: real PTY, echo, Ctrl+C to the process group, resize, scrollback, touch key row |
| **Processes** | Start/stop/restart, bounded output, OS-kill detection, foreground service, port allocation |
| **Toolchain** | git 2.55.0, PHP 8.5.1, Node 24.18.0 LTS — built for this app's prefix, installed and run on device (x86_64); arm64 built but not executed |
| **Tests** | 540 unit tests and 46 instrumented tests on Android 14, all passing |
| **Settings** | Theme, editor options, explorer options, diagnostics |

## What does not work yet

PHP/Composer/Node/npm/Artisan/Vite, local servers, live preview, Git, AI providers
and agents, LSP, and extensions. These have designed contracts (`:runtime:api`, `:ai:api`) and
no implementations. The diagnostics screen reports each as *not implemented*, with its phase.

---

## Build

**Requirements**

| | |
|---|---|
| JDK | 17 |
| Android SDK | platform 35, build-tools 34.0.0+ |
| Gradle | supplied by the wrapper (8.10.2) |
| Node/npm | only to fetch Monaco once |

```bash
bash tools/fetch-monaco.sh
```

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew test
```

On-device verification of the execution mechanism (needs a device or emulator on ADB):

```bash
.\tools\verify-runtime.ps1
```

On Windows PowerShell use the wrapper instead — plain `bash` resolves to the WSL launcher,
not Git Bash:

```bash
.\tools\verify-runtime.ps1
```

`local.properties` must point at your SDK. On Windows, use forward slashes —
`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`. A backslash path silently breaks, because
`\U`, `\A` and `\L` are not valid Java properties escapes.

`assembleRelease` is **not** a supported target yet: there is no signing config and the R8
rules have not been validated.

---

## Device support

**minSdk 30 (Android 11).** This is a product decision, not a default.

The Phase 2 development runtime depends on executing binaries through the system linker, which
is viable on Android 11 and above. Shipping to older devices would mean shipping an IDE that
can never run PHP — a text editor sold as a development environment. See
[ADR-002](docs/adr/ADR-002-runtime-strategy.md).

---

## Architecture

17 Gradle modules. Domain and security logic are **pure-JVM** so they cannot depend on the
Android framework and their tests run in milliseconds without an emulator.

```
:app                     composition root, navigation
:core:model              pure domain types
:core:common             AppResult, dispatchers, logging
:core:security           path containment, trust, redaction, command risk   [pure JVM]
:core:filesystem         SAF/internal storage, atomic writes
:core:data               Room, DataStore, Keystore
:core:designsystem       Compose theme and shared components
:feature:projects        project list, create, trust
:feature:workspace       adaptive shell + file explorer
:feature:editor          Monaco WebView + bridge
:feature:settings        settings + diagnostics
:feature:terminal        terminal UI over the PTY engine
:runtime:api             process/runtime contracts
:runtime:pty             native PTY, terminal emulator, exec interposer (ADR-010, ADR-011)
:runtime:toolchain       bundle manifest, integrity, install pipeline (ADR-011)
:runtime:exec            execution core — linker strategy, shebang, process lifecycle
:ai:api                  provider/agent contracts     [contracts only in Phase 1]
```

Full detail in [ARCHITECTURE.md](ARCHITECTURE.md).

## Documentation

| Document | Contents |
|---|---|
| [STATUS.md](STATUS.md) | **Start here** — what works, what does not, and what remains |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Structure, module rationale, layering rules |
| [SECURITY.md](SECURITY.md) | Threat model and which controls are actually implemented |
| [RISKS.md](RISKS.md) | 15 tracked risks with probability, impact, mitigation, fallback |
| [ROADMAP.md](ROADMAP.md) | Phases 0–9 and the release gates |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Conventions and the rules that are not negotiable |
| [docs/adr/](docs/adr/) | ADR-001…011 |

**Start with [ADR-002](docs/adr/ADR-002-runtime-strategy.md)** if you only read one. It explains
why running developer tooling on modern Android is hard, what actually works, and why the whole
architecture is shaped around it.

---

## Third-party components

| Component | Licence | Why |
|---|---|---|
| [Monaco Editor](https://github.com/microsoft/monaco-editor) | MIT | Editor engine. Fetched by `tools/fetch-monaco.sh`, bundled for offline use. |
| AndroidX / Jetpack Compose | Apache-2.0 | UI, persistence, lifecycle |
| kotlinx.coroutines / serialization | Apache-2.0 | Concurrency, bridge protocol |

MobileForge is not Visual Studio Code and is not affiliated with Microsoft. It does not include
proprietary VS Code code and does not claim VS Code extension compatibility. See
[ADR-006](docs/adr/ADR-006-extension-architecture.md).
# phone_ide
