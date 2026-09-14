package dev.mobileforge.runtime.exec

/**
 * How a given binary will actually be handed to the OS.
 *
 * This type exists so the decision is explicit, inspectable and testable rather than buried in
 * an `if` inside a launcher. The diagnostics screen shows which strategy a tool resolved to,
 * because "PHP failed to start" is far less useful than "PHP resolved to system-linker exec
 * and the linker rejected it".
 */
sealed interface ExecStrategy {

    /**
     * Execute the file directly.
     *
     * Valid when the binary lives somewhere executable — the native library directory, or the
     * system partition — or when W^X does not apply to this app on this device.
     */
    data object Direct : ExecStrategy

    /**
     * Execute the system dynamic linker, passing the real program as its first argument.
     *
     * ```
     * execve("/system/bin/linker64", ["/data/data/dev.mobileforge/files/usr/bin/php", "-v"])
     *                  ^ what the kernel sees          ^ what actually runs
     * ```
     *
     * The app-data file needs to be READABLE, not executable, so W^X is satisfied. This is the
     * mechanism `termux-exec` uses and how Termux ships on Google Play under a current target
     * SDK. See docs/adr/ADR-002-runtime-strategy.md.
     */
    data class SystemLinker(val linkerPath: String) : ExecStrategy

    /**
     * No strategy can run this binary here, with the concrete reason.
     *
     * A first-class outcome rather than an exception: "cannot run" is a normal answer on a
     * platform this constrained, and the user is owed the reason.
     */
    data class Unsupported(val reason: String, val recovery: String?) : ExecStrategy
}

/**
 * Chooses an [ExecStrategy] for a binary.
 *
 * Pure and total: it never touches the filesystem and never throws. Whether the file *exists*
 * is a separate question answered by the launcher — this decides only how it would be invoked.
 */
class ExecStrategySelector(
    private val environment: ExecEnvironment,
) {

    fun strategyFor(absoluteExecutablePath: String): ExecStrategy {
        val path = absoluteExecutablePath.replace('\\', '/')

        if (!path.startsWith("/")) {
            return ExecStrategy.Unsupported(
                reason = "The executable path is not absolute.",
                recovery = "Use a full path. The system linker cannot resolve relative paths.",
            )
        }

        // Anything on a read-only, already-executable location runs directly. This covers
        // /system/bin/sh and binaries shipped inside the APK's native library directory.
        if (isInherentlyExecutableLocation(path)) return ExecStrategy.Direct

        // Outside those locations, W^X is what decides.
        if (!environment.enforcesWriteXorExecute) return ExecStrategy.Direct

        if (!environment.supportsSystemLinkerExec) {
            return ExecStrategy.Unsupported(
                reason = "This Android version blocks running programs from app storage and " +
                    "does not support the system-linker workaround.",
                recovery = "Android 10 or newer is required to run development tools.",
            )
        }

        return ExecStrategy.SystemLinker(environment.systemLinkerPath)
    }

    private fun isInherentlyExecutableLocation(path: String): Boolean {
        val nativeLibDir = environment.nativeLibraryDir.replace('\\', '/').trimEnd('/')
        if (nativeLibDir.isNotEmpty() && path.startsWith("$nativeLibDir/")) return true
        return SYSTEM_PREFIXES.any { path.startsWith(it) }
    }

    private companion object {
        /** Read-only partitions. Files here are executable and are not app-writable. */
        val SYSTEM_PREFIXES = listOf("/system/", "/apex/", "/vendor/", "/product/")
    }
}
