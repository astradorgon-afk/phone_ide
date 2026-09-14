# MobileForge IDE — Roadmap

Phases ship in order. A phase is done when its features are **built, tested and verified** —
not when the code exists. Anything not listed under Phase 1 does not exist in this build.

---

## Phase 0 — Architecture ✅ Complete

- [x] Environment inspected and recorded (JDK, SDK, NDK, Gradle, device availability)
- [x] Gradle multi-module structure, convention plugins, version catalogue
- [x] Runtime strategy researched against authoritative sources **before** committing to it
- [x] ADRs 001–008
- [x] Risk register (15 risks)
- [x] Build verified: `assembleDebug` produces an APK

## Phase 1 — Android IDE shell 🟢 Complete; device-verified except the editor

- [x] Compose application shell, adaptive at a 720 dp breakpoint
- [x] Navigation graph; no god Activity
- [x] Project manager: create, open, remove, recent list, framework detection
- [x] Workspace trust model, enforced (untrusted ⇒ read-only)
- [x] File explorer: lazy per-directory loading, dotfile toggle, excluded-directory dimming
- [x] Monaco editor in a WebView with a narrow, validated bridge
- [x] Atomic file save (temp → fsync → rename)
- [x] Path containment: logical validation plus real-path (toRealPath) symlink check
- [x] Secret redaction at the logging boundary
- [x] Settings (theme, editor, explorer) + diagnostics screen
- [x] Keystore-backed secret storage (built now; nothing stored yet)
- [x] Contracts for the runtime and AI subsystems, with an honest "not implemented" probe
- [x] Real project scaffolding (static-site and PHP templates write actual files)
- [x] 117 unit tests: path traversal, symlink escape, redaction, command risk, trust,
      atomic writes, framework detection, language mapping

**Open before Phase 1 is signed off**
- [x] Run on an Android 11+ runtime — done on an emulator; see the device-verification
      section below
- [ ] Confirm on physical **arm64** hardware
- [x] **Monaco verified on Android 14 / Chromium 113** — boots and round-trips content
      (`MonacoRenderVerificationTest`)
- [ ] Monaco memory footprint and IME/touch behaviour on a real phone remain unmeasured
      (RISK-003); an emulator with a hardware keyboard does not answer this
- [x] Compose UI tests for navigation and the trust dialog — `NavigationAndTrustUiTest`, 4 tests
      on device (2026-09-10). Covers both dialog answers: "Open restricted" leaves the project
      read-only with no save affordance and stored trust unchanged, "Trust project" clears the
      banner and persists. Trust is read back from the database, not the screen.

## Phase 2a — Execution core 🟢 Built, unit-tested and VERIFIED ON DEVICE

- [x] `:runtime:exec` — pure-JVM module (ADR-009)
- [x] System-linker exec: strategy selection across API level, target SDK and ABI
- [x] Shebang resolution, matching the kernel (127-byte cap, single unsplit argument,
      nested-interpreter refusal) — required because the kernel never sees the script
- [x] Static-vs-dynamic ELF detection, so an unloadable binary is named rather than exploding
- [x] `/proc/self/exe` shim via `TERMUX_EXEC__PROC_SELF_EXE` — **corrected 2026-09-11.** This
      was marked done when only the environment variable was being set; nothing read it, so the
      shim did nothing. Node proved it: `process.execPath` reported the linker. `libmfexec` now
      intercepts `readlink`/`readlinkat` and answers with the real program path
- [x] `$PREFIX` / `$HOME` / `$PATH` / `$LD_LIBRARY_PATH` environment assembly
- [x] `DefaultProcessManager`: lifecycle, bounded output, OS-kill classification (exit 137)
- [x] Foreground service so user-started servers survive backgrounding
- [x] Port allocation with reconciliation for OS-killed owners
- [x] `SystemLinkerRuntimeProbe` — measures, never infers; replaces the Phase 1 stub
- [x] 74 unit tests in `:runtime:exec`
- [x] `ExecMechanismVerificationTest` — on-device proof of both the constraint and the workaround

**Gate CLEARED — verified on device 2026-09-06.**

`ExecMechanismVerificationTest` was run on an Android 11 emulator (API 30, x86_64, app
targeting API 35). Measured, not inferred:

```
device=API 30  target=API 35  abi=x86_64  wx=true  linker=/system/bin/linker64
system exec  -> Succeeded(mobileforge_exec_ok, exit 0)
direct exec  -> Failed: error=13, Permission denied      <- the W^X constraint is real
linker exec  -> Succeeded(mobileforge_exec_ok, exit 0)   <- the workaround works
```

Re-run any time with `bash tools/verify-runtime.sh` (or `.\tools\verify-runtime.ps1` on
Windows PowerShell, where plain `bash` resolves to the WSL launcher rather than Git Bash).

