package dev.mobileforge.runtime

import android.content.Context
import android.os.Build
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.data.settings.SettingsRepository
import dev.mobileforge.feature.settings.BundleStore
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.toolchain.BundleCatalog
import dev.mobileforge.runtime.toolchain.CatalogInstallProgress
import dev.mobileforge.runtime.toolchain.CatalogInstaller
import dev.mobileforge.runtime.toolchain.HttpBundleFetcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shipping [BundleStore]: the catalog baked into the APK, plus the machinery to fetch it.
 *
 * The catalog is an asset because the versions MobileForge ships with are a decision of the
 * build, not of the user — that is the whole point of this feature. A user who hosts their
 * own bundles can override the source at runtime; see [sourceUrl].
 *
 * It is narrowed to this device's ABI here, at the edge, so the screen and the installer only
 * ever see bundles that can actually run on the phone in hand. The installer still filters as
 * a defence in depth.
 */
class AndroidBundleStore(
    private val context: Context,
    private val manager: ToolchainManager,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val deviceAbi: String = Build.SUPPORTED_ABIS.first(),
) : BundleStore {

    private val fetcher = HttpBundleFetcher(dispatchers)

    override suspend fun catalog(): AppResult<BundleCatalog> = withContext(dispatchers.io) {
        val override = sourceUrl()
        val text = try {
            context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logger.warn(TAG, "No readable bundle catalog asset: ${e.message}")
            return@withContext AppError(
                category = ErrorCategory.Unavailable,
                message = "This build does not include a bundle catalog.",
                detail = "The catalog asset is missing or unreadable.",
                recovery = "Install a bundle from a file under Advanced options.",
            ).asFailure()
        }

        try {
            val parsed = BundleCatalog.parse(text)
            val effective = if (override == null) parsed else parsed.copy(baseUrl = override)
            AppResult.Success(effective.copy(bundles = effective.bundles.filter { it.abi == deviceAbi }))
        } catch (e: IllegalArgumentException) {
            AppError(
                category = ErrorCategory.Validation,
                message = if (override == null) {
                    "This build's bundle catalog is not usable."
                } else {
                    "The bundle source in Advanced options is not usable."
                },
                detail = e.message,
                recovery = if (override == null) {
                    "Install a bundle from a file under Advanced options."
                } else {
                    "Correct or clear the source URL under Advanced options."
                },
                cause = e,
            ).asFailure()
        }
    }

    override suspend fun installAll(
        catalog: BundleCatalog,
        onProgress: (CatalogInstallProgress) -> Unit,
    ): AppResult<Unit> {
        // One cache directory per source: two different sources can legitimately publish the
        // same file name for different bytes, and sharing a cache would install the wrong one.
        val downloadDir = File(File(context.cacheDir, "bundle-downloads"), cacheKey(catalog.baseUrl))
        val installer = CatalogInstaller(fetcher, manager, downloadDir, deviceAbi)
        return installer.installAll(catalog, onProgress)
    }

    override suspend fun sourceUrl(): String? = withContext(dispatchers.io) {
        try {
            settings.settings.first().bundleSourceUrl
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun setSourceUrl(url: String?) {
        settings.setBundleSourceUrl(url)
    }

    /** A short, stable, filesystem-safe key for a source URL. */
    private fun cacheKey(baseUrl: String): String =
        "%08x".format(baseUrl.hashCode())

    private companion object {
        const val TAG = "BundleStore"
        const val CATALOG_ASSET = "bundles/catalog.json"
    }
}
