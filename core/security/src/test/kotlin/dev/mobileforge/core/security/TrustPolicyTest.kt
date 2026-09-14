package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.model.WorkspaceTrust
import org.junit.Test

class TrustPolicyTest {

    @Test
    fun `untrusted workspace cannot execute commands`() {
        val ceiling = TrustPolicy.ceilingFor(WorkspaceTrust.Untrusted)
        assertThat(ceiling.canExecuteCommands).isFalse()
        assertThat(ceiling.canInstallPackages).isFalse()
        assertThat(ceiling.canAccessNetwork).isFalse()
        assertThat(ceiling.canGitPush).isFalse()
    }

    @Test
    fun `untrusted workspace can still be read`() {
        // Browsing a cloned repo before trusting it is the entire point of restricted mode.
        assertThat(TrustPolicy.ceilingFor(WorkspaceTrust.Untrusted).canReadFiles).isTrue()
    }

    @Test
    fun `untrusted workspace cannot be written to`() {
        assertThat(TrustPolicy.ceilingFor(WorkspaceTrust.Untrusted).canWriteFiles).isFalse()
    }

    @Test
    fun `trusted workspace raises the ceiling`() {
        val ceiling = TrustPolicy.ceilingFor(WorkspaceTrust.Trusted)
        assertThat(ceiling.canExecuteCommands).isTrue()
        assertThat(ceiling.canGitPush).isTrue()
        assertThat(ceiling.canWriteFiles).isTrue()
    }

    @Test
    fun `capability ceiling defaults to fully denied`() {
        // A capability added to CapabilityCeiling later must default to denied, so that
        // forgetting to set it fails closed rather than open.
        val fresh = CapabilityCeiling()
        assertThat(fresh.canReadFiles).isFalse()
        assertThat(fresh.canWriteFiles).isFalse()
        assertThat(fresh.canExecuteCommands).isFalse()
        assertThat(fresh.canAccessNetwork).isFalse()
        assertThat(fresh.canInstallPackages).isFalse()
        assertThat(fresh.canGitPush).isFalse()
    }

    @Test
    fun `workspace trust exposes consistent convenience flags`() {
        assertThat(WorkspaceTrust.Untrusted.allowsExecution).isFalse()
        assertThat(WorkspaceTrust.Untrusted.allowsNetwork).isFalse()
        assertThat(WorkspaceTrust.Untrusted.allowsGitPush).isFalse()
        assertThat(WorkspaceTrust.Trusted.allowsExecution).isTrue()
    }
}
