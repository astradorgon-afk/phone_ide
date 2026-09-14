package dev.mobileforge.core.filesystem

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Writes starter files into a newly created project.
 *
 * Every template here produces files this build can actually open and edit. Templates that
 * would need tooling to scaffold — a real `laravel new`, `npm create vite` — are deliberately
 * NOT offered in Phase 1, because there is no runtime to run them and a template that creates
 * a folder while claiming to create a Laravel app is exactly the kind of lie this project
 * refuses to ship.
 */
class ProjectScaffolder(
    private val dispatchers: AppDispatchers,
) {

    suspend fun scaffold(rootPath: String, template: ScaffoldTemplate): AppResult<Unit> =
        withContext(dispatchers.io) {
            val root = File(rootPath)
            if (!root.isDirectory) {
                return@withContext AppError(
                    category = ErrorCategory.FileSystem,
                    message = "The project folder is missing.",
                    detail = "Expected a directory at '$rootPath'.",
                    recovery = "Try creating the project again.",
                ).asFailure()
            }

            try {
                template.files().forEach { (relativePath, contents) ->
                    val target = File(root, relativePath)
                    target.parentFile?.mkdirs()
                    target.writeText(contents)
                }
                AppResult.Success(Unit)
            } catch (e: IOException) {
                AppError(
                    category = ErrorCategory.FileSystem,
                    message = "The starter files could not be written.",
                    detail = e.message,
                    recovery = "The project folder was created and is safe to use; " +
                        "you can add files manually.",
                    cause = e,
                ).asFailure()
            }
        }
}

enum class ScaffoldTemplate {
    Empty,
    StaticSite,
    Php,
    ;

    /** Relative path to contents. Empty map means an empty folder, which is a valid choice. */
    fun files(): Map<String, String> = when (this) {
        Empty -> emptyMap()
        StaticSite -> staticSiteFiles()
        Php -> phpFiles()
    }
}

private fun staticSiteFiles(): Map<String, String> = mapOf(
    "index.html" to """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>New project</title>
          <link rel="stylesheet" href="styles.css">
        </head>
        <body>
          <main>
            <h1>Hello</h1>
            <p>Edit this file to get started.</p>
          </main>
          <script src="script.js"></script>
        </body>
        </html>
    """.trimIndent() + "\n",

    "styles.css" to """
        :root {
          color-scheme: light dark;
          font-family: system-ui, sans-serif;
        }

        body {
          margin: 0;
          display: grid;
          place-items: center;
          min-height: 100vh;
        }

        main {
          padding: 2rem;
          text-align: center;
        }
    """.trimIndent() + "\n",

    "script.js" to """
        document.addEventListener('DOMContentLoaded', () => {
          console.log('Ready.');
        });
    """.trimIndent() + "\n",

    "README.md" to """
        # New project

        A static site. Open `index.html` to start editing.

        Serving it locally needs the development runtime, which arrives in Phase 2.
    """.trimIndent() + "\n",
)

private fun phpFiles(): Map<String, String> = mapOf(
    "index.php" to """
        <?php

        declare(strict_types=1);

        ${'$'}name = ${'$'}_GET['name'] ?? 'world';

        echo 'Hello, ' . htmlspecialchars(${'$'}name, ENT_QUOTES, 'UTF-8') . "!\n";
    """.trimIndent() + "\n",

    ".env.example" to """
        APP_ENV=local
        APP_DEBUG=true
    """.trimIndent() + "\n",

    "README.md" to """
        # New PHP project

        Edit `index.php` to start.

        Running PHP on-device needs the development runtime, which arrives in Phase 2 —
        see ROADMAP.md. Until then this project is editable but not runnable.
    """.trimIndent() + "\n",
)
