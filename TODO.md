# MobileForge IDE — Prioritised TODO

Compiled 2026-10-27 from ROADMAP.md, STATUS.md, RISKS.md, SECURITY.md and a pass over the code.
Ordered most important → least. Numbers are a single global ranking.

**Effort:** S = under a day · M = days · L = weeks or more.
**Evidence:** `[code]` = I confirmed it in the source on this pass · `[docs]` = taken from the
project's own docs and not re-checked.

**What this pass did not do:** build or run anything. There is no Android SDK in this container,
so no test, lint or device result below is new. "Confirmed in code" means read, not executed.

---

## P0 — Needed before anyone other than the author can use the app

### 1. Run the whole toolchain on the Galaxy A50 (arm64) — M
Every arm64 bundle (base, php, composer, nginx, opencode) is built and structurally verified but
**has never been executed.** All execution proof is the x86_64 emulator. `[docs]` STATUS, ROADMAP
Phase 2a/2b-iii/3.
- Install Base → PHP → Composer from the catalog, run the Laravel wizard, Artisan, `php artisan
  serve`, nginx + php-cgi, opencode.
- opencode's `poll → ppoll` fix was proven on x86_64 only. arm64 Linux has no legacy `poll` /
  `open` / `stat` syscalls at all, so the aarch64 shim is likely a different code path that the
  x86_64 result does not cover. `libsigsys-shim-aarch64.so` is checked in but unexercised.
- Re-confirm on the phone that the terminal Send key writes `0x0D` into opencode. The byte-level
  proof could not be taken on the emulator.
- `bash tools/verify-runtime.sh` on hardware (ROADMAP "Confirm on physical arm64").

### 2. Enforce workspace trust on the terminal, Artisan and Serve — S–M  `[code]`
**The trust policy exists and is unit-tested, but nothing consumes it.** `allowsExecution`,
`TrustPolicy` and `CapabilityCeiling` are referenced only from tests. `MobileForgeNavHost.kt:267-281`
builds the shell and `TerminalViewModel` with no trust condition, and `ArtisanViewModel` /
`ServeViewModel` take no trust input. So an "Open restricted" project can still open a shell, run
Artisan, start a server, or launch opencode.

SECURITY.md §6 says untrusted ⇒ "Run commands ❌" and RISK-011 says "enforced from Phase 2". Only
file writes are gated (via the read-only filesystem). Low practical exposure today because there
is no in-app clone, but it must land **before Git clone (#7)** and any AI tool use (#11).
Add a UI test alongside `NavigationAndTrustUiTest`.

### 3. Put git and node/npm in the download catalog — S–M  `[code]`
Git 2.55.0, Node and npm 11.19.1 are built and device-verified, but `catalog.json` lists only
base, php, composer, opencode, nginx, and `REQUIRED_TOOLCHAIN_IDS` is `base, php, composer`. A
user installing from the app **cannot get git or node.** This blocks Phase 4 and criterion 14.
- Package/refresh the git and node bundles for both ABIs and add them to both catalogs.
- Reconcile the Node split: 24.18 LTS on x86_64, 26.4 on arm64 (V8 `roots.h` compile error on
  x86_64). `tools/nodejs-llvm21-x86_64.patch` exists; STATUS.md never records whether it fixed
  this. `[docs]`

### 4. Sign the bundles — M  `[docs]`
A manifest checksum proves an archive matches *its manifest*, not who published it, and both come
from the same host. Whoever controls that host can ship arbitrary native code that runs in every
user's app sandbox. Needs a signature checked against a key pinned in the APK (ADR-011 currently
says "treat the source URL as the trust boundary"). **Do this before #5 goes public.**

### 5. Host the bundles, the GPL sources, and clear the licence flag — L  `[docs]`
- `base_url` is `https://bundles.mobileforge.dev`, which does not exist. A release build downloads
  nothing. `tools/publish-bundles.ps1` and `docs/bundle-hosting.md` already describe the process.
- Source URLs are recorded per component, but nothing serves the sources. This is a real
  obligation for the GPL/LGPL components (PHP's 33, git, base's 50).
