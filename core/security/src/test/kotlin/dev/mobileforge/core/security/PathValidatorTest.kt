package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import org.junit.Test

/**
 * Adversarial tests for workspace path containment.
 *
 * Every case here is an escape attempt that has appeared in real path-traversal advisories. If
 * one of these regresses, a malicious archive or an AI agent can read or write outside the
 * project, so these are blocking tests, not nice-to-haves.
 */
class PathValidatorTest {

    private val validator = PathValidator()

    // ---------- paths that must be accepted ----------

    @Test
    fun `accepts a simple relative path`() {
        assertAccepted("routes/web.php", expected = "routes/web.php")
    }

    @Test
    fun `accepts an empty path as the workspace root`() {
        assertAccepted("", expected = "")
    }

    @Test
    fun `collapses redundant current-directory segments`() {
        assertAccepted("./app/./Models/User.php", expected = "app/Models/User.php")
    }

    @Test
    fun `allows parent traversal that stays inside the root`() {
        assertAccepted("app/Models/../Http/Kernel.php", expected = "app/Http/Kernel.php")
    }

    @Test
    fun `collapses duplicate separators`() {
        assertAccepted("app//Models///User.php", expected = "app/Models/User.php")
    }

    @Test
    fun `accepts a filename that merely starts with dots`() {
        // "...eslintrc" is a legitimate filename, not a traversal attempt.
        assertAccepted("...eslintrc", expected = "...eslintrc")
    }

    @Test
    fun `accepts a dotfile`() {
        assertAccepted(".env.example", expected = ".env.example")
    }

    // ---------- escapes that must be rejected ----------

    @Test
    fun `rejects simple parent escape`() {
        assertRejected("../etc/passwd")
    }

    @Test
    fun `rejects deep parent escape`() {
        assertRejected("../../../../data/data/com.other.app/databases/secrets.db")
    }

    @Test
    fun `rejects escape hidden mid-path`() {
        assertRejected("app/Models/../../../outside.txt")
    }

    @Test
    fun `rejects escape that dips out and back in`() {
        // Normalising left to right, this leaves the root before returning — still an escape.
        assertRejected("../workspace-two/app.php")
    }

    @Test
    fun `rejects absolute unix path`() {
        assertRejected("/etc/passwd")
    }

    @Test
    fun `rejects windows drive-qualified path`() {
        assertRejected("C:/Windows/System32/drivers/etc/hosts")
    }

    @Test
    fun `rejects backslash traversal`() {
        // A Windows-authored archive can carry backslash separators; treating them as ordinary
        // filename characters would let this straight through.
        assertRejected("..\\..\\etc\\passwd")
    }

    @Test
    fun `rejects mixed separator traversal`() {
        assertRejected("app/..\\../etc/passwd")
    }

    @Test
    fun `rejects backslash absolute path`() {
        assertRejected("\\etc\\passwd")
    }

    @Test
    fun `rejects NUL byte injection`() {
        // Classic truncation attack against native path handling.
        assertRejected("safe.txt\u0000../../etc/passwd")
    }

    @Test
    fun `rejection is categorised as a security error and offers recovery`() {
        val result = validator.validateRelative("../escape")
        val error = (result as AppResult.Failure).error
        assertThat(error.category).isEqualTo(ErrorCategory.Security)
        assertThat(error.recovery).isNotNull()
    }

    @Test
    fun `rejection detail truncates attacker-controlled input`() {
        val long = "../".repeat(500)
        val error = (validator.validateRelative(long) as AppResult.Failure).error
        assertThat(error.detail!!.length).isLessThan(300)
    }

    // ---------- resolveWithin ----------

    @Test
    fun `resolveWithin joins against the root`() {
        val result = validator.resolveWithin("/data/ws/app", "routes/web.php")
        assertThat(result.getOrNull()).isEqualTo("/data/ws/app/routes/web.php")
    }

    @Test
    fun `resolveWithin returns the root for an empty path`() {
        val result = validator.resolveWithin("/data/ws/app", "")
        assertThat(result.getOrNull()).isEqualTo("/data/ws/app")
    }

    @Test
    fun `resolveWithin refuses an escaping path`() {
        val result = validator.resolveWithin("/data/ws/app", "../other/file")
        assertThat(result.isSuccess).isFalse()
    }

    // ---------- isContained (post-canonicalisation symlink defence) ----------

    @Test
    fun `isContained accepts the root itself`() {
        assertThat(validator.isContained("/data/ws/app", "/data/ws/app")).isTrue()
    }

    @Test
    fun `isContained accepts a descendant`() {
        assertThat(validator.isContained("/data/ws/app", "/data/ws/app/routes/web.php")).isTrue()
    }

    @Test
    fun `isContained rejects a sibling with a shared string prefix`() {
        // The classic prefix bug: "/data/ws/app-evil" starts with "/data/ws/app" as a string
        // but is a different directory. Segment-aware comparison is what stops this.
        assertThat(validator.isContained("/data/ws/app", "/data/ws/app-evil/x")).isFalse()
    }

    @Test
    fun `isContained rejects a path outside the root`() {
        assertThat(validator.isContained("/data/ws/app", "/data/ws/other/file")).isFalse()
    }

    // ---------- helpers ----------

    private fun assertAccepted(input: String, expected: String) {
        val result = validator.validateRelative(input)
        assertThat(result.errorOrNull()).isNull()
        assertThat(result.getOrNull()).isEqualTo(expected)
    }

    private fun assertRejected(input: String) {
        val result = validator.validateRelative(input)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.category).isEqualTo(ErrorCategory.Security)
    }
}
