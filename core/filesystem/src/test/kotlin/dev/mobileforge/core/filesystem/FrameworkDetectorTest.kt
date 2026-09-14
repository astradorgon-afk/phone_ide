package dev.mobileforge.core.filesystem

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.model.DetectedFramework
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Framework detection must never guess.
 *
 * The important cases here are the negative ones: a bare Composer package is not a Laravel app,
 * and misreporting it would put Laravel-specific actions in front of a user who cannot use them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameworkDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        private val test: CoroutineDispatcher = StandardTestDispatcher()
        override val main = test
        override val io = test
        override val default = test
    }

    private val detector = FrameworkDetector(dispatchers)

    @Test
    fun `detects laravel only when all markers are present`() = runTest(dispatchers.io) {
        val root = temp.newFolder("laravel")
        File(root, "artisan").writeText("#!/usr/bin/env php")
        File(root, "composer.json").writeText("{}")
        File(root, "app").mkdirs()
        File(root, "routes").mkdirs()

        assertThat(detector.detect(root.absolutePath)).isEqualTo(DetectedFramework.Laravel)
    }

    @Test
    fun `a composer package without artisan is php not laravel`() = runTest(dispatchers.io) {
        val root = temp.newFolder("lib")
        File(root, "composer.json").writeText("{}")
        File(root, "src").mkdirs()

        assertThat(detector.detect(root.absolutePath)).isEqualTo(DetectedFramework.Php)
    }

    @Test
    fun `artisan alone is not enough for laravel`() = runTest(dispatchers.io) {
        val root = temp.newFolder("odd")
        File(root, "artisan").writeText("")

        assertThat(detector.detect(root.absolutePath)).isNotEqualTo(DetectedFramework.Laravel)
    }

    @Test
    fun `detects a node project`() = runTest(dispatchers.io) {
        val root = temp.newFolder("node")
        File(root, "package.json").writeText("{}")

        assertThat(detector.detect(root.absolutePath)).isEqualTo(DetectedFramework.Node)
    }

    @Test
    fun `detects a static site`() = runTest(dispatchers.io) {
        val root = temp.newFolder("site")
        File(root, "index.html").writeText("<html></html>")

        assertThat(detector.detect(root.absolutePath)).isEqualTo(DetectedFramework.Static)
    }

    @Test
    fun `an empty folder is unknown`() = runTest(dispatchers.io) {
        val root = temp.newFolder("empty")
        assertThat(detector.detect(root.absolutePath)).isEqualTo(DetectedFramework.Unknown)
    }

    @Test
    fun `a missing folder is unknown rather than an exception`() = runTest(dispatchers.io) {
        assertThat(detector.detect("/does/not/exist")).isEqualTo(DetectedFramework.Unknown)
    }

    @Test
    fun `reads declared laravel and php versions from composer json`() =
        runTest(dispatchers.io) {
            val root = temp.newFolder("laravel2")
            File(root, "artisan").writeText("")
            File(root, "app").mkdirs()
            File(root, "routes").mkdirs()
            File(root, "composer.json").writeText(
                """
                {
                  "require": {
                    "php": "^8.2",
                    "laravel/framework": "^11.9"
                  }
                }
                """.trimIndent(),
            )

            val facts = detector.describe(root.absolutePath)

            assertThat(facts.framework).isEqualTo(DetectedFramework.Laravel)
            assertThat(facts.declaredLaravelVersion).isEqualTo("^11.9")
            assertThat(facts.declaredPhpConstraint).isEqualTo("^8.2")
        }

    @Test
    fun `malformed composer json yields null versions rather than throwing`() =
        runTest(dispatchers.io) {
            val root = temp.newFolder("broken")
            File(root, "composer.json").writeText("{ this is not json")

            val facts = detector.describe(root.absolutePath)

            assertThat(facts.declaredLaravelVersion).isNull()
            assertThat(facts.framework).isEqualTo(DetectedFramework.Php)
        }

    @Test
    fun `identifies the package manager from its lockfile`() = runTest(dispatchers.io) {
        val root = temp.newFolder("pnpm-proj")
        File(root, "package.json").writeText("{}")
        File(root, "pnpm-lock.yaml").writeText("")

        assertThat(detector.describe(root.absolutePath).packageManager).isEqualTo("pnpm")
    }

    @Test
    fun `reports git and env facts`() = runTest(dispatchers.io) {
        val root = temp.newFolder("facts")
        File(root, ".git").mkdirs()
        File(root, ".env.example").writeText("")

        val facts = detector.describe(root.absolutePath)

        assertThat(facts.isGitRepository).isTrue()
        assertThat(facts.hasEnvExample).isTrue()
        assertThat(facts.hasEnvFile).isFalse()
    }
}
