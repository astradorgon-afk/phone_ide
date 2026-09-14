package dev.mobileforge.runtime.laravel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `.env` parsing, masking and round-tripping.
 *
 * Two failure modes are worth more attention than the happy path.
 *
 * A `.env` is hand-maintained — comments, grouping, deliberate blank lines — so **an edit that
 * silently reformats the file destroys the user's work**, and they will not notice until a diff
 * days later. Most of these tests are about preserving what was not edited.
 *
 * And `APP_KEY` is the most sensitive value in a Laravel project. The brief forbids displaying
 * secrets automatically, so masking is asserted per key rather than assumed.
 */
class EnvFileTest {

    private val sample = """
        APP_NAME=Laravel
        APP_ENV=local
        APP_KEY=base64:2b7eXAMPLEkeyMATERIALhere==
        APP_DEBUG=true

        # Database
        DB_CONNECTION=sqlite
        DB_PASSWORD=hunter2
    """.trimIndent()

    // --- Parsing ------------------------------------------------------------------

    @Test
    fun `reads keys and values`() {
        val env = EnvFile.parse(sample)

        assertThat(env["APP_NAME"]?.value).isEqualTo("Laravel")
        assertThat(env["DB_CONNECTION"]?.value).isEqualTo("sqlite")
        assertThat(env["MISSING"]).isNull()
    }

    @Test
    fun `keeps entries in file order`() {
        val keys = EnvFile.parse(sample).entries.map { it.key }
        assertThat(keys.first()).isEqualTo("APP_NAME")
        assertThat(keys.last()).isEqualTo("DB_PASSWORD")
    }

    @Test
    fun `strips quotes from values`() {
        val env = EnvFile.parse("""APP_NAME="My App"${"\n"}OTHER='single'""")

        assertThat(env["APP_NAME"]?.value).isEqualTo("My App")
        assertThat(env["OTHER"]?.value).isEqualTo("single")
    }

    @Test
    fun `a trailing comment is not part of the value`() {
        val env = EnvFile.parse("APP_ENV=local # not production")
        assertThat(env["APP_ENV"]?.value).isEqualTo("local")
    }

    @Test
    fun `a hash inside a value is not a comment`() {
        // Laravel keys are base64 and can contain '#'. Treating it as a comment would silently
        // truncate the application key, which breaks every encrypted value in the app.
        val env = EnvFile.parse("APP_KEY=base64:ab#cd")
        assertThat(env["APP_KEY"]?.value).isEqualTo("base64:ab#cd")
    }

    @Test
    fun `an empty value is read as empty, not missing`() {
        val env = EnvFile.parse("DB_PASSWORD=")
        assertThat(env["DB_PASSWORD"]).isNotNull()
        assertThat(env["DB_PASSWORD"]?.value).isEmpty()
    }

    // --- Round-tripping -----------------------------------------------------------

    @Test
    fun `parsing and rendering an untouched file changes nothing`() {
        assertThat(EnvFile.parse(sample).render()).isEqualTo(sample)
    }

    @Test
    fun `editing one value leaves every other line byte-identical`() {
        val edited = EnvFile.parse(sample).withValue("APP_ENV", "production").render()

        val before = sample.split("\n")
        val after = edited.split("\n")
        assertThat(after).hasSize(before.size)
        before.indices.filterNot { before[it].startsWith("APP_ENV=") }.forEach { index ->
            assertThat(after[index]).isEqualTo(before[index])
        }
        assertThat(edited).contains("APP_ENV=production")
    }

    @Test
    fun `comments and blank lines survive an edit`() {
        val edited = EnvFile.parse(sample).withValue("DB_PASSWORD", "newpass").render()

        assertThat(edited).contains("# Database")
        assertThat(edited.split("\n").count { it.isEmpty() })
            .isEqualTo(sample.split("\n").count { it.isEmpty() })
    }

