package dev.mobileforge.core.filesystem

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A template that claims to create files must actually create them.
 *
 * These tests exist because the create-project dialog names the files each template produces;
 * if the scaffolder silently did nothing, the dialog would be lying to the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectScaffolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : AppDispatchers {
        private val test: CoroutineDispatcher = StandardTestDispatcher()
        override val main = test
        override val io = test
        override val default = test
    }

    private val scaffolder = ProjectScaffolder(dispatchers)

    @Test
    fun `static site template writes the files the dialog promises`() =
        runTest(dispatchers.io) {
            val root = temp.newFolder("site")

            val result = scaffolder.scaffold(root.absolutePath, ScaffoldTemplate.StaticSite)

            assertThat(result.isSuccess).isTrue()
            assertThat(File(root, "index.html").isFile).isTrue()
            assertThat(File(root, "styles.css").isFile).isTrue()
            assertThat(File(root, "script.js").isFile).isTrue()
        }

    @Test
    fun `static site html references its own stylesheet and script`() =
        runTest(dispatchers.io) {
            val root = temp.newFolder("site2")
            scaffolder.scaffold(root.absolutePath, ScaffoldTemplate.StaticSite)

            val html = File(root, "index.html").readText()

            assertThat(html).contains("styles.css")
            assertThat(html).contains("script.js")
        }

    @Test
    fun `php template writes index php`() = runTest(dispatchers.io) {
        val root = temp.newFolder("php")

        scaffolder.scaffold(root.absolutePath, ScaffoldTemplate.Php)

        val php = File(root, "index.php").readText()
        assertThat(php).startsWith("<?php")
        // The dollar signs must survive Kotlin string templating into real PHP variables.
        assertThat(php).contains("\$name")
    }

    @Test
    fun `php template ships an env example but never a real env file`() =
        runTest(dispatchers.io) {
            val root = temp.newFolder("php2")

            scaffolder.scaffold(root.absolutePath, ScaffoldTemplate.Php)

            assertThat(File(root, ".env.example").isFile).isTrue()
            // Creating a real .env would put a secrets file in the project by default.
            assertThat(File(root, ".env").exists()).isFalse()
        }

    @Test
    fun `empty template leaves the folder empty`() = runTest(dispatchers.io) {
        val root = temp.newFolder("empty")

        val result = scaffolder.scaffold(root.absolutePath, ScaffoldTemplate.Empty)

        assertThat(result.isSuccess).isTrue()
        assertThat(root.listFiles()).isEmpty()
    }

    @Test
    fun `scaffolding a missing folder reports a filesystem error`() = runTest(dispatchers.io) {
        val result = scaffolder.scaffold("/does/not/exist", ScaffoldTemplate.StaticSite)

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.recovery).isNotNull()
    }
}
