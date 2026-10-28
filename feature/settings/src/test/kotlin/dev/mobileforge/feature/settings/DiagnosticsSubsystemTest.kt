package dev.mobileforge.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.ToolStatus
import org.junit.Test

/**
 * The diagnostics rows that used to be hard-coded "not implemented".
 *
 * The terminal and the toolchain both worked while this screen said they did not, which is the
 * one failure a diagnostics screen cannot have: it exists to be believed. These rows are now
 * derived from measurements, and the tests pin the three things that matter — a working
 * subsystem is never reported as missing, a missing one says what is missing, and an unknown
 * is never reported as working.
 */
class DiagnosticsSubsystemTest {

    private fun available(tool: DevTool) = tool to ToolStatus.Available("${tool.displayName} 1", "/bin/x")

    private fun probed(vararg tools: Pair<DevTool, ToolStatus>): Map<DevTool, ToolStatus> =
        DevTool.entries.associateWith<DevTool, ToolStatus> { ToolStatus.NotInstalled } + tools

    @Test fun `a fully installed toolchain is available`() {
        val report = toolchainSubsystem(
            probed(
                available(DevTool.Php), available(DevTool.Composer), available(DevTool.Node),
                available(DevTool.Npm), available(DevTool.Git),
            ),
        )

        assertThat(report.state).isEqualTo(SubsystemState.Available)
        assertThat(report.detail).contains("PHP")
        assertThat(report.detail).contains("Git")
    }

    @Test fun `a partly installed toolchain names what is installed and what is missing`() {
        val report = toolchainSubsystem(probed(available(DevTool.Php), available(DevTool.Composer)))

        assertThat(report.state).isEqualTo(SubsystemState.Degraded)
        assertThat(report.detail).contains("Installed: PHP, Composer")
        assertThat(report.detail).contains("Missing: Node.js, npm, Git")
        // A reader is told where to go, not just that something is wrong.
        assertThat(report.detail).contains("Toolchains")
    }

    @Test fun `an empty toolchain says nothing is installed instead of not implemented`() {
        val report = toolchainSubsystem(probed())

        assertThat(report.state).isEqualTo(SubsystemState.Degraded)
        assertThat(report.state).isNotEqualTo(SubsystemState.NotImplemented)
        assertThat(report.detail).contains("Nothing installed")
    }

    @Test fun `a tool the probe could not run counts as missing, not as working`() {
        val report = toolchainSubsystem(
            probed(
                available(DevTool.Php), available(DevTool.Composer), available(DevTool.Node),
                available(DevTool.Npm),
                DevTool.Git to ToolStatus.Unsupported("wrong ABI", "rebuild for arm64"),
            ),
        )

        assertThat(report.state).isEqualTo(SubsystemState.Degraded)
        assertThat(report.detail).contains("Missing: Git")
    }

    @Test fun `the shell does not count towards the toolchain`() {
        // Every device has a shell. If it counted, an empty toolchain would read as partly there.
        val report = toolchainSubsystem(probed(available(DevTool.Shell)))

        assertThat(report.detail).contains("Nothing installed")
    }

    @Test fun `a tool missing from the probe result is treated as missing`() {
        val report = toolchainSubsystem(emptyMap())

        assertThat(report.state).isEqualTo(SubsystemState.Degraded)
    }

    @Test fun `a loaded terminal library is reported as available`() {
        val report = terminalSubsystem(true)

        assertThat(report.state).isEqualTo(SubsystemState.Available)
        assertThat(report.state).isNotEqualTo(SubsystemState.NotImplemented)
    }

    @Test fun `a terminal library that did not load is degraded with the reason`() {
        val report = terminalSubsystem(false)

        assertThat(report.state).isEqualTo(SubsystemState.Degraded)
        assertThat(report.detail).contains("CPU architecture")
    }

    @Test fun `an unchecked terminal is never reported as working`() {
        val report = terminalSubsystem(null)

        assertThat(report.state).isNotEqualTo(SubsystemState.Available)
        assertThat(report.detail).isEqualTo("Not checked")
    }
}
