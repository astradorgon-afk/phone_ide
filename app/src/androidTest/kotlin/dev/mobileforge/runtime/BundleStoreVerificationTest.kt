package dev.mobileforge.runtime

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.data.settings.DataStoreSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * VERIFIES THE AUTOMATIC INSTALL PATH on a device.
 *
 * The unit tests prove the catalog, the fetch and the ordering logic on the JVM. What they
 * cannot prove is the two Android-specific joints this test covers: that the catalog asset is
 * readable from the APK and narrowed to this device, and that a manifest the app *downloaded*
 * — a plain file, not a document the user picked — can be read back through the toolchain
 * manager. If that last one did not hold, an automatic install would fetch a bundle and then
 * refuse to look at it.
 */
@RunWith(AndroidJUnit4::class)
class BundleStoreVerificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val abi = android.os.Build.SUPPORTED_ABIS.first()

    private val manager = AndroidToolchainManager(
        context = context,
        prefix = File(context.filesDir, "usr"),
        dispatchers = DefaultAppDispatchers,
        logger = NoOpLogger,
    )

    private fun store(settings: DataStoreSettingsRepository = DataStoreSettingsRepository(context)) =
        AndroidBundleStore(context, manager, settings, DefaultAppDispatchers, NoOpLogger, abi)

    @Test
    fun catalogIsReadFromTheApkAndNarrowedToThisDevice() = runBlocking {
        val result = store().catalog()

        assertTrue("catalog() failed: ${(result as? AppResult.Failure)?.error}", result is AppResult.Success)
        val catalog = (result as AppResult.Success).value
        assertTrue("catalog had no bundles for $abi", catalog.bundles.isNotEmpty())
        assertEquals("every entry must match this device", emptyList<String>(),
            catalog.bundles.filter { it.abi != abi }.map { it.id })
    }

    /**
     * The exact hand-off an automatic install makes: a manifest written to cache by the
     * downloader is opened by the manager through its `file://` reference.
     */
    @Test
    fun aDownloadedManifestFileCanBeInspected() = runBlocking {
        val manifestFile = File(context.cacheDir, "mf-verify").apply { mkdirs() }
            .resolve("base-verify.json")
        context.assets.open("bundles/base-1.0.0-$abi.json").use { input ->
            manifestFile.outputStream().use { input.copyTo(it) }
        }

        val result = manager.inspect(Uri.fromFile(manifestFile).toString())

        assertTrue("inspect() failed: ${(result as? AppResult.Failure)?.error}", result is AppResult.Success)
        val bundle = (result as AppResult.Success).value
        assertTrue("inspect() returned no token", bundle.token.isNotBlank())
        assertTrue("unexpected bundle: ${bundle.name}", bundle.name.contains("Base"))
        assertEquals(abi, bundle.abi)
    }

    @Test
    fun aUserSourceOverrideChangesWhereBundlesComeFrom() = runBlocking {
        val settings = DataStoreSettingsRepository(context)
        val local = store(settings)

        try {
            local.setSourceUrl(SOURCE)
            val catalog = (local.catalog() as AppResult.Success).value
            assertEquals(SOURCE, catalog.baseUrl)
        } finally {
            local.setSourceUrl(null)
        }
    }

    private companion object {
        const val SOURCE = "http://127.0.0.1:8080"
    }
}
