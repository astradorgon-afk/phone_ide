# MobileForge IDE — Status

**Baseline as of 2026-09-07; continuation notes dated 2026-09-10 below.**
A snapshot of what actually works, what does not, and what it would take
to finish. Written to be read by someone deciding whether and how to continue.

The rule applied throughout: a thing is "done" only if it was **built, tested, and — where the
claim depends on Android behaviour — measured on a device.** Nothing here is marked complete on
the strength of reasoning alone.

---

## Continuation — 2026-09-10

- Implemented incremental UTF-8 decoding in the terminal, including replacement of malformed
  input and flushing a truncated final character when the PTY closes. All 7 new regression
  tests and the existing 38 terminal emulator tests pass.
- Implemented scroll regions (`DECSTBM`), index/reverse index, explicit scrolling, and
  region-bounded line insertion/deletion. All 16 new regression tests pass. Partial-region
  redraws preserve surrounding rows and do not enter scrollback. Margins reset on resize.
- Terminal tests: **61 passing** across 3 suites. Debug APK assembly succeeded.
  Full TUI compatibility (including alternate-screen and origin modes) remains unverified.
- Final validation: `gradlew.bat --offline :runtime:pty:testDebugUnitTest :app:assembleDebug
  test lint --console=plain` completed with **BUILD SUCCESSFUL**. XML reports contain
  **281 tests across 23 distinct suites**, 0 failures and 0 errors (duplicate debug/release
  suites counted once). App lint reports 0 errors and 72 warnings; terminal lint is clean.
- Replaced navigation's `produceState` with keyed `remember` state and `LaunchedEffect`
  after lint rejected the existing producer despite its state assignment. Database loading
  remains asynchronous, and a different workspace ID starts with an empty loading state.
- Corrected the README's stale terminal and editor verification claims.
- No device is connected over ADB. Monaco rendering and physical arm64 verification remain
  open; no new device verification is claimed.

Scroll-region escape syntax was checked against the
[xterm control-sequence reference](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).

The baseline measurements below are historical; they are not a recount of this continuation.

---

## Continuation — 2026-09-10 (later session, on an Android 14 emulator)

An `android-34;google_apis;x86_64` image was installed (4.2 GB) and booted as AVD `mf34`. It
ships **Android System WebView 113.0.5672.136** — the first runtime in this project's history
above the Chromium 94 floor Monaco requires.

**Monaco is now verified.** `MonacoRenderVerificationTest` loads the bundled editor, waits for
`EditorEvent.Ready` to arrive from Monaco's own JavaScript, pushes a document, and requests it
back under a fresh request id. It passed in 11.41 s, **not skipped**. This closes the single
largest open risk in the project: until now the editor — the centre of the product and
acceptance criterion 4 — had never once been shown to run.

**Two test defects found and fixed, both of which were hiding gaps rather than causing failures:**

