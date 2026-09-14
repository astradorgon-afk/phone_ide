package dev.mobileforge.runtime.laravel

import dev.mobileforge.core.security.CommandRisk
import dev.mobileforge.core.security.CommandRiskClassifier

/**
 * A validated `php artisan` invocation.
 *
 * Artisan is not a safe command surface. `migrate:fresh` drops every table, `db:wipe` empties the
 * database, and both are one typo away from commands people run daily. The brief is explicit
 * that destructive database operations must never run without the user's consent, so the risk
 * verdict is carried in the type rather than checked somewhere downstream and hopefully not
 * forgotten.
 *
 * Construction never executes anything. [parse] validates and classifies; running is a separate
 * step that requires the caller to have dealt with [risk].
 */
data class ArtisanCommand internal constructor(
    /** The subcommand, e.g. `migrate` or `route:list`. */
    val name: String,
    val arguments: List<String>,
    val risk: CommandRisk,
) {
    /** Full argument vector after `artisan`, for the process spec. */
    val argv: List<String> get() = listOf(name) + arguments

    /**
     * True when the user must confirm before this runs.
     *
     * Delegates to the existing risk model rather than re-deciding what is dangerous. That
     * matters for more than tidiness: `CommandRisk` already treats package installs as
     * confirmation-worthy, because running third-party lifecycle scripts is not something a
     * user consented to by granting "run commands" (RISK-015). A local definition here would
     * have silently dropped that.
     */
    val requiresConfirmation: Boolean get() = risk.requiresExplicitConfirmation

    companion object {

        /**
         * Characters that must never reach an argument.
         *
         * Artisan is invoked directly — no shell — so metacharacters cannot be interpreted.
         * They are rejected anyway: a command containing them is either a mistake or an attempt
         * to reach a shell that is not there, and both are worth refusing loudly. Rejecting is
         * cheap; discovering later that a shell crept into the path is not.
         */
        private val FORBIDDEN = charArrayOf(
            ';', '|', '&', '$', '`', '\n', '\r', ' ', '<', '>',
        )

        /** Artisan subcommand names are `word` or `group:word`, nothing else. */
        private val NAME_PATTERN = Regex("""^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$""")

        /**
         * Parses a user-typed artisan command.
         *
         * Returns null when the input is not a usable command at all — empty, or a name that
         * cannot be an artisan subcommand. A null result means "do not run this", not "run it
         * and see".
         */
        fun parse(input: String): ArtisanCommand? {
            val tokens = input.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null

            // Tolerate the user typing the whole thing as they would in a terminal.
            val withoutPrefix = tokens
                .dropWhile { it == "php" || it == "artisan" || it.endsWith("/artisan") }
            if (withoutPrefix.isEmpty()) return null

            val name = withoutPrefix.first()
            if (!NAME_PATTERN.matches(name)) return null

            val arguments = withoutPrefix.drop(1)
            if (arguments.any { argument -> argument.any { it in FORBIDDEN } }) return null

            return ArtisanCommand(
                name = name,
                arguments = arguments,
                // Classified against the full command line, because the danger is often in the
                // arguments: `migrate` is ordinary, `migrate --force` on production is not.
                risk = CommandRiskClassifier.classify("php artisan ${withoutPrefix.joinToString(" ")}"),
            )
        }

        /**
         * The commands offered as buttons in the UI.
         *
         * Deliberately short and entirely non-destructive. A one-tap button is the wrong place
         * for anything that drops data — those remain typed, so the user has to mean it, and
         * then confirmed because [requiresConfirmation] says so.
         */
        val COMMON: List<String> = listOf(
            "serve",
            "route:list",
            "migrate",
            "migrate:status",
            "db:seed",
            "cache:clear",
            "config:clear",
            "view:clear",
            "optimize:clear",
            "queue:work",
            "test",
            "about",
        )
    }
}
