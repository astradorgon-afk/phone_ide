package dev.mobileforge.runtime

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ToolchainImportVerificationTest {
    @Test fun importsBundleAndReloadsItsRecord() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val abi = Build.SUPPORTED_ABIS.first()
        val base = "bundles/mf-doctor-1.0.0-$abi"
        val prefix = File(context.filesDir, "import-verification/usr")
        prefix.parentFile.mkdirs()
        val json = Json { ignoreUnknownKeys = true }
        val manifest = instrumentation.context.assets.open("$base.json").bufferedReader().use {
            json.decodeFromString<ToolchainManifest>(it.readText())
        }.copy(id = "import-verification", prefix = prefix.absolutePath)
        val manifestFile = File(context.cacheDir, "import-verification.json")
        manifestFile.writeText(json.encodeToString(manifest))
        val zip = File(context.cacheDir, "import-verification.zip")
        instrumentation.context.assets.open("$base.zip").use { source ->
            zip.outputStream().use { source.copyTo(it) }
        }
        val manager = AndroidToolchainManager(context, prefix, DefaultAppDispatchers, NoOpLogger)
        val inspected = manager.inspect(Uri.fromFile(manifestFile).toString())
        assertTrue("Manifest inspection failed: $inspected", inspected is AppResult.Success)
        val selection = (inspected as AppResult.Success).value
        assertEquals(null, selection.incompatibility)
        val installed = manager.install(selection.token, Uri.fromFile(zip).toString()) {}
        assertTrue("Installation failed: $installed", installed is AppResult.Success)
        assertTrue(File(prefix, "bin/mf-doctor").canExecute())
        val recreated = AndroidToolchainManager(context, prefix, DefaultAppDispatchers, NoOpLogger)
        val records = recreated.installed()
        assertTrue(records is AppResult.Success)
        assertTrue((records as AppResult.Success).value.any {
            it.id == manifest.id && it.filesPresent && it.version == manifest.version
        })
        manifestFile.delete()
        zip.delete()
        // Keep the shared app record list clean for subsequent UI inspection.
        File(context.filesDir, "toolchain-records/${manifest.id}.json").delete()
        Unit
    }
}