    @Test
    fun `an existing quoting style is kept`() {
        val edited = EnvFile.parse("""APP_NAME="Old Name"""").withValue("APP_NAME", "New Name")
        assertThat(edited.render()).isEqualTo("""APP_NAME="New Name"""")
    }

    @Test
    fun `a value needing quotes gets them`() {
        val edited = EnvFile.parse("APP_NAME=Laravel").withValue("APP_NAME", "My App")
        assertThat(edited.render()).isEqualTo("""APP_NAME="My App"""")
    }

    @Test
    fun `a trailing comment survives a value change`() {
        val edited = EnvFile.parse("APP_ENV=local # keep me").withValue("APP_ENV", "production")
        assertThat(edited.render()).isEqualTo("APP_ENV=production # keep me")
    }

    @Test
    fun `a new key is appended`() {
        val edited = EnvFile.parse("APP_NAME=Laravel").withValue("NEW_KEY", "value")
        assertThat(edited.render()).isEqualTo("APP_NAME=Laravel\nNEW_KEY=value")
    }

    @Test
    fun `an unrecognised line is carried through untouched`() {
        val odd = "export FOO=bar\n!!! nonsense\nAPP_NAME=Laravel"
        assertThat(EnvFile.parse(odd).render()).isEqualTo(odd)
    }

    // --- Masking ------------------------------------------------------------------

    @Test
    fun `secret values are masked`() {
        val env = EnvFile.parse(sample)

        assertThat(env["APP_KEY"]!!.isSensitive).isTrue()
        assertThat(env["APP_KEY"]!!.maskedValue).isEqualTo(EnvFile.MASK)
        assertThat(env["DB_PASSWORD"]!!.maskedValue).isEqualTo(EnvFile.MASK)
    }

    @Test
    fun `ordinary values are shown`() {
        val env = EnvFile.parse(sample)

        assertThat(env["APP_NAME"]!!.isSensitive).isFalse()
        assertThat(env["APP_NAME"]!!.maskedValue).isEqualTo("Laravel")
        assertThat(env["DB_CONNECTION"]!!.maskedValue).isEqualTo("sqlite")
    }

    @Test
    fun `the mask does not leak the secret's length`() {
        val short = EnvFile.parse("APP_KEY=a")
        val long = EnvFile.parse("APP_KEY=${"x".repeat(64)}")
        assertThat(short["APP_KEY"]!!.maskedValue).isEqualTo(long["APP_KEY"]!!.maskedValue)
    }

    @Test
    fun `an unset secret is shown as empty rather than masked`() {
        // "This is not set" is something the user needs to see, and revealing it discloses
        // nothing — masking an empty value would hide a real configuration problem.
        val env = EnvFile.parse("DB_PASSWORD=")
        assertThat(env["DB_PASSWORD"]!!.maskedValue).isEmpty()
    }

    @Test
    fun `masking uses the same definition of sensitive as redaction`() {
        // These all come from SecretRedactor's single key pattern. A second definition here
        // would drift, and the two disagreeing means a value shown in clear that logs redact.
        listOf("APP_KEY", "DB_PASSWORD", "MAIL_PASSWORD", "AWS_SECRET_ACCESS_KEY", "API_TOKEN")
            .forEach { key ->
                assertThat(EnvFile.parse("$key=x")[key]!!.isSensitive).isTrue()
            }
        listOf("APP_NAME", "APP_ENV", "DB_CONNECTION", "KEYBOARD_LAYOUT").forEach { key ->
            assertThat(EnvFile.parse("$key=x")[key]!!.isSensitive).isFalse()
        }
    }

    @Test
    fun `rendering never substitutes the mask for the real value`() {
        // The mask is for display. If it ever reached render(), saving the file would destroy
        // the user's actual secret.
        val rendered = EnvFile.parse(sample).render()
        assertThat(rendered).doesNotContain(EnvFile.MASK)
        assertThat(rendered).contains("base64:2b7eXAMPLEkeyMATERIALhere==")
    }
}
