package dev.mobileforge.feature.editor

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.filesystem.LocalWorkspaceFileSystem
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.security.PathValidator
import dev.mobileforge.feature.editor.bridge.MonacoBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEditorTest {
    @get:Rule val temp = TemporaryFolder()
    private val scheduler = StandardTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val main = scheduler
        override val io = scheduler
        override val default = scheduler
    }
    @Before fun setup() { Dispatchers.setMain(scheduler) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun filesystem() = LocalWorkspaceFileSystem(temp.root, PathValidator(), dispatchers, NoOpLogger)
    private fun model(fs: WorkspaceFileSystem = filesystem()) = EditorViewModel(fs,
        MonacoBridge(NoOpLogger), NoOpLogger, nativeEditing = true)

    @Test fun `native edits save through the real atomic filesystem without WebView`() = runTest(scheduler) {
        val file = File(temp.root, "hello.txt").apply { writeText("old") }
        val model = model()
        model.openFile("hello.txt", false)
        advanceUntilIdle()
        model.editNativeContent("new café 日本")
        model.save()
        advanceUntilIdle()
        assertThat(file.readText()).isEqualTo("new café 日本")
        assertThat(model.state.value.isDirty).isFalse()
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `restricted workspace refuses edits and saves`() = runTest(scheduler) {
        val file = File(temp.root, "hello.txt").apply { writeText("original") }
        val model = model()
        model.openFile("hello.txt", true)
        advanceUntilIdle()
        model.editNativeContent("changed")
        model.save()
        advanceUntilIdle()
        assertThat(file.readText()).isEqualTo("original")
        assertThat(model.state.value.content).isEqualTo("original")
        assertThat(model.state.value.error).isNotNull()
    }

    @Test fun `typing during a save retains newer buffer and dirty state`() = runTest(scheduler) {
        File(temp.root, "hello.txt").writeText("old")
        val real = filesystem()
        val gate = CompletableDeferred<Unit>()
        val slow = object : WorkspaceFileSystem by real {
            override suspend fun writeText(relativePath: String, content: String) =
                gate.await().let { real.writeText(relativePath, content) }
        }
        val model = model(slow)
        model.openFile("hello.txt", false)
        advanceUntilIdle()
        model.editNativeContent("first")
        model.save()
        runCurrent()
        model.editNativeContent("second")
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(File(temp.root, "hello.txt").readText()).isEqualTo("first")
        assertThat(model.state.value.content).isEqualTo("second")
        assertThat(model.state.value.isDirty).isTrue()
    }

    @Test fun `slow file load cannot replace a more recent selection`() = runTest(scheduler) {
        File(temp.root, "first.txt").writeText("first")
        File(temp.root, "second.txt").writeText("second")
        val real = filesystem()
        val gate = CompletableDeferred<Unit>()
        val slow = object : WorkspaceFileSystem by real {
            override suspend fun readText(relativePath: String): dev.mobileforge.core.common.AppResult<String> {
                if (relativePath == "first.txt") gate.await()
                return real.readText(relativePath)
            }
        }
        val model = model(slow)
        model.openFile("first.txt", false)
        runCurrent()
        model.openFile("second.txt", false)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(model.state.value.openPath).isEqualTo("second.txt")
        assertThat(model.state.value.content).isEqualTo("second")
    }
}