- `libicu` is flagged "custom" (Unicode licence) and needs a manual review.
- No download resume yet (see #18).

### 6. Check Google Play's policy on downloading native executables — S (research)
**Not in RISKS.md.** The whole toolchain is native code downloaded at runtime and executed.
Play has historically restricted apps from fetching executable code from outside Play. I have not
checked the current policy text, so treat this as "verify", not "blocked". It is cheap to find
out and it decides the distribution model (Play vs. F-Droid / direct APK), which in turn
decides how much of #5 matters.

---

## P1 — Core features the acceptance criteria still list as missing

### 7. Git inside the IDE (Phase 4) — L  `[code][docs]`
No Git module exists; only `FrameworkDetector` mentions git. Git itself works in the terminal.
- init, clone (with URL validation), status, add, commit, branch, checkout, diff, log, stash, remotes.
- Auth: OAuth device-code flow, tokens in Keystore only, never a password (RISK-014).
- Editor gutter decorations for changed lines.
- Criteria 15–18 (15–17 partial via terminal, 18 diffs not started).
- Depends on #2 (trust) and #3 (git in the catalog).

### 8. In-app preview of the served app — M  `[code][docs]`
`php artisan serve` and nginx + php-cgi run and are verified (`ServeVerificationTest`,
`NginxServeVerificationTest`), but there is no WebView preview in `:feature:workspace`; you reach
the server only via `adb forward`. Criterion 13; ROADMAP Phase 3 "Local server + WebView live
preview". The preview WebView renders project output, so it needs the same hardening as the
editor WebView (RISK-010), not a copy of its settings.

### 9. OpenCode as a first-class agent (start of Phase 6) — M  `[code][docs]`
opencode boots and draws its TUI inside the terminal, and nothing else. `AgentAvailability`
still has a `NotImplementedYet` case; there is no detection/status row, no launch button, and no
way to supply a provider key (the Keystore is not wired to opencode's config; `models.dev` was
unreachable in the emulator test). ROADMAP has no entry for opencode, `sigsys-shim` or the musl
loader. This is the closest route to AI on the phone, so it is ranked above the built-in agent.
Also do Claude Code / Codex / Aider detection with actionable "incompatible because…" reasons.

### 10. Editor on a real phone — M  `[docs]`
Monaco now renders on the A50 and takes keyboard input, but memory footprint, IME behaviour and
touch selection are unmeasured (RISK-003, RISK-009). Also decide what happens on devices with a
WebView older than Chromium 94: today the editor is replaced by a notice. The CodeMirror 6
fallback named in ADR-003 is not built.

### 11. Built-in AI (Phase 5) — L  `[docs]`
`:ai:api` is contracts only. Provider adapters (Anthropic, OpenAI, Gemini, OpenRouter, Ollama,
OpenAI-compatible), BYOK with capability detection, streaming, tool execution behind the
permission engine, bounded context with secret redaction, diff review and checkpoints,
provider-reported usage/cost only. Criteria 19–25. Depends on #2.

---

## P2 — Hardening and known gaps

### 12. Continuous integration — S–M  `[code]`
There is no `.github/` directory and no CI of any kind. 42 unit-test files and 21 instrumented
ones exist. At minimum run unit tests and lint on every push; the `Aligned16KB` lint error is a
real guard (it caught a shipped bug) and should not depend on someone remembering to run it.

### 13. Install guard rails at execution time — M  `[docs]`
`composer install` / `npm install` run arbitrary lifecycle scripts (RISK-015). The classifier
exists; the `--no-scripts` / `--ignore-scripts` defaults and the "show the exact command"
confirmation are not wired to a real install path. SECURITY.md §10 lists this as not implemented.
The Laravel wizard runs `composer create-project` with scripts, behind a consent screen.

### 14. Security review + ADR for the SIGSYS shim and musl loader — M  `[code][docs]`
`tools/sigsys-shim` traps seccomp `SIGSYS` and re-issues legacy syscalls from inside the app. It
is also in every opencode process. `sigsys` / `musl` appear only in STATUS.md — no ADR, nothing
in SECURITY.md. Needs a written threat assessment, and `MF_SIGSYS_LOG` documented.

### 15. Terminal compatibility beyond `vi` and opencode — M  `[docs]`
Mouse reporting, bracketed paste (recognised but not implemented), `htop`-class programs.
Resize reflow is deliberately not implemented (`TerminalEmulator.kt:538`). Also the font fallback
problem behind the row-height bug suggests shipping a bundled monospace font with box-drawing
glyphs rather than relying on the system fallback face.

### 16. Survive and measure memory pressure — M  `[docs]`
OS-kill classification (exit 137) is unit-tested; it has not been exercised under real memory
pressure (RISK-002). Memory with Monaco + PHP + a server + opencode on a mid-range phone is
unmeasured (RISK-009).

### 17. Run on a 16 KB-page device — S once hardware exists  `[docs]`
The emulator uses 4 KB pages. The alignment lint guard exists, but no 16 KB device has ever
loaded `libmfexec` / `libmfpty` or any bundle (RISK-004).

### 18. Smaller toolchain items — S–M each  `[docs]`
- Resumable bundle downloads.
- `posix_spawn` interception, only if a packaged tool turns out to need it.
- Process isolation between concurrent workspaces (SECURITY.md §10).
- Remaining deprecation warnings (`KeyboardOptions`, WebView `databaseEnabled`).

---

## P3 — Docs and UI text that contradict the code (cheap; the project's own rule is "nothing pretends")

### 19. Wrong "not implemented" text in the app — S  `[code]`
- `Diagnostics.kt:87-92` reports **"Interactive terminal — Not implemented"** and
  **"Toolchain — binaries are not bundled yet"**. Both are false; the terminal and toolchain
  work.
- `SettingsScreen.kt:149-154` shows build `0.2.0-phase2a` and "PHP, Node and Git are not bundled."
- `SetupScreen.kt` / `SetupViewModel.kt` "not bundled in this build — import it from…" wording,
  and the projects empty-state text that STATUS.md already calls stale.

### 20. SECURITY.md is out of date — S  `[code]`
§8 says Phase 1 requests zero permissions and `INTERNET` is not requested. The manifest now
declares `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`, plus a cleartext carve-out for
loopback. Also record the trust gap from #2 until it is fixed, and the new surfaces (downloaded
executables, shim, local servers).

