package dev.mobileforge.runtime.exec

/**
 * Builds the environment a development tool runs with.
 *
 * Android gives a process almost nothing useful: no `HOME` pointing anywhere writable, a `PATH`
 * containing only system directories, and no `TMPDIR`. Tools assume all three exist, so the
 * runtime supplies them explicitly rather than letting each tool fail in its own way.
 *
 * The prefix layout mirrors Termux's (`$PREFIX/bin`, `$PREFIX/lib`, `$PREFIX/etc`) because the
 * binaries we will bootstrap are built against exactly those paths — a package compiled with a
 * `DT_RUNPATH` of `/data/data/<pkg>/files/usr/lib` will not find its libraries anywhere else.
 * Diverging from that layout would mean rebuilding the whole toolchain.
 */
class RuntimeEnvironmentBuilder(
    private val environment: ExecEnvironment,
) {

    /** `$PREFIX` — the root of the installed toolchain. */
    val prefix: String get() = "${environment.filesDir}/usr"

    val binDir: String get() = "$prefix/bin"
    val libDir: String get() = "$prefix/lib"
    val etcDir: String get() = "$prefix/etc"
    val tmpDir: String get() = "$prefix/tmp"

    /** `$HOME` — kept OUTSIDE `$PREFIX` so reinstalling the toolchain cannot wipe user data. */
    val homeDir: String get() = "${environment.filesDir}/home"

    /**
     * Assembles the environment for one invocation.
     *
     * [command] supplies the real program path, which matters under system-linker exec: with
     * the linker as argv[0], `/proc/self/exe` points at the linker rather than the program.
     * Tools that locate their own installation that way would otherwise resolve to
     * `/system/bin/` and fail to find their standard library.
     */
    fun build(
        command: ResolvedCommand,
        workingDirectory: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("PREFIX", prefix)
        put("HOME", homeDir)
        put("PATH", pathValue())
        put("LD_LIBRARY_PATH", libDir)
        put("TMPDIR", tmpDir)
        put("TMP", tmpDir)
        put("PWD", workingDirectory)
        put("LANG", "en_US.UTF-8")
        put("SHELL", "$binDir/sh")

        // A terminal type tools recognise. Without it, anything using ncurses or readline
        // either refuses to run or emits raw escape codes.
        put("TERM", "xterm-256color")

        // Locates CA certificates for anything doing TLS (composer, npm, git).
        put("SSL_CERT_FILE", "$etcDir/tls/cert.pem")

        if (command.strategy is ExecStrategy.SystemLinker) {
            // Specific to THIS command: with the linker as argv[0], /proc/self/exe points at
            // the linker, so the real path is carried in the environment instead. Mirrors
            // termux-exec's variable so ported packages that look for it work unchanged.
            put(PROC_SELF_EXE_VAR, command.realProgramPath)
        }

        /*
         * Load the execve() interposer into every descendant.
         *
         * Set UNCONDITIONALLY, and that is the whole point. An earlier version set it only
         * when the parent itself used system-linker exec, which meant it was never set for the
         * terminal — `/system/bin/sh` lives on the system partition and runs directly. The
         * shell then exec'd our binaries through the kernel's normal path and W^X refused
         * them, observed on device as:
         *
         *     /system/bin/sh: .../usr/bin/mf-doctor: Permission denied
         *
         * The interposer exists to fix what CHILDREN do, so it has to be present regardless of
         * how the parent was launched. libmfexec rewrites execve() for paths under
         * MOBILEFORGE_PREFIX and passes everything else through untouched.
         * See docs/adr/ADR-011-toolchain-strategy.md.
         */
        put("LD_PRELOAD", "${environment.nativeLibraryDir}/$INTERPOSER_LIBRARY")
        put(PREFIX_VAR, prefix)

        // Caller overrides last, so a tool runtime can pin a version-specific variable.
        putAll(extra)
    }

    /**
     * `$PATH`, toolchain first.
     *
     * `/system/bin` is retained at the end so platform utilities remain reachable, but it is
     * deliberately last: when both exist, the bootstrapped GNU coreutils should win over
     * Android's cut-down toybox versions, whose flags differ enough to break scripts.
     */
    private fun pathValue(): String = listOf(binDir, "/system/bin", "/system/xbin")
        .joinToString(":")

    companion object {
        const val PROC_SELF_EXE_VAR = "TERMUX_EXEC__PROC_SELF_EXE"

        /** Read by libmfexec to decide which paths it should rewrite. */
        const val PREFIX_VAR = "MOBILEFORGE_PREFIX"

        /** Ships in the APK native library directory, which is executable and read-only. */
        const val INTERPOSER_LIBRARY = "libmfexec.so"
    }
}
