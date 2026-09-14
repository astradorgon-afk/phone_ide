# ADR-011 — Toolchain packaging and the exec interposer

* **Status:** Accepted — **verified on device 2026-09-07**
* **Date:** 2026-09-07
* **Affects:** `:runtime:toolchain`, `libmfexec`, Phase 2b-iii
* **Builds on:** [ADR-002](ADR-002-runtime-strategy.md), [ADR-009](ADR-009-exec-core.md)

## Context

The IDE needs PHP, Node, Git and Composer on-device. Two questions had to be answered before
writing an installer: where do the binaries come from, and does installing them actually make
them runnable?

## Decision 1 — Termux packages cannot be reused

The obvious shortcut is Termux's package repository: thousands of tools already built for
Android against Bionic, with correct `DT_RUNPATH` and NDK toolchains.

**It does not work, and the reason is structural.** Termux bakes its prefix —
`/data/data/com.termux/files/usr` — into binaries at build time: library search paths,
interpreter paths, certificate locations. The packages are explicitly not relocatable, and
forks using a different package name have to patch the prefix throughout the build system.

Our prefix is `/data/data/dev.mobileforge/files/usr`. A Termux `php` dropped into it would not
find its own libraries. And we cannot adopt Termux's prefix: it is another app's private
directory, which SELinux denies us.

**Consequence:** bundles must be built against *our* prefix. `ToolchainManifest` records the
prefix it was built for, and the installer refuses a mismatch outright rather than letting it
fail later as a baffling "library not found".

That makes producing bundles a build-system project — a Linux host running the termux-packages
Docker build with `TERMUX_APP_PACKAGE` repointed — rather than something the app can do. It is
tracked as such, not hidden.

## Decision 2 — Verify before extracting, extract before promoting

The install pipeline's ordering *is* the security design:

1. **Compatibility check** — ABI, prefix, page size, free space. Before any download.
2. **SHA-256 over the whole archive** — before extraction. Verifying afterwards would mean
   writing attacker-controlled files first and hoping to clean up.
3. **Extract to staging** — never into a live `$PREFIX`, so a failure halfway cannot leave a
   half-installed toolchain.
4. **Reject path traversal** — an entry named `../../../../system/bin/sh` is the Zip Slip
   attack, and a downloaded bundle is exactly the artefact that carries one.
5. **Promote by merging** — bundles share `$PREFIX/bin`, so a wholesale directory swap would
   delete previously installed tools.

Page size is checked because a 4 KB-aligned shared library will not load on a 16 KB-page
device (RISK-004); declaring it lets us refuse with a real reason instead of a cryptic linker
failure. `source_url` is a *required* manifest field, because several tools we intend to ship
are GPL-licensed and distributing them carries an obligation to offer source — making it
mandatory means it cannot be forgotten at packaging time.

## Decision 3 — An LD_PRELOAD exec interposer is mandatory, not optional

This one was learned the hard way, on a device.

`ExecCommandBuilder` (ADR-009) rewrites a program path into `linker64 <path> …` so W^X does not
block it. Installing `mf-doctor` and running it through that path worked first time. Then it
was typed at the terminal prompt:

```
/system/bin/sh: /data/.../usr/bin/mf-doctor: Permission denied
```

**Our rewrite only covers processes we launch.** Once a shell is running, every command the
user types is exec'd by the *shell*, through the kernel's normal path, and SELinux refuses it.
A toolchain you can install but cannot invoke from a shell is not a toolchain.

The fix is to intervene one level lower: `libmfexec`, a small `LD_PRELOAD` library that
overrides `execve()` in every descendant and applies the same rewrite regardless of who calls
it. This is the approach `termux-exec` takes, for the same reason.

Scope is deliberately narrow: only paths under `$MOBILEFORGE_PREFIX` are rewritten, so system
binaries are untouched; shebang scripts are resolved here too, because the kernel never sees
the script when handed the linker; and static ELF binaries are passed through unmodified, since
the linker cannot load them and rewriting would turn a clear `EACCES` into a confusing linker
error.

### Two device-only failures on the way

**`LD_PRELOAD` was set conditionally.** The first version set it only when the *parent* used
system-linker exec — but the terminal's shell is `/system/bin/sh`, which lives on the system
partition and runs directly, so it was never set. The interposer exists to fix what *children*
do, so it must be present regardless of how the parent was launched. Now unconditional.

**`extractNativeLibs` defaults to false.** With modern AGP, `.so` files stay inside the APK and
are mapped directly. `System.loadLibrary` copes; `LD_PRELOAD` does not, because the dynamic
linker needs a real path:

```
CANNOT LINK EXECUTABLE "/system/bin/sh": library ".../libmfexec.so" not found
```

Fixed with `packaging { jniLibs { useLegacyPackaging = true } }`. The cost is a larger install
footprint; the benefit is a toolchain that can be invoked from a shell at all.

## Verification

**JVM (14 tests):** digest mismatch refused with nothing written, Zip Slip refused, ABI
mismatch, prefix mismatch, page-size mismatch, insufficient space, merge-not-replace, progress
monotonic, staging cleaned up.

**On device (API 30 / x86_64):**

* `ToolchainVerificationTest` — installs a real NDK-built bundle and executes it:
  `strategy=SystemLinker`, `argv=[/system/bin/linker64, …/usr/bin/mf-doctor]`, `exit=0`, with
  `$PREFIX` and the `/proc/self/exe` shim visible to the process. A tampered manifest is
  refused and writes nothing.