### 21. STATUS / ROADMAP / RISKS drift — S  `[code][docs]`
- STATUS.md headline still says "Phases 3–9 not started" and "11 of 28 criteria", while its own
  later notes show the Laravel wizard, Artisan, `.env` editor and Serve working. Criteria 10–13
  are still ❌ in the table.
- ROADMAP.md has no entries for Serve/nginx, opencode or the shim, and Phase 3 "Local server"
  is unchecked though it is verified.
- STATUS.md still lists two things that are already done: the unpinned NDK (now pinned to
  `29.0.13113456` in `KotlinCommon.kt:32`) and the temporary `MFBOOT` traces (none remain in the
  editor assets).
- RISKS.md RISK-003 still says Monaco "has never actually rendered"; RISK-004 is still "Open".

---

## P4 — Release gates (block any store release)

### 22. Release signing configuration — S  `[code]` none exists.
### 23. Validate R8 / ProGuard rules — M  `[code]`
`proguard-rules.pro` is a one-line comment, yet `release` has `isMinifyEnabled` and
`isShrinkResources` on. `assembleRelease` is not a supported target today. The app has JNI
(`NativePty`), a serialised WebView bridge protocol and Room, all of which are typical
keep-rule candidates. That is my inference — it has not been tried.
### 24. Security review against SECURITY.md — M  `[docs]` (after #2, #14, #20)
### 25. In-app third-party licence attribution — S  `[docs]`
Monaco (MIT) at minimum, plus the toolchain components' notices.

---

## P5 — Later phases

### 26. LSP (Phase 7) — L
`LspManager` decoupled from Monaco; PHP first (Intelephense or phpactor), then JS/TS,
HTML/CSS/JSON; per-project, per-language opt-in. Needs Node in the catalog (#3).
### 27. Extensions (Phase 8) — L
Native extension host with manifest and permission model; `.vsix` inspector with specific
verdicts. Do not market VS Code compatibility (RISK-005).
### 28. Advanced (Phase 9) — L
Local models, multi-agent sessions, project indexing, advanced Git, per-project toolchains.
