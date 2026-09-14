package dev.mobileforge.runtime.laravel

import dev.mobileforge.core.security.SecretRedactor

/**
 * A parsed `.env` file.
 *
 * Two properties drive the design.
 *
 * **Round-trip fidelity.** A `.env` is a hand-maintained file full of comments, grouping and
 * deliberate blank lines. Editing one value must return the file otherwise byte-identical —
 * anything else silently rewrites the user's work, and they will not notice until a diff.
 * Every line is therefore kept, including ones this parser does not understand.
 *
 * **Secrets stay masked.** `APP_KEY` is the most sensitive value in a Laravel project, and the
 * brief is explicit that secrets are never displayed automatically. Entries carry
 * [Entry.isSensitive] and the UI must ask for [Entry.value] deliberately; [maskedValue] is what
 * it renders by default.
 */
class EnvFile private constructor(
    private val lines: List<Line>,
) {

    /** Entries in file order. Comments and blanks are not entries but are preserved. */
    val entries: List<Entry> get() = lines.filterIsInstance<Line.Setting>().map { it.entry }

    operator fun get(key: String): Entry? = entries.firstOrNull { it.key == key }

    /**
     * Returns a copy with [key] set to [value].
     *
     * An existing key is updated in place, keeping its position, spacing and any trailing
     * comment. A new key is appended. Nothing else in the file moves.
     */
    fun withValue(key: String, value: String): EnvFile {
        val index = lines.indexOfFirst { it is Line.Setting && it.entry.key == key }
        if (index < 0) {
            return EnvFile(lines + Line.Setting(Entry(key, value), quote = quoteFor(value), trailing = ""))
        }
        val existing = lines[index] as Line.Setting
        return EnvFile(
            lines.toMutableList().apply {
                // The existing quoting style is kept when it still works, so a file that quotes
                // everything stays that way and a diff shows one changed value, not a reformat.
                val quote = if (existing.quote != null && !value.contains(existing.quote)) {
                    existing.quote
                } else {
                    quoteFor(value)
                }
                this[index] = existing.copy(entry = Entry(key, value), quote = quote)
            },
        )
    }

    /** The file as text, ready to write. */
    fun render(): String = lines.joinToString("\n") { it.render() }

    data class Entry(val key: String, val value: String) {
        val isSensitive: Boolean get() = SecretRedactor.isSensitiveKey(key)

        /**
         * What to show instead of the value.
         *
         * Length is not preserved — a fixed-width mask would leak how long the secret is, which
         * is a small but free thing to give away. Empty values are shown as empty because
         * "this is unset" is information the user needs and no secret is disclosed by it.
         */
        val maskedValue: String get() = when {
            !isSensitive -> value
            value.isEmpty() -> ""
            else -> MASK
        }
    }

    private sealed interface Line {
        fun render(): String

        data class Other(val text: String) : Line {
            override fun render(): String = text
        }

        data class Setting(
            val entry: Entry,
            val quote: Char?,
            val trailing: String,
        ) : Line {
            override fun render(): String {
                val rendered = if (quote != null) "$quote${entry.value}$quote" else entry.value
                return "${entry.key}=$rendered$trailing"
            }
        }
    }

    companion object {
        const val MASK: String = "••••••••"

        /** `KEY=value`, optionally quoted, optionally followed by a comment. */
        private val SETTING = Regex("""^([A-Za-z_][A-Za-z0-9_.]*)=(.*)$""")

        fun parse(text: String): EnvFile = EnvFile(
            text.split("\n").map { line ->
                val trimmed = line.trimStart()
                // Comments, blanks and anything unrecognised are carried through untouched.
                if (trimmed.startsWith("#") || trimmed.isEmpty()) return@map Line.Other(line)

                val match = SETTING.matchEntire(line) ?: return@map Line.Other(line)
                val key = match.groupValues[1]
                val raw = match.groupValues[2]

                val quote = raw.firstOrNull()?.takeIf { it == '"' || it == '\'' }
                if (quote != null) {
                    val closing = raw.indexOf(quote, startIndex = 1)
                    if (closing > 0) {
                        return@map Line.Setting(
                            entry = Entry(key, raw.substring(1, closing)),
                            quote = quote,
                            trailing = raw.substring(closing + 1),
                        )
                    }
                }

                // Unquoted: a `#` starts a comment, but only when separated by whitespace —
                // `APP_KEY=base64:ab#cd` is a value containing a hash, not a comment.
                val commentAt = Regex("""\s#""").find(raw)?.range?.first
                if (commentAt != null) {
                    Line.Setting(
                        entry = Entry(key, raw.substring(0, commentAt).trimEnd()),
                        quote = null,
                        trailing = raw.substring(commentAt),
                    )
                } else {
                    Line.Setting(Entry(key, raw), quote = null, trailing = "")
                }
            },
        )

        /** Quote only when the value needs it, so untouched files keep their style. */
        private fun quoteFor(value: String): Char? =
            if (value.any { it.isWhitespace() || it == '#' }) '"' else null
    }
}
