package dev.mobileforge.runtime

import dev.mobileforge.core.common.AppResult
import dev.mobileforge.feature.terminal.ShellCommand
import dev.mobileforge.runtime.exec.ExecCommandBuilder
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import java.io.File

/**
 * Resolves which shell the terminal should run, and how to invoke it.
 *
 * This lives at the composition root because it is the seam between two subsystems: it asks
 * `ExecCommandBuilder` for a resolved argv (ADR-009) so the terminal itself never learns about
 * the system-linker workaround, and it asks `RuntimeEnvironmentBuilder` for the environment.
 *
 * **Shell selection is honest about what is installed.** The bundled shell under `$PREFIX/bin`
 * is preferred once a toolchain exists; until then it falls back to Android's own
 * `/system/bin/sh`, which is a real shell — just a minimal one, with none of our tools on its
 * PATH. The user gets a working terminal now rather than an error saying "install a toolchain
 * first", and the header says which shell they got.
 */
class ShellCommandFactory(
    private val commandBuilder: ExecCommandBuilder,
    private val environmentBuilder: RuntimeEnvironmentBuilder,
) {

    fun create(workingDirectory: String): AppResult<ShellCommand> {
        val bundled = File("${environmentBuilder.binDir}/sh")
        val useBundled = bundled.isFile

        val shellPath = if (useBundled) bundled.absolutePath else SYSTEM_SHELL

        // Even /system/bin/sh goes through the builder rather than being exec'd directly, so
        // there is exactly one path from "a program to run" to "an argv", and it is the tested
        // one. The builder correctly resolves this to Direct execution.
        return when (val resolved = commandBuilder.build(shellPath, LOGIN_ARGS)) {
            is AppResult.Failure -> resolved
            is AppResult.Success -> {
                val command = resolved.value
                AppResult.Success(
                    ShellCommand(
                        argv = command.argv,
                        workingDirectory = workingDirectory,
                        environment = environmentBuilder.build(command, workingDirectory),
                        displayName = if (useBundled) "sh" else "sh (Android system shell)",
                    ),
                )
            }
        }
    }

    private companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"

        /**
         * Interactive, but NOT a login shell.
         *
         * `-l` would source profile scripts that do not exist yet and, once a toolchain is
         * installed, would run whatever a cloned project dropped into them — a trust problem
         * before it is a convenience.
         */
        val LOGIN_ARGS = listOf("-i")
    }
}
