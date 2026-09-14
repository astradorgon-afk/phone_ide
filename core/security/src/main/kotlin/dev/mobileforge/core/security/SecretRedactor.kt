package dev.mobileforge.core.security

/**
 * Removes credential-shaped values from any text about to cross a trust boundary — a log sink,
 * a diagnostics export, or an AI prompt.
 *
 * Applied at the boundary rather than at each call site, because "remember to redact" is not a
 * control. The brief is explicit: keys must never appear in logs, analytics, crash reports or
 * prompts, and .env values must not reach a model unless the user explicitly authorises it.
 *
 * This is defence in depth, not the primary control. The primary control is that secrets are
 * never placed in these paths to begin with (see SECURITY.md). A redactor is a backstop for
 * the case where something slips through, and it is deliberately biased toward over-redacting.
 *
 * Patterns use raw strings so the regex reads as a regex rather than as escaped Kotlin.
 */
object SecretRedactor {

    const val PLACEHOLDER: String = "[REDACTED]"

    /**
     * Keys whose *value* is always sensitive, whatever the surrounding format.
     *
     * The bare `KEY` alternative carries a `(?![A-Z])` lookahead so it matches `APP_KEY`,
     * `API_KEY` and `PRIVATE_KEY` but not `KEYBOARD_LAYOUT`. `APP_KEY` matters specifically:
     * it is Laravel's application encryption key, the single most sensitive value in a Laravel
     * .env, and an earlier version of this pattern missed it because it only looked for
     * `API_KEY` and `ACCESS_KEY`.
     *
     * Where the choice is between over-redacting a harmless config value and leaking a
     * credential, this errs toward over-redacting.
     */
    /**
     * The key-name shape itself, shared by [sensitiveKeyPattern] and [isSensitiveKey].
     *
     * Factored out so there is exactly one definition of "this name holds a secret". A second
     * copy in the `.env` editor would drift from this one, and the two disagreeing means either
     * a value shown in clear that should have been masked, or a log line redacted that the
     * editor happily displays.
     */
    private const val SENSITIVE_NAME =
        """[A-Z0-9_]*(?:SECRET|PASSWORD|PASSWD|TOKEN|CREDENTIAL|AUTH|SESSION|COOKIE|SALT|KEY(?![A-Z]))[A-Z0-9_]*"""

    private val sensitiveKeyPattern = Regex(
        """(?i)\b($SENSITIVE_NAME)\s*[:=]\s*("[^"]*"|'[^']*'|[^\s,;}]+)""",
    )

    private val sensitiveNamePattern = Regex("""(?i)^$SENSITIVE_NAME$""")

    /**
     * Whether a bare key name means its value must be treated as a secret.
     *
     * Used where the key and value are already separated — a `.env` entry, a config row — so
     * the value can be masked before it ever reaches a screen.
     */
    fun isSensitiveKey(key: String): Boolean = sensitiveNamePattern.matches(key.trim())

    /** Well-known provider key shapes, caught even with no key name nearby. */
    private val knownTokenPatterns: List<Regex> = listOf(
        // PEM private key blocks first: header through footer, so the body cannot survive.
        Regex("""-----BEGIN[^-]*PRIVATE KEY-----[\s\S]*?-----END[^-]*PRIVATE KEY-----"""),
        Regex("""sk-ant-[A-Za-z0-9_\-]{16,}"""),
        Regex("""sk-[A-Za-z0-9]{20,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""glpat-[A-Za-z0-9_\-]{16,}"""),
        Regex("""AIza[A-Za-z0-9_\-]{20,}"""),
        Regex("""xox[baprs]-[A-Za-z0-9\-]{10,}"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{20,}"""),
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text

        var result = text
        for (pattern in knownTokenPatterns) {
            result = pattern.replace(result, PLACEHOLDER)
        }
        result = sensitiveKeyPattern.replace(result) { match ->
            "${match.groupValues[1]}=$PLACEHOLDER"
        }
        return result
    }

    /** True if [text] appears to contain a secret. Used to warn before sharing content. */
    fun containsSecret(text: String): Boolean =
        knownTokenPatterns.any { it.containsMatchIn(text) } ||
            sensitiveKeyPattern.containsMatchIn(text)
}
