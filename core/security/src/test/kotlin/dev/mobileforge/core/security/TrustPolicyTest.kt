package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.ErrorCategory
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

    @Test
    fun `an untrusted workspace is refused execution with a security error`() {
        val refusal = TrustPolicy.executionRefusal(WorkspaceTrust.Untrusted)

        assertThat(refusal).isNotNull()
        assertThat(refusal!!.category).isEqualTo(ErrorCategory.Security)
        // The user is told what to do next, not just that they were stopped.
        assertThat(refusal.recovery).contains("Trust the project")
    }

    @Test
    fun `a trusted workspace is not refused execution`() {
        assertThat(TrustPolicy.executionRefusal(WorkspaceTrust.Trusted)).isNull()
    }

    @Test
    fun `the refusal and the ceiling cannot disagree for any trust level`() {
        // The view models ask executionRefusal; the ceiling is the documented source of truth.
        // If a new WorkspaceTrust tier were added and only one of them updated, this fails.
        WorkspaceTrust.entries.forEach { trust ->
            val refused = TrustPolicy.executionRefusal(trust) != null
            assertThat(refused).isEqualTo(!TrustPolicy.ceilingFor(trust).canExecuteCommands)
        }
    }
}
