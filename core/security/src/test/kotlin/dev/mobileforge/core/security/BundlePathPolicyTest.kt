package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BundlePathPolicyTest {
    @Test fun `rejects aliases platform paths and traversal`() {
        for (path in listOf("", "/bin/tool", "../evil", "bin/../evil", "./bin/x", "bin//x",
            "bin\\x", "C:/x", "bin/x:y", "bin/\u0000x", "bin/\nx")) {
            assertThat(BundlePathPolicy.isSafeEntry(path)).isFalse()
        }
    }
    @Test fun `accepts portable file and directory paths`() {
        for (path in listOf("bin/tool", "lib/", "share/licenses/MIT.txt", "bin/tool-name")) {
            assertThat(BundlePathPolicy.isSafeEntry(path)).isTrue()
        }
    }
    @Test fun `identifiers cannot become paths`() {
        for (id in listOf("../escape", "/absolute", "a/b", "a\\b", "", ".", "..")) {
            assertThat(BundlePathPolicy.isSafeIdentifier(id)).isFalse()
        }
        assertThat(BundlePathPolicy.isSafeIdentifier("1.2.3+patch-4")).isTrue()
    }
}
