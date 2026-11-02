package dev.mobileforge.core.security

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.model.WorkspaceTrust

/**
 * The capability ceiling implied by a workspace's trust level.
 *
 * The effective permission for any operation is min(user grant, this ceiling). Trust bounds
 * what a grant can mean; it is not itself a grant.
 */
object TrustPolicy {

    /**
     * Why this workspace may not run anything, or null when it may.
     *
     * Every place that starts a process on a workspace's behalf — the shell, Artisan, a dev
     * server — asks this at the moment it acts and refuses with the returned error. One question
     * with one answer, so the gates cannot drift apart: the UI explaining a restricted pane and
     * the view model refusing to run are reading the same ceiling.
     *
     * "Running" includes anything that executes the project's own code. `php artisan --version`
     * looks like a harmless read, but it boots the application and so runs the project's service
     * providers; it is execution for the purposes of this check.
     */
    fun executionRefusal(trust: WorkspaceTrust): AppError? =
        if (ceilingFor(trust).canExecuteCommands) {
            null
        } else {
            AppError(
                category = ErrorCategory.Security,
                message = "This project is open in restricted mode.",
                detail = "Untrusted projects cannot run commands, open a shell or start a " +
                    "server: a project you did not write can carry scripts that run the " +
                    "moment they are executed.",
                recovery = "Trust the project if you know where it came from, then try again.",
            )
        }

    fun ceilingFor(trust: WorkspaceTrust): CapabilityCeiling = when (trust) {
        WorkspaceTrust.Untrusted -> CapabilityCeiling(
            canReadFiles = true,
            canWriteFiles = false,
            canDeleteFiles = false,
            canExecuteCommands = false,
            canInstallPackages = false,
            canAccessNetwork = false,
            canGitCommit = false,
            canGitPush = false,
        )

        WorkspaceTrust.Trusted -> CapabilityCeiling(
            canReadFiles = true,
            canWriteFiles = true,
            canDeleteFiles = true,
            canExecuteCommands = true,
            canInstallPackages = true,
            canAccessNetwork = true,
            canGitCommit = true,
            canGitPush = true,
        )
    }
}

/**
 * What a workspace is *allowed to permit*. Every flag defaults to the safe value so that a
 * future capability added to this type is denied until someone deliberately enables it.
 */
data class CapabilityCeiling(
    val canReadFiles: Boolean = false,
    val canWriteFiles: Boolean = false,
    val canDeleteFiles: Boolean = false,
    val canExecuteCommands: Boolean = false,
    val canInstallPackages: Boolean = false,
    val canAccessNetwork: Boolean = false,
    val canGitCommit: Boolean = false,
    val canGitPush: Boolean = false,
)
