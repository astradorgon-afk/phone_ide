package dev.mobileforge.core.security

import dev.mobileforge.core.model.WorkspaceTrust

/**
 * The capability ceiling implied by a workspace's trust level.
 *
 * The effective permission for any operation is min(user grant, this ceiling). Trust bounds
 * what a grant can mean; it is not itself a grant.
 */
object TrustPolicy {

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
