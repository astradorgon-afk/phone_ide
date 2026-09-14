# ADR-010 — A real PTY for the terminal

* **Status:** Accepted — **verified on device 2026-09-06**
* **Date:** 2026-09-06
* **Affects:** `:runtime:pty`, Phase 2b
* **Builds on:** [ADR-009](ADR-009-exec-core.md), which resolved *how* to launch a program

## Context

Phase 2a can start processes through `ProcessBuilder`. That is correct for running a build or a
server and **structurally incapable** of being a terminal:

| | Pipes (`ProcessBuilder`) | PTY |
|---|---|---|
| `isatty(0)` | false | **true** |
| Line discipline (echo, editing) | none | kernel-provided |
| Ctrl+C → SIGINT to foreground group | impossible | yes |
| Window size (`TIOCSWINSZ`) | none | yes |
| Controlling terminal | none | yes |

The consequences are not cosmetic. With `isatty()` false, shells disable job control and
prompts; `git`, `composer` and `npm` switch to non-interactive output; anything using ncurses or
readline either refuses to run or emits raw escape codes. **None of this is emulable from Java** —
the line discipline lives in the kernel.

So the choice was: write native code, or ship a "terminal" that is a command-output viewer
wearing a costume. The second option is exactly the kind of fake this project refuses.

## Decision

A small C layer (`:runtime:pty`) around `forkpty`, plus a pure-Kotlin terminal emulator.

### 1. `forkpty`, not a hand-rolled `openpty` + `fork`

bionic has provided `openpty`/`forkpty`/`login_tty` since API 23; `minSdk` is 30, so they are
always available. `forkpty` also handles `setsid()` and the `TIOCSCTTY` dance that makes the
child's PTY its *controlling* terminal — get that wrong by hand and Ctrl+C silently stops working.

### 2. The native layer decides nothing

It receives a fully-resolved argv from `ExecCommandBuilder` and execs it. It knows nothing about
the system linker, shebangs or ELF types. That keeps the Android exec workaround in exactly one
place (ADR-009) instead of duplicating it in a language with no tests.

### 3. Signals go to the process GROUP

`killpg`, not `kill`. A shell running `sleep 30` puts sleep in the foreground process group;
signalling only the shell's pid leaves sleep running while the UI claims the interrupt worked.
There is an on-device test for precisely this.

### 4. The emulator is pure Kotlin

`TerminalEmulator` — escape-sequence parsing, screen buffer, scrollback — has no Android
dependency and is covered by 38 unit tests. Terminal bugs surface as subtly corrupted output
that is miserable to reproduce by hand, so they are pinned in tests rather than found by eye.

**Scope is a deliberate subset:** cursor movement, erase, SGR colour/bold, line insert/delete —
what a shell, `ls`, `git`, `composer` and `npm` actually emit. Unknown sequences are consumed
cleanly rather than printed as garbage. The alternate screen buffer and bracketed paste are
**not** implemented, because half-implementing them renders worse than not claiming them.

Colours are stored as ANSI **indices**, not RGB, so the UI maps them onto the app's palette.
A terminal hard-coding `#00FF00` would fight the editor's theme.

### 5. Missing native library is a capability, not a crash

`NativePty.isAvailable` catches `UnsatisfiedLinkError` at load. A device with an ABI we did not
build for reports "terminal not supported here" like any other capability gap, instead of
throwing from a static initialiser at an arbitrary later moment.

## Verification

**JVM (38 tests):** every escape sequence above, including sequences split across reads (a PTY
read can end mid-sequence), runaway parameter buffers, and bounded scrollback.

**On device — `PtyVerificationTest`, Android 11 / API 30 / x86_64, all passing:**

```
IS_A_TTY                                     [ -t 0 ] succeeds — the thing pipes cannot do
echo hello_from_pty ... hello_from_pty       line discipline echoes input
stty size -> 40 132                          TIOCSWINSZ honoured
AFTER_INTERRUPT                              Ctrl+C killed `sleep 30` via the process group
exit code: 3                                 exit status propagated
:/data/data/dev.mobileforge.debug/files $    a real shell prompt
```

It uses `/system/bin/sh`, present on every Android device, so it needs no bootstrapped
toolchain and runs the moment the app installs.

## Consequences

**Accepted costs**

* Native code: three ABIs to build (`arm64-v8a`, `armeabi-v7a`, `x86_64`), compiled with
  `-Wall -Wextra -Werror`. It is ~250 lines and does one thing.
* Post-`fork()` discipline: the child does only async-signal-safe work before `execve`, because
  the JVM's locks may be held by threads that no longer exist in the forked process.
* File-descriptor ownership needed care. `ParcelFileDescriptor.adoptFd` owns the fd; both the
  input and output streams share it, so neither may close it. Closing is done in exactly one
  place.
* UTF-8 decoding is currently per-read, so a multi-byte character split across two reads can
  corrupt. A streaming decoder is the fix; tracked in ROADMAP.md.

**Not done**

* No terminal **UI** yet. The engine is verified; rendering it in Compose is the next step.
* No scroll-region (`DECSTBM`) support, so full-screen TUIs like `vim` or `htop` will not lay
  out correctly. That is a known limit, not a silent one.