Still to verify on hardware:
- [ ] Confirm on a physical **arm64-v8a** device. The mechanism is ABI-independent in
      principle, but the measurement above is x86_64 only.

## Phase 1 device verification — CLEARED 2026-09-06

Phase 1 had never run on a device. It now has, on the same emulator:

- [x] App installs, launches, `MainActivity` resumes, no crashes
- [x] Project creation, with real scaffolded files on disk
      (`index.html`, `styles.css`, `script.js`, `README.md`)
- [x] Framework detection correctly identified the project as a Static site
- [x] File explorer lists and sorts correctly; adaptive phone layout applied
- [ ] **Editor rendering is NOT confirmed** — see the WebView finding below

Two real bugs were found by running it, neither reachable by unit tests:

1. **CSP blocked Monaco's inline bootstrap script**, so the editor silently never initialised.
   Fixed by moving it to `bootstrap.js` rather than adding `'unsafe-inline'` to `script-src` —
   that page renders untrusted project content (RISK-010), so weakening the policy was the
   wrong trade.
2. **The emulator's WebView is Chromium 83** (stock AOSP, 2020) and Monaco 0.52 needs ~94+.
   The editor now detects this and explains it instead of showing a blank pane. See RISK-003.

## Phase 2b-i — Terminal engine 🟢 Built and VERIFIED ON DEVICE

- [x] `:runtime:pty` — native `forkpty` layer, built for arm64-v8a, armeabi-v7a and x86_64
      with `-Wall -Wextra -Werror` (ADR-010)
- [x] Controlling terminal, line discipline, `TIOCSWINSZ`, signals to the process **group**
- [x] `TerminalEmulator` — pure-Kotlin VT100/xterm subset, 38 unit tests
- [x] `TerminalSession` — PTY + emulator + coroutine plumbing, bounded scrollback
- [x] A missing native library is reported as a capability gap, never a crash
- [x] **On-device verification (`PtyVerificationTest`, API 30 / x86_64, all passing):**
      `[ -t 0 ]` reports a TTY, input is echoed, `stty size` reflects a resize, Ctrl+C
      interrupts `sleep 30` through the process group, and exit status propagates

## Phase 2b-ii — Terminal UI 🟢 Working, VERIFIED ON DEVICE

- [x] `:feature:terminal` — Compose terminal rendering `TerminalUiState`
- [x] ANSI palette in the design system: colours are indices mapped onto the app theme, so
      terminal output sits in the same visual system as the editor
- [x] Runs of identically-styled cells merged into single spans (a span per character would
      be ~2000 spans per screen and visibly stutters while scrolling)
- [x] Cursor drawn as an inverted cell, so it stays aligned at any font size
- [x] Auto-scroll that follows output **only when already at the bottom**
- [x] Key row for what a soft keyboard lacks: Ctrl+C, Ctrl+D, Tab, Esc, arrows, Ctrl+L, pipe
- [x] Grid measured from the font and reported to the PTY via `TIOCSWINSZ`
- [x] Third pane in the workspace shell; toolbar toggle on wide layouts
- [x] Shell resolution through `ExecCommandBuilder`, falling back to `/system/bin/sh` and
      saying so, rather than erroring when no toolchain is installed
- [x] **Verified on device:** `pwd` returns the project directory, `uname` returns `Linux`,
      commands echo, the prompt redraws, scrollback scrolls

**Remaining**
- [x] Multiple sessions and session switching — tab strip, capped at 4 (each session is a real
      process and PTY). 9 unit tests for bookkeeping plus `TerminalSessionsVerificationTest` on
      device, which proves the part a JVM test cannot: two sessions are separate shells whose
      output and working directories do not leak into each other, and closing ends the
      process (2026-09-10)
- [x] Hardware-keyboard modifier handling — Ctrl+letter to control characters, Ctrl+C as a
      signal rather than a byte, Alt as Meta (ESC prefix), cursor/Home/End/paging/function key
      sequences. Intercepted before the text field, so Ctrl+C no longer types "c". 14 unit tests
      in `HardwareKeyMapTest`, several asserting keys are deliberately NOT claimed — consuming
      an ordinary letter would stop the user typing at all (2026-09-11)
- [x] Streaming UTF-8 decoder — preserves multi-byte characters across reads and flushes
      truncated input at PTY close; 7 regression tests pass (2026-09-10)
- [x] Scroll regions (`DECSTBM`) — region-aware scrolling and line insertion/deletion;
      16 regression tests pass (2026-09-10)
- [x] Alternate screen buffer (47/1047/1048/1049), origin mode (DECOM) and cursor visibility
      (DECTCEM) — 32 unit tests, plus `TerminalTuiVerificationTest`, which drives Android's own
      `vi` through a real PTY and confirms the buffer switches and restores (2026-09-10)
