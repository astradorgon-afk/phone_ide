package dev.mobileforge.feature.settings

import dev.mobileforge.core.common.AppResult
import dev.mobileforge.runtime.toolchain.BundleCatalog
import dev.mobileforge.runtime.toolchain.CatalogInstallProgress

/**
 * The published-bundle side of the toolchain screen.
 *
 * This exists so the screen can offer *install* rather than *find a file*: the app knows the
 * versions and addresses, so a person never has to source an archive. It sits behind an
 * interface so the ViewModel is testable without a network, a ZIP or a device — and so the
 * screen never touches Android's asset manager or an HTTP connection itself.
 */
interface BundleStore {

    /**
     * The catalog of bundles for this build, already narrowed to this device's ABI and with
     * any user-supplied source applied. An empty [BundleCatalog.bundles] means "nothing is
     * published for this device", which the screen states plainly rather than dressing up.
     */
    suspend fun catalog(): AppResult<BundleCatalog>

    /** Fetches and installs everything in [catalog] this device is missing, in order. */
    suspend fun installAll(
        catalog: BundleCatalog,
        onProgress: (CatalogInstallProgress) -> Unit,
    ): AppResult<Unit>

    /** The user's bundle-source override, or null when the built-in source is in use. */
    suspend fun sourceUrl(): String?

    /** Sets or clears the override. Blank clears it. */
    suspend fun setSourceUrl(url: String?)
}
