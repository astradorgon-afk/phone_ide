package dev.mobileforge.runtime.pty

import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * The real [PtyLauncher], backed by `forkpty` in `libmfpty.so`.
 *
 * [isSupported] is false when the native library did not load — a device with an ABI we did not
 * build for. The terminal then reports that as a capability gap rather than crashing on first
 * use, matching how the runtime probe treats missing tools.
 */
class NativePtyLauncher : PtyLauncher {

    override val isSupported: Boolean get() = NativePty.isAvailable

    @Throws(IOException::class)
    override fun open(
        argv: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
        rows: Int,
        cols: Int,
    ): PtyProcess {
        if (!isSupported) {
            throw IOException(
                "The terminal is not supported on this device: the native PTY library " +
                    "could not be loaded for this CPU architecture.",
            )
        }
        require(argv.isNotEmpty()) { "argv must not be empty" }

        val pidOut = IntArray(1)
        val fd = NativePty.nativeForkPty(
            argv = argv.toTypedArray(),
            cwd = workingDirectory,
            // execve takes NAME=VALUE strings, not a map.
            env = environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
            rows = rows.coerceAtLeast(1),
            cols = cols.coerceAtLeast(1),
            pidOut = pidOut,
        )

        if (fd < 0) {
            throw IOException("forkpty failed for '${argv.first()}'")
        }

        // adoptFd takes ownership: the ParcelFileDescriptor closes the fd, so nothing else may.
        // If adoption itself fails we must close the fd here or leak it.
        val descriptor = try {
            ParcelFileDescriptor.adoptFd(fd)
        } catch (e: Exception) {
            NativePty.nativeClose(fd)
            NativePty.nativeSignalGroup(pidOut[0], PtySignal.Kill.number)
            throw IOException("Could not adopt the PTY file descriptor", e)
        }

        return PtyProcess(masterFd = fd, pid = pidOut[0], descriptor = descriptor)
    }
}