- [x] Unicode cell widths — wide CJK, Hangul and emoji occupy two columns; combining marks
      occupy none; surrogate pairs form one cell. 28 unit tests plus a device test driving a
      real shell'''s UTF-8 through a PTY (2026-09-10). Width table is a documented subset of
      UAX #11; ambiguous-width characters are treated as narrow
- [ ] Broader TUI compatibility beyond `vi` (mouse reporting, bracketed paste, `htop`-class
      programs) remains unverified

## Phase 2b-iii — Toolchain 🟡 Real software builds and runs; PHP/Node/arm64 outstanding

- [x] `:runtime:toolchain` — manifest, compatibility, integrity, install pipeline (ADR-011)
- [x] Verify-before-extract, extract-to-staging, Zip Slip rejection, merge-not-replace
- [x] ABI, prefix and 16 KB page-size compatibility checks with actionable reasons
- [x] `source_url` a REQUIRED manifest field, so the GPL source obligation cannot be forgotten
- [x] **`libmfexec`** — an `LD_PRELOAD` `execve()` interposer, without which a shell cannot
      execute anything we install (found on device, not predicted)
- [x] `useLegacyPackaging = true`, required for `LD_PRELOAD` to resolve a real path
- [x] `mf-doctor` — a real NDK-built PIE ELF, packaged for arm64-v8a / armeabi-v7a / x86_64,
      with `tools/build-bundle.sh` as the reference packaging implementation
- [x] 14 installer unit tests
- [x] **On device:** installed and executed via linker-exec; tampered bundle refused; the
      shell runs it only with the interposer; typing `mf-doctor` in the app's terminal prints
      its self-check

**Established:** Termux packages **cannot be reused.** They bake
`/data/data/com.termux/files/usr` into binaries at build time and are not relocatable, and we
cannot write to another app's prefix. Bundles must be built against our own prefix.

- [x] **A termux-packages build with the prefix repointed** — `TERMUX_APP__PACKAGE_NAME` set to
      `dev.mobileforge`, giving `/data/data/dev.mobileforge/files/usr`. The build refuses
      upstream prebuilt dependencies because the package name differs, so the whole dependency
      tree is compiled from source. `tools/termux-build.sh` persists build state across
      containers; `tools/package-termux-bundle.sh` converts `.deb` output into our bundle format
- [x] **A real third-party package installed and executed on device** — `tree`, chosen because
      it has a genuine shared-library dependency. `RealToolchainVerificationTest` proves install,
      linker-exec through `libmfexec`, real output, and `DT_RUNPATH` resolution *without*
      `LD_LIBRARY_PATH` (2026-09-11)
- [x] Toolchain installer UI — `ToolchainScreen` drives `ToolchainManager` with SHA-256
      verification and progress
- [x] `libmfexec` sets `TERMUX_EXEC__PROC_SELF_EXE` (`mfexec.c`)

**Remaining**
- [x] **Git 2.55.0** — built, installed and verified on device: real commit created,
      symlinked subcommands run, PATH-based exec reaches it (`GitToolchainVerificationTest`).
      Unblocks Phase 4 (2026-09-11)
- [x] **PHP 8.5.1** — built, installed and verified on device: evaluates code, loads its
      extensions from the compiled-in path, runs a script from disk
      (`PhpToolchainVerificationTest`). Unblocks Phase 3 (2026-09-11)
- [x] **Node + npm** — `nodejs-lts` 24.18.0 on x86_64, `nodejs` 26.4.0 on aarch64. 26.4.0 fails
      to compile *for x86_64* (`roots.h:504: error: expected identifier` in a V8 macro
      expansion) but cross-compiles cleanly for aarch64, so the two architectures carry
      different versions. npm 11.19.1 verified on device; it needed coreutils (`env`),
      `PROVIDE_SH=bash` for `$PREFIX/bin/sh`, and symlink targets added to the executables list
      (2026-09-11)
- [ ] Reconcile the Node version split between architectures
- [x] **arm64-v8a: tree, git and PHP** — built and packaged; bundles pass the ELF machine
      check with the same dependency closures as x86_64 (14 for git, 33 for PHP). Node pending
- [~] **arm64-v8a builds** — `tree` built and verified at the artifact level (ARM
      aarch64 ELF, dynamically linked, correct RUNPATH). Packaging refuses a bundle whose
      binaries are not the declared architecture, tested in both directions. Build state is
      per-architecture, because the symlinked prefix defeats termux's own arch isolation
- [ ] **Execute an arm64 bundle on physical hardware** — cannot be done here: the emulator is
      x86_64 and an arm64 AVD needs full-system emulation. arm64 bundles are built and
      structurally verified, never run
- [x] **Per-component licence and source recorded** — manifests carry a `components` list
      (name, version, SPDX licence, source URL) for every package in the bundle, not just the
      headline tool. The obligation attaches to each component separately; PHP's 33 span GPL-2.0
      through MPL-2.0. Packaging warns on any non-SPDX licence (2026-09-11)
- [ ] Resolve `libicu (custom)` — ICU's Unicode licence, flagged by the packaging warning
- [x] **`base` bundle split out** — shell and coreutils ship once; tool bundles declare
      `requires: [base]` and the manager refuses to install without it. `tree` went from 19 MB
      to 56 KB, php 58 → 49 MB, node 56 → 41 MB, git 31 → 20 MB. 7 unit tests on the
      requirement check (2026-09-11)
- [x] **Package contamination fixed** — prefix persistence reverted; it made termux attribute
      other packages' files to whatever was rebuilt (an `openssl` .deb with 1453 apache2 files).
      123 suspect packages quarantined; shared-library and test-fixture guards added (2026-09-11)
- [ ] Source-offer hosting: the URLs are recorded, but nothing serves the sources yet
- [ ] Bundle hosting + download, resume and signature verification
- [ ] `posix_spawn` interception, if any packaged tool turns out to need it
- [ ] Restore the `warningsAsErrors` decision (ADR-007)

> The chain from keystroke to a real, dynamically-linked third-party binary now works end to
> end. What is missing is the software developers actually need — PHP and Node — and any build
> at all for arm64.

## Phase 3 — Laravel 🟡 Logic built and unit-tested; nothing verified on device yet

- [ ] Laravel project creation via Composer — **blocked**: Composer is still building
- [~] Artisan integration — `ArtisanCommand` (parsing, validation, risk gating) and
      `ArtisanRunner` (no shell; refuses an unconfirmed destructive command) built with 14 unit
      tests. Not yet wired to UI, not yet run against a real project
- [ ] Local server + WebView live preview
- [x] **Real, executed versions** — `LaravelVersions` runs `php --version` and
      `php artisan --version` rather than reading composer.json, which declares what a project
      *wants*, not what is installed. Every field is null when the tool is absent, exits
      non-zero, or answers in an unrecognised shape — an honest gap, never a plausible number.
      9 unit tests (2026-09-11)
- [x] **`.env` editor logic** — `EnvFile` parses, masks and round-trips. 18 unit tests, most of
      them on the two ways it could do harm: silently reformatting a hand-maintained file, and
      showing a secret. `APP_KEY=base64:ab#cd` keeps its `#`; the mask is fixed-width so it does
      not leak length; `render()` can never write the mask over a real secret (2026-09-11)
- [ ] `.env` editor UI

## Phase 4 — Git

- [ ] init, clone, status, add, commit, branch, checkout, diff, log, stash, remotes
- [ ] Clone flow with URL validation
- [ ] OAuth device-code auth; tokens in Keystore only, never a password
- [ ] Editor gutter decorations for changed lines

## Phase 5 — AI

- [ ] Provider adapters: Anthropic, OpenAI, Gemini, OpenRouter, Ollama, OpenAI-compatible
- [ ] BYOK with capability detection
- [ ] Streaming responses
- [ ] Tool execution behind the permission engine
- [ ] Bounded context assembly with secret redaction
- [ ] Diff review UI; checkpoints
- [ ] Provider-reported usage and cost only

## Phase 6 — External agents

- [ ] OpenCode adapter (via its client/server interface, not its internals)
- [ ] Claude Code / Codex / Aider detection with actionable incompatibility reasons

## Phase 7 — LSP

- [ ] `LspManager`, decoupled from Monaco
- [ ] PHP (Intelephense or phpactor), then JS/TS, HTML/CSS/JSON
- [ ] Per-project, per-language opt-in

## Phase 8 — Extensions

- [ ] Native extension host, manifest and permission model
- [ ] `.vsix` inspector with specific compatibility verdicts
- [ ] Web-model extension support where feasible

## Phase 9 — Advanced

- [ ] Local models, multi-agent sessions, project indexing, advanced Git, per-project toolchains

---

## Release gates

These block any store release regardless of phase:

- [x] **`compileSdk`/`targetSdk` → 36**, Gradle 8.14.3, AGP 8.13.0 (ADR-007). Confirmed in the
      built artifact: `aapt2 dump badging` reports `targetSdkVersion:'36'`. This no longer
      blocks publication.
- [ ] Release signing configuration
- [ ] R8 rules validated (`assembleRelease` is not a supported target today)
- [ ] Security review against SECURITY.md
- [ ] Third-party licence attribution bundled in-app (Monaco is MIT)
