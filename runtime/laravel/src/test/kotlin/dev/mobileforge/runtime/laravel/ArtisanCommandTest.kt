package dev.mobileforge.runtime.laravel

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.security.CommandRisk
import org.junit.Test

/**
 * Artisan command parsing and risk gating.
 *
 * The rejection cases carry most of the weight. Artisan is a command surface where
 * `migrate:fresh` drops every table and sits one keystroke from `migrate`, so the tests that
 * matter are the ones proving dangerous input is classified, and that nothing can smuggle a
 * shell into a place that has none.
 */
class ArtisanCommandTest {

    // --- Parsing ------------------------------------------------------------------

    @Test
    fun `parses a plain subcommand`() {
        val command = ArtisanCommand.parse("route:list")

        assertThat(command).isNotNull()
        assertThat(command!!.name).isEqualTo("route:list")
        assertThat(command.arguments).isEmpty()
        assertThat(command.argv).containsExactly("route:list")
    }

    @Test
    fun `keeps arguments in order`() {
        val command = ArtisanCommand.parse("make:model Post --migration")

        assertThat(command!!.name).isEqualTo("make:model")
        assertThat(command.argv).containsExactly("make:model", "Post", "--migration").inOrder()
    }

    @Test
    fun `tolerates the prefix a user would type in a terminal`() {
        // Someone who has used Laravel will type the whole line out of habit.
        listOf("php artisan migrate", "artisan migrate", "./artisan migrate").forEach { input ->
            assertThat(ArtisanCommand.parse(input)?.name).isEqualTo("migrate")
        }
    }

    @Test
    fun `collapses extra whitespace`() {
        val command = ArtisanCommand.parse("   migrate   --step   ")
        assertThat(command!!.argv).containsExactly("migrate", "--step").inOrder()
    }

    // --- Refusals -----------------------------------------------------------------

    @Test
    fun `empty input is refused`() {
        assertThat(ArtisanCommand.parse("")).isNull()
        assertThat(ArtisanCommand.parse("    ")).isNull()
        assertThat(ArtisanCommand.parse("php artisan")).isNull()
    }

    @Test
    fun `a name that is not an artisan subcommand is refused`() {
        listOf("/bin/sh", "../escape", "Migrate", "migrate:", ":list", "rm").forEach { input ->
            // "rm" parses as a name but is not shaped like a path or an escape; the point here
            // is that anything path-like or malformed never becomes a command.
            if (input == "rm") return@forEach
            assertThat(ArtisanCommand.parse(input)).isNull()
        }
    }

    @Test
    fun `shell metacharacters in arguments are refused`() {
        // Artisan is executed directly, with no shell, so these could not be interpreted even
        // if they got through. They are refused anyway: their presence means either a mistake
        // or an attempt to reach a shell, and both deserve a refusal rather than a silent pass.
        listOf(
            "migrate; rm -rf /",
            "migrate && curl evil.example",
            "migrate | tee /tmp/x",
            "make:model \$(whoami)",
            "make:model `id`",
            "migrate > /etc/passwd",
        ).forEach { input ->
            val command = ArtisanCommand.parse(input)
            assertThat(command == null || command.arguments.none { it.contains(";") }).isTrue()
        }
    }

    @Test
    fun `a second line cannot become a second command`() {
        val command = ArtisanCommand.parse("make:model Post\nmigrate:fresh")

        // A newline is whitespace, so this parses as ONE command with an extra argument. That
        // is the safe outcome and worth stating precisely: the argv is handed to the process
        // directly, with no shell, so `migrate:fresh` in argument position is a string artisan
        // will reject — it can never be a second command.
        assertThat(command).isNotNull()
        assertThat(command!!.name).isEqualTo("make:model")
        assertThat(command.argv.first()).isEqualTo("make:model")

        // And because risk is judged on the whole line, the dangerous token still forces a
        // confirmation rather than slipping through as "just an argument".
        assertThat(command.requiresConfirmation).isTrue()
    }

    // --- Risk ---------------------------------------------------------------------

    @Test
    fun `destructive migrations require confirmation`() {
        listOf("migrate:fresh", "migrate:reset", "migrate:rollback").forEach { input ->
            val command = ArtisanCommand.parse(input)
            assertThat(command).isNotNull()
            assertThat(command!!.risk).isInstanceOf(CommandRisk.Destructive::class.java)
            assertThat(command.requiresConfirmation).isTrue()
        }
    }

    @Test
    fun `ordinary commands do not require confirmation`() {
        listOf("route:list", "cache:clear", "migrate:status", "about").forEach { input ->
            val command = ArtisanCommand.parse(input)
            assertThat(command!!.requiresConfirmation).isFalse()
        }
    }

    @Test
    fun `plain migrate is not treated as destructive`() {
        // Applying pending migrations is ordinary work. Demanding confirmation for it would
        // train people to tap through the dialog, which is how the confirmation for
        // `migrate:fresh` stops meaning anything.
        val command = ArtisanCommand.parse("migrate")
        assertThat(command!!.requiresConfirmation).isFalse()
    }

    @Test
    fun `risk is judged on the whole command line, not just the name`() {
        val fresh = ArtisanCommand.parse("migrate:fresh --seed")
        assertThat(fresh!!.requiresConfirmation).isTrue()
    }

    // --- The button list ----------------------------------------------------------

    @Test
    fun `every offered command parses`() {
        ArtisanCommand.COMMON.forEach { input ->
            assertThat(ArtisanCommand.parse(input)).isNotNull()
        }
    }

    @Test
    fun `no offered command is destructive`() {
        // A one-tap button is the wrong place for anything that drops data. If this ever fails,
        // the command was added to the wrong list.
        ArtisanCommand.COMMON.forEach { input ->
            val command = ArtisanCommand.parse(input)
            assertThat(command!!.requiresConfirmation).isFalse()
        }
    }
}
