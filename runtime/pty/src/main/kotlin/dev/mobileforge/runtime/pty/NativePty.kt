package dev.mobileforge.runtime.pty

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin JNI surface over `forkpty`. Nothing above this layer touches a raw fd.
 *
 * Deliberately dumb: it decides nothing about *what* to run. The caller supplies a
 * fully-resolved argv from `ExecCommandBuilder`, so the Android system-linker workaround stays
 * in exactly one place (ADR-009) rather than being duplicated in native code.
 */
internal object NativePty {

    /**
     * Whether the native library loaded.
     *
     * Checked rather than assumed: a device with an ABI we did not build for would otherwise
     * throw `UnsatisfiedLinkError` from a static initialiser at an arbitrary later moment. This
     * way the terminal can report "not supported on this device" like any other capability.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary("mfpty")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    @JvmStatic
    external fun nativeForkPty(
        argv: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
        pidOut: IntArray,
    ): Int

    @JvmStatic
    external fun nativeResize(fd: Int, rows: Int, cols: Int)

    @JvmStatic
    external fun nativeSignalGroup(pid: Int, signalNumber: Int): Int

    @JvmStatic
    external fun nativeWaitFor(pid: Int, blocking: Boolean): Int

    @JvmStatic
    external fun nativeClose(fd: Int)
}

/**
 * A running process attached to a pseudo-terminal.
 *
 * The master fd is both ends: reading gets the child's output, writing sends it input. That is
 * the essential difference from a pipe pair — the child sees one terminal, so `isatty()` is
 * true, echo and line editing work, and Ctrl+C reaches the foreground process group.
 */
class PtyProcess internal constructor(
    private val masterFd: Int,
    val pid: Int,
    private val descriptor: ParcelFileDescriptor,
) {

    /**
     * Child output. Reading blocks until data arrives or the PTY closes.
     *
     * The [ParcelFileDescriptor] owns the fd, so these streams must NOT auto-close it —
     * both streams share one fd, and letting either close it would break the other. Ownership
     * lives in exactly one place: [close].
     */
    val input: InputStream = FileInputStream(descriptor.fileDescriptor)

    /** Child input. What is written here appears to the child as typed at a terminal. */
    val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    @Volatile
    private var closed = false

    @Volatile
    private var cachedExitCode: Int? = null

    /**
     * Tells the child its window changed.
     *
     * Not cosmetic: without a correct `TIOCSWINSZ`, anything using ncurses or readline draws
     * to the wrong width, and a shell's line wrapping goes wrong the moment a command is longer
     * than the assumed 80 columns.
     */
    fun resize(rows: Int, cols: Int) {
        if (closed) return
        NativePty.nativeResize(masterFd, rows.coerceAtLeast(1), cols.coerceAtLeast(1))
    }

    /** True when the child failed before it could exec — a distinct, explainable case. */
    fun startupFailure(): String? = when (cachedExitCode ?: exitCodeOrNull()) {
        EXIT_CHDIR_FAILED -> "The working directory could not be entered."
        EXIT_EXEC_FAILED -> "The program could not be started."
        else -> null
    }

    /**
     * Signals the child's process GROUP.
     *
     * The group, not the pid: a shell running `sleep 100` puts sleep in the foreground group,
     * and signalling only the shell would leave sleep running while claiming Ctrl+C worked.
     */
    fun signal(signal: PtySignal): Boolean =
        !closed && NativePty.nativeSignalGroup(pid, signal.number) == 0

    /** Exit status, or null while the child is still running. Never blocks. */
    fun exitCodeOrNull(): Int? {
        cachedExitCode?.let { return it }
        val result = NativePty.nativeWaitFor(pid, blocking = false)
        return when {
            result == STILL_RUNNING -> null
            // Already reaped: report a terminated-but-unknown status rather than pretending
            // it succeeded, which would let a failed command look clean.
            result == ALREADY_REAPED -> UNKNOWN_EXIT.also { cachedExitCode = it }
            else -> result.also { cachedExitCode = it }
        }
    }

    fun isAlive(): Boolean = !closed && exitCodeOrNull() == null

    /** Blocks until the child exits. Call from an IO dispatcher, never the main thread. */
    fun waitFor(): Int {
        cachedExitCode?.let { return it }
        val result = NativePty.nativeWaitFor(pid, blocking = true)
        val code = if (result == ALREADY_REAPED) UNKNOWN_EXIT else result
        cachedExitCode = code
        return code
    }

    /**
     * Closes the PTY and terminates the child.
     *
     * SIGHUP first, which is what a real terminal sends when its window closes and what shells
     * are written to handle. SIGKILL only for anything that ignores it.
     */
    fun close() {
        if (closed) return
        closed = true

        signal(PtySignal.Hangup)

        // The ParcelFileDescriptor owns the fd; closing it is what releases the PTY. The
        // streams are not closed individually because they share that one fd.
        runCatching { descriptor.close() }

        if (NativePty.nativeWaitFor(pid, blocking = false) == STILL_RUNNING) {
            NativePty.nativeSignalGroup(pid, PtySignal.Kill.number)
            NativePty.nativeWaitFor(pid, blocking = false)
        }
    }

    internal companion object {
        const val STILL_RUNNING = -1
        const val ALREADY_REAPED = -2

        /** Terminated, status unavailable. Not zero — that would read as success. */
        const val UNKNOWN_EXIT = 129

        /** Matches the exit codes defined in pty.c. */
        const val EXIT_CHDIR_FAILED = 126
        const val EXIT_EXEC_FAILED = 127
    }
}

/**
 * Signals a terminal actually needs.
 *
 * Numeric values are the Linux/bionic ones, which are stable across the ABIs we build for.
 */
enum class PtySignal(val number: Int) {
    Hangup(1),
    Interrupt(2),
    Quit(3),
    Kill(9),
    Terminate(15),
    Continue(18),
    Stop(19),
}

/**
 * Opens a PTY and starts a process in it.
 *
 * An interface so terminal session logic can be tested against a fake, without a device — the
 * same reasoning as `ProcessLauncher` in ADR-009.
 */
interface PtyLauncher {
    val isSupported: Boolean

    @Throws(IOException::class)
    fun open(
        argv: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
        rows: Int,
        cols: Int,
    ): PtyProcess
}
