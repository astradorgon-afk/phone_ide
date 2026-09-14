package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Secret leakage is rated Critical in RISKS.md (RISK-013), so redaction is tested against the
 * concrete credential shapes this IDE will actually encounter in a Laravel project.
 */
class SecretRedactorTest {

    @Test
    fun `redacts a laravel app key`() {
        val input = "APP_KEY=base64:kJ8s7fJ2mQ1pLx9vZ0aB3cD4eF5gH6iJ7kL8mN9oP0Q="
        val output = SecretRedactor.redact(input)
        assertThat(output).doesNotContain("kJ8s7fJ2mQ1pLx9vZ0aB3cD4eF5gH6iJ7kL8mN9oP0Q=")
        assertThat(output).contains(SecretRedactor.PLACEHOLDER)
    }

    @Test
    fun `redacts a database password from a dotenv file`() {
        val input = listOf(
            "DB_CONNECTION=mysql",
            "DB_HOST=127.0.0.1",
            "DB_DATABASE=forge",
            "DB_PASSWORD=sup3rs3cr3tvalue",
        ).joinToString("\n")

        val output = SecretRedactor.redact(input)

        assertThat(output).doesNotContain("sup3rs3cr3tvalue")
        // Non-sensitive configuration must survive, otherwise redaction destroys usefulness.
        assertThat(output).contains("DB_HOST=127.0.0.1")
        assertThat(output).contains("DB_DATABASE=forge")
    }

    @Test
    fun `redacts anthropic api key by shape alone`() {
        val input = "x-api-key: sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        assertThat(SecretRedactor.redact(input)).doesNotContain("sk-ant-api03-AbCdEfGh")
    }

    @Test
    fun `redacts github personal access token by shape alone`() {
        val input = "origin https://ghp_AbCdEfGhIjKlMnOpQrStUvWxYz01234567@github.com/x/y"
        assertThat(SecretRedactor.redact(input))
            .doesNotContain("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz01234567")
    }

    @Test
    fun `redacts google api key by shape alone`() {
        val input = "GOOGLE_MAPS=AIzaSyD-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        assertThat(SecretRedactor.redact(input))
            .doesNotContain("AIzaSyD-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
    }

    @Test
    fun `redacts bearer authorization header`() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefghij"
        assertThat(SecretRedactor.redact(input))
            .doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
    }

    @Test
    fun `redacts an entire pem private key block`() {
        val input = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtz",
            "c2gtZWQyNTUxOQAAACDlM3nOtSecretKeyMaterialHereDoNotLeakAAAAAAAA",
            "-----END OPENSSH PRIVATE KEY-----",
        ).joinToString("\n")

        val output = SecretRedactor.redact(input)

        assertThat(output).doesNotContain("SecretKeyMaterialHere")
        assertThat(output).doesNotContain("BEGIN OPENSSH PRIVATE KEY")
    }

    @Test
    fun `redacts double-quoted values`() {
        val input = "API_SECRET=\"quoted-secret-value-here\""
        assertThat(SecretRedactor.redact(input)).doesNotContain("quoted-secret-value-here")
    }

    @Test
    fun `redacts single-quoted values`() {
        val input = "API_SECRET='another-secret-value'"
        assertThat(SecretRedactor.redact(input)).doesNotContain("another-secret-value")
    }

    @Test
    fun `leaves ordinary source code untouched`() {
        val input = listOf(
            "public function index()",
            "{",
            "    return view('welcome');",
            "}",
        ).joinToString("\n")

        assertThat(SecretRedactor.redact(input)).isEqualTo(input)
    }

    @Test
    fun `containsSecret detects a token assignment`() {
        assertThat(SecretRedactor.containsSecret("token=abcdefghijklmnop")).isTrue()
    }

    @Test
    fun `containsSecret is false for plain prose`() {
        assertThat(SecretRedactor.containsSecret("The password reset flow needs a test."))
            .isFalse()
    }

    @Test
    fun `redaction is idempotent`() {
        val once = SecretRedactor.redact("API_KEY=abcdef1234567890")
        assertThat(SecretRedactor.redact(once)).isEqualTo(once)
    }

    @Test
    fun `handles empty input`() {
        assertThat(SecretRedactor.redact("")).isEmpty()
    }
}
