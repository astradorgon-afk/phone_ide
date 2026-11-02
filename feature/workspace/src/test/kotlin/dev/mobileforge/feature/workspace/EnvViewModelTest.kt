package dev.mobileforge.feature.workspace

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.filesystem.LocalWorkspaceFileSystem
import dev.mobileforge.core.security.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Structured `.env` editing over the real atomic filesystem.
 *
 * The two failure modes from the parser tests carry through here: an edit must not reformat
 * what was not edited (asserted byte-for-byte through the real save path), and the mask must
 * never reach the disk (a save with no edits writes identical bytes, and editing a masked
 * key writes the real value, not the mask).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private val scheduler = StandardTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val main = scheduler
        override val io = scheduler
        override val default = scheduler
    }

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    private fun filesystem() =
        LocalWorkspaceFileSystem(temp.root, PathValidator(), dispatchers, NoOpLogger)

    private fun model() = EnvViewModel(filesystem())

    private suspend fun TestScope.open(env: String, readOnly: Boolean = false): EnvViewModel {
        File(temp.root, ".env").writeText(env)
        return model().also {
            it.open(readOnly = readOnly, hasEnvExample = false)
            advanceUntilIdle()
        }
    }

    @Test fun `open loads entries in file order`() = runTest(scheduler) {
        val model = open("APP_NAME=Laravel\nAPP_KEY=base64:abc==\nDB_PASSWORD=hunter2\n")
        advanceUntilIdle()

        val state = model.state.value
        assertThat(state.found).isTrue()
        assertThat(state.entries.map { it.key })
            .containsExactly("APP_NAME", "APP_KEY", "DB_PASSWORD")
            .inOrder()
        assertThat(state.isDirty).isFalse()
        assertThat(state.error).isNull()
    }

    @Test fun `missing file is reported as not found, not an error`() = runTest(scheduler) {
        val model = model()
        model.open(readOnly = false, hasEnvExample = true)
        advanceUntilIdle()

        val state = model.state.value
        assertThat(state.found).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.hasEnvExample).isTrue()
    }

    @Test fun `sensitive values are flagged, plain values are not`() = runTest(scheduler) {
        val model = open("APP_NAME=Laravel\nAPP_KEY=base64:abc==\n")
        advanceUntilIdle()

        val entries = model.state.value.entries.associateBy { it.key }
        assertThat(entries.getValue("APP_NAME").isSensitive).isFalse()
        assertThat(entries.getValue("APP_KEY").isSensitive).isTrue()
        assertThat(entries.getValue("APP_KEY").maskedValue).isNotEqualTo("base64:abc==")
    }

    @Test fun `an edit saves through the real filesystem preserving the rest byte for byte`() =
        runTest(scheduler) {
            val before = "APP_NAME=Laravel\n\n# Database\nDB_CONNECTION=sqlite\nDB_PASSWORD=hunter2\n"
            File(temp.root, ".env").writeText(before)
            val model = model()
            model.open(readOnly = false, hasEnvExample = false)
            advanceUntilIdle()

            model.setValue("DB_CONNECTION", "mysql")
            assertThat(model.state.value.isDirty).isTrue()
            model.save()
            advanceUntilIdle()

            assertThat(File(temp.root, ".env").readText()).isEqualTo(
                "APP_NAME=Laravel\n\n# Database\nDB_CONNECTION=mysql\nDB_PASSWORD=hunter2\n",
            )
            assertThat(model.state.value.isDirty).isFalse()
            assertThat(model.state.value.error).isNull()
        }

    // ASCII only in the name: a test's name becomes a class file name, and a non-ASCII one fails
    // to compile on any machine whose locale is not UTF-8 (a stock CI runner, a Docker image).
    @Test fun `a save with no edits writes identical bytes so the mask never reaches disk`() =
        runTest(scheduler) {
            val before = "APP_KEY=base64:abc==\nDB_PASSWORD=hunter2\n"
            File(temp.root, ".env").writeText(before)
            val model = model()
            model.open(readOnly = false, hasEnvExample = false)
            advanceUntilIdle()

            // Nothing edited: not dirty, so save is a no-op.
            model.save()
            advanceUntilIdle()
            assertThat(File(temp.root, ".env").readText()).isEqualTo(before)
        }

    @Test fun `editing a masked key writes the real value, not the mask`() =
        runTest(scheduler) {
            val model = open("APP_KEY=base64:abc==\n")
            advanceUntilIdle()

            model.reveal("APP_KEY")
            model.setValue("APP_KEY", "base64:new==")
            model.save()
            advanceUntilIdle()

            assertThat(File(temp.root, ".env").readText()).isEqualTo("APP_KEY=base64:new==\n")
        }

    @Test fun `restricted workspace refuses saves and leaves the file untouched`() =
        runTest(scheduler) {
            File(temp.root, ".env").writeText("APP_NAME=Laravel\n")
            val model = model()
            model.open(readOnly = true, hasEnvExample = false)
            advanceUntilIdle()

            model.setValue("APP_NAME", "changed")
            model.save()
            advanceUntilIdle()

            assertThat(File(temp.root, ".env").readText()).isEqualTo("APP_NAME=Laravel\n")
            assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)
        }

    @Test fun `reveal and conceal toggle per key`() = runTest(scheduler) {
        val model = open("APP_KEY=base64:abc==\nDB_PASSWORD=hunter2\n")
        advanceUntilIdle()

        assertThat(model.state.value.revealed).isEmpty()
        model.reveal("APP_KEY")
        assertThat(model.state.value.revealed).containsExactly("APP_KEY")
        model.conceal("APP_KEY")
        assertThat(model.state.value.revealed).isEmpty()
    }

    @Test fun `adding a key appends it and marks the file dirty`() = runTest(scheduler) {
        val model = open("APP_NAME=Laravel\n")
        advanceUntilIdle()

        model.addEntry("CACHE_DRIVER", "file")
        assertThat(model.state.value.isDirty).isTrue()
        model.save()
        advanceUntilIdle()

        // The trailing blank line is preserved; the new key lands after it.
        assertThat(File(temp.root, ".env").readText())
            .isEqualTo("APP_NAME=Laravel\n\nCACHE_DRIVER=file")
    }

    @Test fun `adding an invalid key is a validation error, not a write`() =
        runTest(scheduler) {
            val model = open("APP_NAME=Laravel\n")
            advanceUntilIdle()

            model.addEntry("not a key!", "x")
            assertThat(model.state.value.error?.category)
                .isEqualTo(ErrorCategory.Validation)
            assertThat(model.state.value.isDirty).isFalse()
            assertThat(File(temp.root, ".env").readText()).isEqualTo("APP_NAME=Laravel\n")
        }

    @Test fun `a hash inside a value survives an unrelated edit`() = runTest(scheduler) {
        // Regression guard for the parser's hardest case: base64 keys containing '#'
        // must not be truncated when a different entry is saved.
        val model = open("APP_KEY=base64:ab#cd\nAPP_NAME=Laravel\n")
        advanceUntilIdle()

        model.setValue("APP_NAME", "Forge")
        model.save()
        advanceUntilIdle()

        assertThat(File(temp.root, ".env").readText())
            .isEqualTo("APP_KEY=base64:ab#cd\nAPP_NAME=Forge\n")
    }
}
