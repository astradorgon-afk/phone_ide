package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess

/**
 * Reads the first bytes of a file so the builder can tell a script from an ELF binary.
 *
 * An interface so the whole argv-construction path is testable with in-memory headers — no
 * temp files, no platform assumptions.
 */
fun interface FileHeaderReader {
    /** Returns up to [maxBytes] from the start of the file, or null if it cannot be read. */
    fun readHeader(absolutePath: String, maxBytes: Int): ByteArray?
}

/**
 * Turns a logical invocation into the argv the OS will actually receive.
 *
 * This is the single place where the Android exec workaround is applied. Everything above it
 * (ProcessManager, tool runtimes, the terminal) works in terms of "run this program with these
 * arguments" and never learns that a linker is involved — which is what makes the mechanism
 * replaceable if the platform changes again.
 *
 * The two transformations, composed:
 *
 * 1. **Shebang**: under system-linker exec the kernel never sees the script, so its `#!`
 *    handling does not run. We perform it ourselves.
 * 2. **Linker**: the resolved program is prefixed with the system linker when W^X applies.
 *
 * Order matters. A script's *interpreter* is what must be linker-exec'd, not the script.
 */
class ExecCommandBuilder(
    private val strategySelector: ExecStrategySelector,
    private val headerReader: FileHeaderReader,
) {

    /**
     * Builds the final argv.
     *
     * [absoluteExecutablePath] must be absolute — the system linker will not resolve a
     * relative path, and silently accepting one would produce a baffling failure later.
     */
    fun build(
        absoluteExecutablePath: String,
        arguments: List<String>,
    ): AppResult<ResolvedCommand> {
        val header = headerReader.readHeader(absoluteExecutablePath, HEADER_BYTES)
            ?: return notReadable(absoluteExecutablePath)

        val shebang = ShebangResolver.parse(header)

        return if (shebang == null) {
            buildForBinary(absoluteExecutablePath, arguments, header)
        } else {
            buildForScript(absoluteExecutablePath, arguments, shebang)
        }
    }

    private fun buildForBinary(
        path: String,
        arguments: List<String>,
        header: ByteArray,
    ): AppResult<ResolvedCommand> {
        val strategy = strategySelector.strategyFor(path)

        if (strategy is ExecStrategy.SystemLinker && ShebangResolver.isElf(header) &&
            !ShebangResolver.isDynamicElf(header)
        ) {
            // Caught here rather than at exec time so the message names the real cause.
            return AppError(
                category = ErrorCategory.Unavailable,
                message = "That program cannot be started on this device.",
                detail = "'${path.substringAfterLast('/')}' is a statically linked binary. " +
                    "Android requires programs in app storage to be launched through the " +
                    "system linker, which only supports dynamically linked executables.",
                recovery = "Use a dynamically linked build of this tool.",
            ).asFailure()
        }

        return assemble(strategy, program = path, programArgs = arguments)
    }

    /**
     * Resolves a script through its interpreter.
     *
     * Only one level of indirection is followed, matching the kernel: an interpreter that is
     * itself a script is an error on Linux, and pretending otherwise here would diverge from
     * the behaviour every other tool assumes.
     */
    private fun buildForScript(
        scriptPath: String,
        arguments: List<String>,
        shebang: Shebang,
    ): AppResult<ResolvedCommand> {
        val interpreterHeader = headerReader.readHeader(shebang.interpreter, HEADER_BYTES)
            ?: return AppError(
                category = ErrorCategory.Unavailable,
                message = "The interpreter for that script is missing.",
                detail = "'${scriptPath.substringAfterLast('/')}' requires " +
                    "'${shebang.interpreter}', which is not installed.",
                recovery = "Install the interpreter, or check the script's #! line.",
            ).asFailure()

        if (ShebangResolver.parse(interpreterHeader) != null) {
            return AppError(
                category = ErrorCategory.Unavailable,
                message = "That script cannot be started.",
                detail = "Its interpreter '${shebang.interpreter}' is itself a script. " +
                    "Nested interpreter scripts are not supported, on Linux or here.",
                recovery = "Point the #! line at a real program.",
            ).asFailure()
        }

        val strategy = strategySelector.strategyFor(shebang.interpreter)

        // argv becomes: <interpreter> [shebang-arg] <script> <user args...>
        val programArgs = buildList {
            shebang.argument?.let(::add)
            add(scriptPath)
            addAll(arguments)
        }

        return assemble(strategy, program = shebang.interpreter, programArgs = programArgs)
    }

    private fun assemble(
        strategy: ExecStrategy,
        program: String,
        programArgs: List<String>,
    ): AppResult<ResolvedCommand> = when (strategy) {
        is ExecStrategy.Unsupported -> AppError(
            category = ErrorCategory.Unavailable,
            message = "That program cannot be started on this device.",
            detail = strategy.reason,
            recovery = strategy.recovery,
        ).asFailure()

        ExecStrategy.Direct -> ResolvedCommand(
            argv = listOf(program) + programArgs,
            strategy = strategy,
            realProgramPath = program,
        ).asSuccess()

        is ExecStrategy.SystemLinker -> ResolvedCommand(
            // The linker takes the program as its first argument. argv[0] as seen by the
            // process is still the program path, which is what tools expect.
            argv = listOf(strategy.linkerPath, program) + programArgs,
            strategy = strategy,
            realProgramPath = program,
        ).asSuccess()
    }

    private fun notReadable(path: String): AppResult<Nothing> = AppError(
        category = ErrorCategory.Unavailable,
        message = "That program is not installed.",
        detail = "'$path' could not be read.",
        recovery = "Install the tool from Settings, or check the path.",
    ).asFailure()

    private companion object {
        /** Enough for a full shebang line (127) plus an ELF header. */
        const val HEADER_BYTES = 160
    }
}

/**
 * A fully-resolved invocation, ready to hand to the OS.
 *
 * [realProgramPath] is retained separately because under system-linker exec `/proc/self/exe`
 * reports the LINKER, not the program. Tools that locate themselves that way (Node does) need
 * the true path injected into their environment — see [RuntimeEnvironmentBuilder].
 */
data class ResolvedCommand(
    val argv: List<String>,
    val strategy: ExecStrategy,
    val realProgramPath: String,
)