* `ExecInterposerTest` — `shellCannotExecuteWithoutTheInterposer` confirms the `EACCES` is
  real in the app's own SELinux domain; `shellCanExecuteWithTheInterposer` confirms libmfexec
  fixes it.
* **In the app's terminal**, typing `mf-doctor` prints its self-check, showing `$PREFIX`,
  `$HOME`, `$PATH` and `TERM` — the full chain from keystroke to our own binary.

A note on test validity: an `adb shell run-as` session runs in a *different* SELinux domain and
can execute app-data files directly. A shell test there passes while the real app fails, so it
is worse than useless — only instrumented tests exercise the domain that matters.

## Decision 4 — Symlinks are declared in the manifest, not carried in the archive

*Added 2026-09-11, after packaging git.*

A real toolchain is mostly symbolic links. The git bundle has **986**, around 50 of them
pointing at one 3.6 MB binary, and every file under `libexec/git-core` is a link.

`zip` follows symlinks by default and stores a full copy of each target, with no deduplication.
Packaging git that way produced a **302 MB** bundle from a 24 MB package. `zip -y` would store
them as links instead, but archive symlink metadata is awkward to read from
`java.util.zip.ZipInputStream` and, more importantly, invisible to anyone reviewing a bundle.

**Decision:** the packaging tool removes symlinks from the archive and records them in the
manifest as `link path -> target`; `ToolchainInstaller` creates them after promotion. The git
bundle is **20.9 MB**.

This is a security improvement as much as a size one. Every link target is now explicit in the
manifest, covered by whatever signs it, and validated before creation — a link is just as
capable of escaping the prefix as a `../..` archive entry, and
`$PREFIX/bin/x -> /system/bin/sh` would otherwise be an unreviewable escape hatch.

Links are created **after** promotion, not in staging: the staging-to-prefix merge copies with
follow-links semantics, so a staged link would arrive in the prefix as a full copy of its
target — reintroducing exactly the problem this avoids.

## Decision 5 — The interposer must override `execvp`, and must accept both spellings of the data directory

*Added 2026-09-11, both found by running git rather than by reading code.*

**`execve` alone is not enough.** This file previously claimed that overriding `execve` covered
`execv` and `execvp`, since bionic implements them in terms of it. That is true of the
implementation and false of the interposition: libc's internal call to `execve` is resolved
inside `libc.so` and never crosses the PLT, so `LD_PRELOAD` never sees it. Git surfaced it as
`fatal: cannot exec 'maintenance': Permission denied`. `libmfexec` now implements `execvp` and
`execvpe` with their own `PATH` search, so every candidate goes through our `execve`.

**Both `/data/data/<pkg>` and `/data/user/<id>/<pkg>` must match the prefix.** They are the same
directory, but they reach us from different places: `MOBILEFORGE_PREFIX` comes from
`Context.filesDir`, which reports the `/data/user/0` form, while a package's own paths are baked
in at build time as `/data/data`. git's exec-path is baked, so its helpers looked *outside* the
prefix, the rewrite was skipped, and W^X refused them. The interposer normalises both spellings,
as does `ToolchainCompatibility` — the same duality bit the install-time prefix check first.

## Decision 6 — The `/proc/self/exe` shim has to intercept `readlink`, not just set a variable

*Added 2026-09-11. This ADR previously recorded the shim as a known gap, then as done; both were
wrong in the same way.*

Under linker-exec the kernel's idea of the running program is the linker, so
`readlink("/proc/self/exe")` answers `/system/bin/linker64`. Anything locating its installation
that way is then wrong about where it lives.

`ExecCommandBuilder` and `libmfexec` both set `TERMUX_EXEC__PROC_SELF_EXE` to the real program
path — and **nothing read it**. The variable was set, the box was ticked, and the behaviour was
unchanged. Node exposed it immediately: `process.execPath` returned
`/apex/com.android.runtime/bin/linker64`, which is where npm and module resolution would have
looked for everything.

`libmfexec` now overrides `readlink` and `readlinkat`, answering with the recorded path for
`/proc/self/exe` and this process's own `/proc/<pid>/exe`, and delegating everything else.
Verified on device: `process.execPath` is now
`/data/user/0/dev.mobileforge/files/usr/bin/node`.

The general lesson matches Decision 5: an interposer is only doing something if a *call* is
intercepted. Setting a variable that a cooperating library would have read is not the same as
being that library.

## Consequences

* **Git, PHP and Node all ship and work.** PHP 8.5.1 evaluates code, loads extensions from its
  compiled-in path and runs scripts; Node 24.18.0 (LTS) evaluates code, uses core modules and
  now knows its own location. Node 26.4.0 could **not** be built — its bundled V8 fails to
  compile against the container toolchain (`roots.h:504: error: expected identifier`), a host
  incompatibility rather than a resource limit.
* **Git ships and works.** Built from source against our prefix, installed, and verified on
  device: a real commit, symlinked subcommands, and PATH-based exec from a system binary.
  PHP and Node are still building; nothing is packaged for arm64 yet.
* `useLegacyPackaging = true` increases install size for every user.
* **Bundles are tied to the application id.** The prefix is derived from it, so
  `applicationIdSuffix = ".debug"` had to be removed — debug builds would otherwise look for a
  prefix no bundle is built for. The cost is losing side-by-side debug/release installs; the
  alternative was building the entire toolchain twice, for three ABIs.
* `posix_spawn` is still not intercepted — bionic implements it off its own syscall path.
  Nothing in the toolchain has needed it so far, git included; documented rather than silently
  missing.