- `PtyVerificationTest.resizeIsAccepted` **could never pass on any device**. It probed with
  `stty size` (a GNU coreutils extension absent from Android's toybox) and guarded on a literal
  `NO_STTY` sentinel — which a PTY echoes back as part of the command line, so the guard matched
  its own echo and the test skipped unconditionally. Switched to `stty -a`, split the sentinel so
  the shell reassembles it, and widened the settle window. TIOCSWINSZ is now genuinely verified:
  the shell reports `speed 0 baud; rows 40; columns 132`.
- `:feature:workspace` used `BackHandler` without depending on activity-compose, so
  `:app:assembleDebug` failed outright. Dependency added.

**Toolchain upgraded**, which removes the Play publication blocker: AGP 8.7.3 → **8.13.0**,
Gradle 8.10.2 → **8.14.3**, compileSdk/targetSdk 35 → **36**. Verified in the built artifact,
not just the config: `aapt2 dump badging` reports `targetSdkVersion:'36'`.

Measured after all of the above:

| | |
|---|---|
| Unit tests | **410**, 0 failures, 0 errors, 0 skipped |
| Instrumented tests (API 34) | **18**, 0 failures, 1 skipped |
| Build | `BUILD SUCCESSFUL` — `test`, `:app:assembleDebug`, `connectedDebugAndroidTest` |

The one remaining skip is correct and not a gap: `basicEditorTypesAndSavesOnOldWebView` covers
the pre-Chromium-94 fallback, which cannot apply on Chromium 113. It passed on Android 11
earlier; both editor paths are therefore covered, each on a runtime where it applies.

### Terminal TUI support (same session)

Implemented the **alternate screen buffer** (DECSET/DECRST 47/1047/1048/1049), **origin mode**
(DECOM) and **cursor visibility** (DECTCEM). These were previously parsed and discarded — a
deliberate choice at the time, but it meant no full-screen program could run correctly: without
a separate buffer, redraws land in scrollback, so quitting an editor buries the shell under
thousands of stale frames.

Verified two ways, because unit tests alone only prove the emulator does what *we believe* a TUI
emits:

- **32 new unit tests** covering buffer switching, scrollback suppression, idempotent re-entry,
  resize while parked, DECSC/DECRC carrying rendition and DECOM, and region-relative addressing.
- **`TerminalTuiVerificationTest`** runs Android's real `vi` inside a real PTY and feeds its raw
  bytes to the emulator. `vi` emits `ESC[?1049h`; the emulator switches buffers, and quitting
  restores the shell's earlier output intact. Passed on device, not skipped.

`cursorVisible` is wired through `TerminalUiState` to the renderer, so a hidden cursor is
actually hidden rather than merely recorded.

### Unicode cell widths (same session)

A terminal grid is columns, not characters, and the two coincide only for ASCII. Three cases
broke that assumption, each corrupting alignment differently: wide characters (CJK, Hangul,
emoji) need two columns, combining marks need none, and emoji are surrogate pairs that were
being written into two cells as two broken halves.

`Cell` now holds a `String` rather than a `Char` — a displayed character is not always one
UTF-16 unit — plus a `continuation` flag for the right half of a double-width glyph. Code points
are assembled from surrogate pairs before reaching the screen, combining marks attach to the
cell they modify, a wide glyph wraps whole rather than straddling the right margin, and
overwriting either half of a wide character clears its partner instead of orphaning it.

Width classification is a documented subset of UAX #11 — a range table covering CJK, Hangul,
fullwidth forms and emoji — not a full property lookup. Ambiguous-width characters (box drawing,
Cyrillic) are treated as narrow, matching what a Western font does.

Verified by **28 unit tests** and, on device, by `wideCharactersFromARealShellOccupyTwoColumns`,
which has a real shell emit UTF-8 through a real PTY and asserts the grid still lines up. That
matters because the unit tests build their input from Kotlin strings and so skip byte decoding
entirely.

### Trust gate and multiple terminal sessions (same session)

**The trust gate is now verified through the UI.** It is a security control that until now had
only unit coverage — which proves the view model behaves when asked, not that the user is ever
actually asked. `NavigationAndTrustUiTest` (4 tests, on device) covers both answers to the
dialog: "Open restricted" leaves the project read-only with no save affordance and stored trust
unchanged, "Trust project" clears the banner and persists. Trust is read back from the database
rather than the screen, so persistence is what is checked.

**Multiple terminal sessions**, capped at 4. A dev server occupies its terminal for as long as
it runs, so a single-session terminal forced the user to stop the server to type anything. The
cap is not arbitrary: each session is a real process holding a real PTY.

Split verification deliberately: 9 unit tests cover bookkeeping against a launcher that refuses
to open, which is all a JVM test can do because `PtyProcess` requires Android. Those tests make
no claim about PTY release or session independence. `TerminalSessionsVerificationTest` proves
both on device — two sessions are separate shells whose output and working directories do not
leak into each other, and closing one ends its process.

### Real toolchain packaging — the mechanism now carries real software (2026-09-11)

Until now the install-and-execute chain was proven only with `mf-doctor`, a ~40-line C program
written for the purpose with no dependencies. That answered "can we exec something we
installed?" but not "can we ship real software?" — a different question, because real packages
resolve shared libraries through a `DT_RUNPATH` baked in at build time.

**A termux-packages build environment is now running with the prefix repointed**, per ADR-011:
`TERMUX_APP__PACKAGE_NAME` → `dev.mobileforge`, giving
`PREFIX=/data/data/dev.mobileforge/files/usr`. The build refuses to download upstream prebuilt
dependencies because the package name differs — the non-relocatability problem enforcing itself
— so every dependency is compiled from source.

`tree` was built first, deliberately: small, but with a real shared-library dependency
(`libandroid-support.so`), so it exercises the part most likely to be wrong. Verified in the
artifact, not the log:

```
./data/data/dev.mobileforge/files/usr/bin/tree
RUNPATH: /data/data/dev.mobileforge/files/usr/lib
ELF 64-bit LSB shared object, interpreter /system/bin/linker64, NDK r29
```

`RealToolchainVerificationTest` then proved it end to end on device — install through
`ToolchainInstaller`, execution from `/system/bin/sh` via the `libmfexec` interposer, and real
output (`tree v2.3.2 ...`). One test deliberately omits `LD_LIBRARY_PATH`: if the binary only
works because we hand it a library path, `DT_RUNPATH` is wrong and anything the user runs from
their own shell would fail. It passes without it.

**Two real defects surfaced from doing this for real rather than reasoning about it:**

- **The prefix check rejected a bundle that works.** Android exposes app storage as both
  `/data/data/<pkg>` and `/data/user/0/<pkg>`; `filesDir` reports the second, build systems bake
  the first. They are the same directory. `ToolchainCompatibility` now normalises the two forms
  — 11 unit tests cover it, and most of them are rejection cases, because the risk in fixing
  this was over-correcting into leniency. A bundle for another app, or for a `.debug`-suffixed
  id, is still refused.
- **`applicationIdSuffix = ".debug"` silently invalidated every bundle.** The prefix derives
  from the application id, so debug builds looked for a prefix no bundle is built for. Removed,
  with the reasoning recorded in `app/build.gradle.kts`: the alternative was building the whole
  toolchain twice across three ABIs, and the cost is only losing side-by-side debug/release
  installs. `tools/build-bundle.sh` carried the same stale id and was fixed.

`tools/package-termux-bundle.sh` converts `.deb` output into our bundle format — stripping the
baked absolute path, refusing anything outside the prefix, and claiming 16 KB page alignment
only when every shared object actually has it (RISK-004).

### Git works on device — and packaging it exposed three real defects (2026-09-11)

**Git 2.55.0 is built, installed and verified**: `git --version`, a full
init → add → commit → log cycle producing a real commit, symlinked subcommands executing, and
`env git --version` proving PATH-based exec reaches it. That unblocks Phase 4.

Getting there surfaced three genuine bugs, none of which reasoning would have found:

- **Bundles were 14× too large.** `zip` follows symlinks and stores a full copy of each target,
  with no dedup. Git is mostly links — 986 of them, ~50 pointing at one 3.6 MB binary — so a
  24 MB package became a **302 MB** bundle. Links are now declared in the manifest and created
  by the installer after promotion (they cannot be staged: the merge copies with follow-links
  semantics and would re-expand them). Result: **20.9 MB**. Manifest-declared links are also
  better than archive metadata for a security-sensitive installer, because every target is
  explicit and checked rather than hidden in a mode bit.
- **A symlink could escape the prefix.** The containment check resolved targets with
  `File(parent, target)`, which joins an *absolute* target onto the parent — so
  `bin/x -> /system/bin/sh` looked contained and would have been created pointing out of the
  prefix. A leading `/` is now treated as absolute regardless of host path rules, because a JVM
  on Windows does not consider `/system/bin/sh` absolute and the check would otherwise pass on
  the build machine and fail open on the device. Found by writing the rejection test.
- **`libmfexec` did not actually cover `execvp`.** A comment claimed overriding `execve` was
  enough since bionic implements the family in terms of it. True of the implementation, false of
  the interposition: libc's internal call never crosses the PLT, so `LD_PRELOAD` never saw it.
  Git reported `fatal: cannot exec 'maintenance': Permission denied`. `execvp`/`execvpe` now do
  their own PATH search so each candidate goes through our `execve`.
- **The interposer's prefix check missed half of Android's paths.** git's exec-path is baked in
  at build time as `/data/data/<pkg>/...`, while `MOBILEFORGE_PREFIX` comes from `filesDir` as
  `/data/user/0/<pkg>/...`. Same directory, different spelling — so git's own helpers looked
  outside the prefix, the rewrite was skipped, and W^X refused them. The interposer now
  normalises both spellings, mirroring the fix already made in `ToolchainCompatibility`.

The last two only became visible because a test assertion was tightened: the commit test
originally checked only that the commit appeared, and passed while
`cannot exec 'maintenance': Permission denied` sat unnoticed in the same output.

### arm64 builds (2026-09-11)

An **aarch64** build now runs alongside the x86_64 one, throttled to 3 jobs so neither starves.
`tree` is built and verified at the artifact level:

```
ELF 64-bit LSB shared object, ARM aarch64, dynamically linked,
interpreter /system/bin/linker64, NDK r29
RUNPATH: /data/data/dev.mobileforge/files/usr/lib
```

**Build state had to become per-architecture first.** termux-packages does support switching
arch in one tree — it moves `/data/data` aside into a per-arch backup — but that mechanism is
defeated here, because the prefix is a symlink into a Docker volume: both architectures resolve
to the same directory and would quietly mix aarch64 and x86_64 libraries. Two concurrent builds
would corrupt each other outright. State now lives under `/work/state/<arch>/`, so the isolation
is structural rather than something to remember.

**Packaging now refuses a bundle whose binaries are not the declared architecture.** This
matters most for the builds that cannot be executed here: an x86_64 bundle is exercised on the
emulator, whereas an aarch64 bundle is only verified structurally until it reaches real
hardware. The check was tested in both directions — a correct arm64 bundle passes, and x86_64
`.deb` files deliberately labelled `aarch64` are refused with exit 1 and no bundle written.

**arm64 cannot be executed here, and is not claimed to be.** The emulator is x86_64, and an
arm64 AVD on an x86_64 host needs full-system emulation. arm64 bundles are *built and
structurally verified*, not *run*. Physical arm64 hardware remains the standing gap.

### A shipped 16 KB alignment bug, found by lint (2026-09-11)

**`libmfexec.so` and `libmfpty.so` were built without `-Wl,-z,max-page-size=16384`.** On a
16 KB-page device — all new Android hardware from 2025 — neither would load. For `libmfexec`
that is not degradation but total failure: it is `LD_PRELOAD`ed into every process, so nothing
installed could be executed from a shell at all.

The irony is the point. `tools/build-bundle.sh` passed this flag for `mf-doctor`, and
`tools/package-termux-bundle.sh` *refuses* bundles that lack the alignment (RISK-004) — so the
project was rejecting everyone else's unaligned binaries while shipping its own. Nothing in the
test suite could catch it, because the emulator is x86_64 and uses 4 KB pages.

Fixed in `CMakeLists.txt`; both libraries now report `align 0x4000`.

**ADR-007's deferred `warningsAsErrors` decision is resolved as a consequence.** A blanket
switch was rejected: 33 of the 55 warnings were "a newer version exists", which are
time-dependent and would break the build when an unrelated library publishes — exactly the
pressure toward blanket suppression the original decision wanted to avoid. Instead `Aligned16KB`
is promoted to an **error** and the version nags are disabled. 55 warnings became 11, all real.

The guard was verified by breaking it deliberately: with the CMake flag removed, `lintDebug`
fails with `Lint found 11 errors` naming `libmfexec.so`, and aborts the build.

### Hardware-keyboard support (2026-09-11)

Ctrl+letter to control characters, Ctrl+C as a **signal** rather than the byte 0x03 (the byte
relies on a line discipline that a program in raw mode has switched off), Alt as Meta, and
cursor/Home/End/paging/function-key sequences. Keys are intercepted before the text field, so
Ctrl+C no longer types "c" and Escape is no longer inert.

`HardwareKeyMap` is deliberately pure and unit-tested rather than driven through a physical
keyboard: the failure mode here is a *wrong byte*, which surfaces far from its cause. Several of
the 14 tests assert keys are **not** claimed — consuming an ordinary letter, Enter or Backspace
would stop the user typing at all.

### PHP 8.5.1 runs on Android — Phase 3 is unblocked (2026-09-11)

Built from source against our prefix (33 packages in its dependency closure: ICU, OpenSSL,
curl, libxml2, oniguruma and the rest), installed, and verified on device by
`PhpToolchainVerificationTest`:

```
php --version   PHP 8.5.1 (cli) ... with Zend OPcache v8.5.1
php -r          echo 6 * 7  ->  42
php -m          json mbstring openssl PDO curl intl mysqli pdo_mysql ...
```

Four tests, because "the interpreter started" proves very little for an interpreter: it
evaluates an expression, lists loaded extensions (which load from a path compiled into the
binary — a second, independent way the baked prefix has to be right), and runs a real script
from disk exercising `json_encode`.

**Node failed, and not for the reason predicted.** I flagged V8's memory use against the 8.2 GB
VM throughout. That was wrong: the build died with a compile error, not OOM —
`deps/v8/src/roots/roots.h:504: error: expected identifier` from a macro expansion in
`INTERNALIZED_STRING_ROOT_LIST`, plus `no member named 'kT_string' in RootIndex`. That is a host
toolchain incompatibility between Node 26.4.0's bundled V8 and the container's compiler, and it
would happen on a machine with any amount of RAM. `nodejs-lts` is building instead.

**A packaging bug caught by the ELF machine check.** The PHP bundle came out with 14 **AArch64**
libraries in it. The dependency-closure resolver took one `.deb` per package name from an output
directory that now holds both architectures, and alphabetical ordering meant `aarch64` won. The
check added a few hours earlier — specifically because arm64 bundles cannot be executed here —
refused the bundle instead of shipping something that installs cleanly and then fails on device
with an unreadable linker error. The resolver now filters by architecture, and `git` and `tree`
were re-packaged and re-verified as a precaution.

### Node runs, and the `/proc/self/exe` shim was a lie (2026-09-11)

**Node 24.18.0 (LTS) works**: evaluates code, uses `os`/`path`/JSON core modules, runs scripts
from disk. Node 26.4.0 remains unbuildable for the toolchain reason above.

Node immediately exposed a feature this project had claimed twice over. Under linker-exec the
kernel reports the running program as the linker, so `readlink("/proc/self/exe")` answers
`/system/bin/linker64`. `ExecCommandBuilder` and `libmfexec` both set
`TERMUX_EXEC__PROC_SELF_EXE` to the real path — **and nothing read it.** The variable was set,
ROADMAP and ADR-011 both marked the shim done, and the behaviour was unchanged:

```
process.execPath  ->  /apex/com.android.runtime/bin/linker64
```

That is where npm and module resolution would have looked for everything. `libmfexec` now
intercepts `readlink`/`readlinkat`:

```
process.execPath  ->  /data/user/0/dev.mobileforge/files/usr/bin/node
```

The lesson is the same one `execvp` taught a few hours earlier: an interposer only does
something if a *call* is intercepted. Setting a variable that a cooperating library would have
read is not the same as being that library. Both documents are corrected rather than quietly
updated.

### The toolchain works at the actual prompt, not just in the harness (2026-09-11)

Every toolchain test until now launched processes with `JvmProcessLauncher` — pipes, and an
environment the test wrote itself. That proves the binaries work; it does not prove the thing a
user touches works. `ToolchainInTerminalVerificationTest` closes that gap by going through the
product's own path: `ShellCommandFactory` resolves the shell and builds the environment, the
shell runs under a real PTY, and output is read back through `TerminalEmulator`.

Typing `php` **by name**, so the shell's own PATH lookup has to reach the interposer:

```
$ php -r 'echo "PHP_FROM_PROMPT_" . (6*7);'
PHP_FROM_PROMPT_42
$ echo "prefix=$PREFIX"
prefix=/data/user/0/dev.mobileforge/files/usr
```

### Licence compliance was recording one licence per bundle, not per component (2026-09-11)

The manifest carried a single `license` and `source_url` — the headline tool's. But a PHP bundle
contains **33 packages**, and the obligation to offer source attaches to each of them
separately. A user is entitled to the source of the LGPL library three dependencies down, not
just to PHP's.

Manifests now record a `components` list — name, version, SPDX licence and source URL for every
package in the bundle. PHP's 33 components span GPL-2.0, GPL-3.0, LGPL-2.1, LGPL-3.0, MPL-2.0,
Apache-2.0, MIT, BSD, NCSA, ZLIB, PHP-3.01 and Public Domain. Packaging also **warns** when a
component's licence is not a recognisable SPDX identifier:

```
WARNING: components whose licence needs manual review before distribution:
  libicu (custom)
```

That is a real outstanding item (ICU ships the Unicode licence, which upstream records as
`custom`) and it is now visible at packaging time rather than at distribution time.

### Bundle inventory

Extracted to `build/toolchain-bundles/` — these are the artifacts, not just container state:

| Bundle | ABI | Components | Symlinks | Size |
|---|---|---|---|---|
| git 2.55.0 | x86_64 / arm64-v8a | 14 | 986 | 19 / 20 MB |
| php 8.5.1 | x86_64 / arm64-v8a | 33 | 870 | 48 / 49 MB |
| nodejs-lts 24.18.0 | x86_64 | 9 | 27 | 34 MB |
| tree 2.3.2 | x86_64 / arm64-v8a | 2 | 1 | <1 MB |

### npm, and a chain of four defects behind it (2026-09-11)

Asked whether the project was done, checking first turned up that **npm was not in the Node
bundle at all** — only `node` and `corepack`. termux builds `nodejs-lts` with `--without-npm`
and lists npm under `Recommends`, which the dependency closure deliberately does not follow. So
"Node works" was true and "Node/npm works" was not.

Fixing that exposed three more, each hidden behind the last:

1. **npm's interpreter was absent.** Its shebang is `#!$PREFIX/bin/env node`, correctly
   repointed to our prefix — but `env` lives in coreutils, which is not in Node's closure. The
   failure reads `can't execute: Permission denied`, which points at W^X and is nothing of the
   sort.
2. **No package provides `$PREFIX/bin/sh`.** Termux's *bootstrap archive* creates that symlink,
   not any `.deb`, and we ship no bootstrap — so every `#!$PREFIX/bin/sh` script was unrunnable.
   Bundles now opt in with `PROVIDE_SH=bash`. This also improves the terminal:
   `ShellCommandFactory` prefers `$PREFIX/bin/sh` over Android's once one exists.
3. **Symlink targets never got the executable bit.** `bin/npm` links to
   `lib/node_modules/npm/bin/npm-cli.js`, and the manifest collected executables only from
   `bin/` and `libexec/` — so the script the link pointed at stayed non-executable. Link targets
   are now resolved and included.

`npm --version` now answers `11.19.1` on device.

**A new guard, and a lesson about guards.** Packaging now refuses a bundle whose scripts
reference an in-prefix interpreter that is not present. It caught defects 1 and 2 immediately —
but not 3, because the interpreter existed and merely lacked a mode bit. Guards narrow the
failure space; they do not close it.

The guard also had to learn a distinction. git ships CVS and Perforce bridges needing perl and
python, and bundling a Python runtime so `git-p4` exists on a phone is not a trade anyone would
make. Those are now declared explicitly per bundle via `ALLOW_MISSING_INTERPRETERS`, and the
packaging output names every command that will not run — shipping broken commands is acceptable,
shipping them silently is not.

### Package contamination — caused by my own optimisation (2026-09-11)

Persisting the install prefix across container runs, to avoid recompiling dependencies, **broke
package hygiene**. termux determines a package's contents by diffing the prefix around a build,
so files left by earlier builds were attributed to whatever was rebuilt next. The `openssl`
package produced this way contained **1453 apache2 share files, 112 apache2 modules and
capstone's headers**.

It surfaced as a test regression — PHP stopped starting with
`CANNOT LINK EXECUTABLE: library "libldap.so" not found` — because a rebuilt curl had also
auto-detected an openldap that happened to be sitting in the shared prefix and linked against it
without declaring the dependency. A non-hermetic build in two directions at once.

**Prefix persistence is reverted.** Only the source cache is persisted now, which is safe. The
cost is rebuilding dependencies on every run — hours — and that is the correct trade: a fast
build producing wrong packages is worth nothing. 123 suspect x86_64 packages are quarantined
rather than deleted.

**Two new guards came out of it:**

- **Shared library check** — every `DT_NEEDED` library must be present in the bundle or be an
  Android system library. This is the failure ADR-011 is built around, and it now cannot ship.
- **Test fixtures are stripped** — termux-core installs unit-test binaries under
  `libexec/installed-tests`, including one built with AddressSanitizer linking
  `libclang_rt.asan-*.so`. 24 files per bundle, none of them product.

Worth being clear about the limit: neither guard would have caught the contamination itself. A
bundle with apache2 files wrongly inside openssl still installs, is the right architecture, and
links correctly. **Guards narrow the failure space; they do not define correctness.**

### Bundles regenerated for arm64, and a size trade worth revisiting

All four arm64 bundles now pass every check — architecture, shared libraries, shebang
interpreters, prefix, page size:

| Bundle | Components | Symlinks | Size |
|---|---|---|---|
| php 8.5.1 | 65 | 1043 | 58 MB |
| nodejs 26.4.0 | 57 | 1127 | 56 MB |
| git 2.55.0 | 52 | 1170 | 31 MB |
| tree 2.3.2 | 51 | 1013 | 19 MB |

The first attempt gave every bundle its own copy of coreutils and bash, so that
`$PREFIX/bin/sh` would exist — no termux `.deb` provides it, since Termux's bootstrap archive
does. That made bundles self-sufficient and made `tree`, a 40 KB utility, a **19 MB** download.

**Fixed with a `base` bundle.** Bundles already merge into one shared `$PREFIX`, so the shell
and coreutils belong in one bundle installed once, with tool bundles declaring a dependency on
it. Manifests gained a `requires` field; `AndroidToolchainManager` refuses to install a bundle
whose requirements are missing, and the packaging tool treats interpreters the base provides as
satisfied rather than silently absent.

| Bundle | Before | After |
|---|---|---|
| base (new) | — | 19.8 MB |
| php 8.5.1 | 58 MB | 49.0 MB |
| nodejs 26.4.0 | 56 MB | 40.8 MB |
| git 2.55.0 | 31 MB | 20.3 MB |
| tree 2.3.2 | 19 MB | **0.1 MB** |

The requirement check is a pure function (`ToolchainCompatibility.missingRequirements`) rather
than logic buried in the Android layer, so it is unit-tested: 7 tests, including that an
unrelated installed bundle does not satisfy a requirement and that matching is exact rather than
by prefix. Installing a tool without its base has to be refused up front — the prefix would look
fine, the install would report success, and the failure would arrive much later as a script
dying on a missing interpreter.

### arm64 Node built — and it is the version x86_64 could not build

`nodejs 26.4.0` **succeeded for aarch64**, verified as a genuine AArch64 ELF, while the same
version failed on x86_64 with the V8 `roots.h` error. Cross-compiling puts host and target on
different paths through V8's build graph. So the two architectures currently carry different
Node versions: 26.4.0 on arm64, 24.18.0 LTS on x86_64. That asymmetry is recorded rather than
smoothed over.

Totals after this work: **510 unit tests** and **36 instrumented tests**, 0 failures.

Still open: **physical arm64 hardware** (all verification to date is x86_64 emulator), real
toolchain packaging (PHP/Node/Git/Composer), and TUI compatibility beyond `vi` — mouse
reporting, bracketed paste and `htop`-class programs are unverified.

---

## Headline

| | |
|---|---|
| **MVP acceptance criteria met** | **11 of 28** (6 partial) |
| Phases complete | 0, 1, 2a, 2b-i, 2b-ii |
| Phase partially complete | 2b-iii (git, PHP and Node run on device; arm64 outstanding) |
| Phases not started | 3–9 |
| Build | `BUILD SUCCESSFUL` — AGP 8.13.0 / Gradle 8.14.3 / targetSdk 36 |
| Unit tests | 540, 0 failures (was 256 at baseline) |
| Instrumented tests | 46 on Android 14 + Android 11, 0 failures |
| Code | 98 Kotlin files (~13,900 lines), 3 C files (~590 lines) |
| Modules | 17 Gradle modules |
| Architecture records | ADR-001 … ADR-011 |

**In one sentence:** the hard Android platform questions are answered and measured, the IDE
shell and a real terminal work, and roughly 80% of the product — every actual development tool —
is not built.

---

## What works

### Verified on device (Android 11 / API 30 / x86_64 emulator)

| Capability | Evidence |
|---|---|
| App installs, launches, no crashes | `MainActivity` resumes; manual run |
| Create / open / remove projects | Real folders created under app storage |
| Project scaffolding | Static-site and PHP templates write real files |
| Framework detection | Correctly identified a scaffolded project as "Static site" |
| File explorer | Lists, sorts, lazy-loads; adaptive phone layout |
| **Process execution via system linker** | `ExecMechanismVerificationTest` |
| **Real PTY terminal** | `PtyVerificationTest` — `[ -t 0 ]` true, echo, Ctrl+C, resize |
| **Terminal UI** | `pwd`, `uname`, `ls` typed and run in the app |
| **Toolchain install + execute** | `ToolchainVerificationTest` — install, verify, linker-exec |
| **Shell can run installed binaries** | `ExecInterposerTest` — fails without `libmfexec`, works with |

### Verified by unit test only

Path containment (incl. symlink escape), secret redaction, command-risk classification,
workspace trust, atomic writes, framework detection, language mapping, exec strategy selection,
shebang resolution, process lifecycle and OS-kill classification, bounded output, port
allocation, terminal escape-sequence handling, toolchain integrity and Zip Slip rejection.

### The three platform questions, answered

These were the genuine unknowns. Each was measured, and two failed first.

1. **Can an app execute binaries on modern Android?** Yes — via
   `execve("/system/bin/linker64", [path, …])`. Direct execution is refused with `EACCES`,
   observed. ([ADR-002](docs/adr/ADR-002-runtime-strategy.md), [ADR-009](docs/adr/ADR-009-exec-core.md))
2. **Can we get a true TTY?** Yes — `forkpty` via the NDK. Pipes cannot do it.
   ([ADR-010](docs/adr/ADR-010-pty-terminal.md))
3. **Can a shell invoke what we install?** Only with an `LD_PRELOAD` `execve()` interposer.
   Without it, `Permission denied`. ([ADR-011](docs/adr/ADR-011-toolchain-strategy.md))

---

## What does not work

### Blocking, and bigger than they look

**Monaco renders and round-trips content** on Android 14 / Chromium 113
(`MonacoRenderVerificationTest`), so criterion 4 is met. What remains unmeasured is the part a
test cannot answer: **memory footprint, IME behaviour and touch/selection ergonomics on a real
phone.** A 4 GB emulator with a hardware keyboard is not evidence that Monaco is usable on a
mid-range handset, and that is now the open editor question rather than whether it starts.

**No development toolchain exists.** The install-and-execute mechanism is proven with
`mf-doctor`, a ~40-line C program written for the purpose. There is no PHP, Node, Git or
Composer. On a stock device the only other executables are Android's own.

**Nothing has run on physical hardware.** All verification is an x86_64 emulator. Users run
arm64.

### Not started

Laravel support, local servers, live preview, Git, AI providers, AI agents, LSP, extensions —
Phases 3 through 9.

---

## Acceptance criteria, one by one

| # | Criterion | Status |
|---|---|---|
| 1 | Launch on an Android device | ✅ emulator; physical arm64 untested |
| 2 | Create / open a project | ✅ |
| 3 | Browse files | ✅ |
| 4 | Edit code | ✅ Monaco verified on Chromium 113; phone ergonomics unmeasured |
| 5 | Save code safely | ✅ atomic writes, unit-tested |
| 6 | Open a terminal | ✅ |
| 7 | Run shell commands | ✅ |
| 8 | Run PHP | ✅ PHP 8.5.1 verified on device (x86_64) |
| 9 | Run Composer | ❌ not packaged — it is a PHP phar, so this is packaging, not a platform problem |
| 10 | Create a Laravel app | ❌ |
| 11 | Artisan | ❌ |
| 12 | Laravel local server | ❌ |
| 13 | Preview in-app | ❌ |
| 14 | Node / npm | ⚠️ node 24.18 verified on device; **npm is a separate termux package and is not in the bundle** |
| 15 | Git init / clone | ⚠️ works in the terminal (real commit verified); no in-app Git UI |
| 16 | Git status | ⚠️ terminal only; no in-app Git UI |
| 17 | Commits | ⚠️ terminal only — a real commit was made and read back on device |
| 18 | Diffs | ❌ |
| 19 | Connect an AI provider | ❌ contracts only |
| 20 | Stream AI responses | ❌ |
| 21 | AI inspects files | ❌ |
| 22 | AI modifies files via tools | ❌ |
| 23 | Show AI diffs | ❌ |
| 24 | Permission for dangerous ops | ⚠️ classifier + trust model built; no agent to gate |
| 25 | Run an agent backend | ❌ |
| 26 | Secrets protected | ✅ Keystore, redaction, no analytics |
| 27 | Recover from runtime failures | ⚠️ partial — OS-kill detection, crash-safe writes |
| 28 | Work without AI | ✅ trivially — there is no AI |

**11 met, 6 partial, 11 not started.**

Counted honestly rather than generously: "works if you type it in the terminal" is recorded as
partial, not met, because criteria 15–18 describe Git *in the IDE* and Phase 4 has not started.
The jump from 9 to 11 is PHP and the editor; the partials are Git-via-terminal and Node without
npm.

---

## What needs to be done

### Immediate — cheap, high value

| Task | Why |
|---|---|
| Run on a **physical arm64 device** | Everything so far is x86_64 emulator. `bash tools/verify-runtime.sh` |
| Get a device with **Chromium 94+ WebView** and open a file | Would finally verify the editor, or trigger the CodeMirror fallback |
| Compose UI tests | Navigation and the trust dialog have no automated coverage |
| `compileSdk`/`targetSdk` → 36, Gradle 8.13+, AGP 8.11+ | **Play requires API 36; this build cannot be published** |

### Phase 2b-iii completion — a build-system project, not engineering

Termux packages **cannot be reused**: they bake `/data/data/com.termux/files/usr` into binaries
and are not relocatable. Bundles must be built against our prefix.

- Run the termux-packages Docker build on a Linux host with `TERMUX_APP_PACKAGE` repointed
- Package PHP, Node, Git, Composer for arm64-v8a (+ x86_64 for emulators)
- Licence review and **source-offer hosting for GPL components** (a real obligation)
- Bundle hosting, download with resume, signature verification
- **Toolchain installer UI** — nothing in the app can trigger an install today
- `libmfexec` should set `TERMUX_EXEC__PROC_SELF_EXE`; Node self-locates that way
- Verify full `vim`/`htop` compatibility; UTF-8 and scroll-region unit tests now pass

### Phases 3–9

| Phase | Scope |
|---|---|
| 3 — Laravel | Project creation, Artisan, local server, preview, `.env` editor |
| 4 — Git | Full local ops, clone, OAuth device flow, gutter decorations |
| 5 — AI | Provider adapters, BYOK, streaming, tools, permissions, diff review |
| 6 — External agents | OpenCode, Claude Code, Codex, Aider — detection, not assumption |
| 7 — LSP | PHP first, then JS/TS, HTML/CSS/JSON |
| 8 — Extensions | Native host; `.vsix` inspection with specific verdicts |
| 9 — Advanced | Local models, multi-agent, indexing, per-project toolchains |

### Release gates

- [ ] `targetSdk` 36 (**blocks Play distribution today**)
- [ ] Release signing config
- [ ] R8 rules validated — `assembleRelease` is not a supported target
- [ ] Security review against [SECURITY.md](SECURITY.md)
- [ ] Third-party licence attribution in-app

---

## Honest assessment

**What was actually bought:** the parts of this product that could have proven impossible are
now proven possible, on a device, with tests that will catch a regression. Two of the three
mechanisms failed on first attempt in ways no amount of design review would have caught — a CSP
silently blocking Monaco's bootstrap, and an `LD_PRELOAD` that could not resolve because
`extractNativeLibs` defaults to false. That is the value of the phase discipline.

**What was not bought:** a usable IDE. A developer cannot yet edit a verified file, run PHP,
use Git, or talk to a model.

**The next decision is not technical.** Finishing Phase 2b-iii means committing to packaging
and redistributing a Linux toolchain — build infrastructure, licence compliance, hosting,
update policy — which is a different kind of work from everything so far. It is worth deciding
deliberately rather than continuing on momentum.

If the goal is the shortest path to something demonstrably useful, the order I would suggest is:
**verify the editor on a current-WebView device** → **package Git alone** (smallest, most
self-contained, unlocks Phase 4) → **Laravel/PHP**.

---

## Reference

| Document | Contents |
|---|---|
| [README.md](README.md) | Overview, build instructions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module structure, layering rules |
| [SECURITY.md](SECURITY.md) | Threat model; which controls are actually implemented |
| [RISKS.md](RISKS.md) | 15 risks with probability, impact, mitigation, fallback |
| [ROADMAP.md](ROADMAP.md) | Full phase detail and release gates |
| [docs/adr/](docs/adr/) | ADR-001 … ADR-011 |

Start with [ADR-002](docs/adr/ADR-002-runtime-strategy.md) and
[ADR-011](docs/adr/ADR-011-toolchain-strategy.md) — between them they explain why running
developer tooling on Android is hard and what it took to make it work.
