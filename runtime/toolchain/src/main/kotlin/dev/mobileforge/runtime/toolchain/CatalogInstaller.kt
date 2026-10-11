package dev.mobileforge.runtime.toolchain

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.REQUIRED_TOOLCHAIN_IDS
import dev.mobileforge.runtime.api.ToolchainManager
import java.io.File

/** Which bundle is being fetched, and how far the whole run has come. */
data class CatalogInstallProgress(
    val entryId: String,
    val displayName: String,
    /** 0.0..1.0 across every bundle in the run, not within one. */
    val overall: Float,
)

/**
 * Installs published bundles end to end: fetch, verify, install, repeat.
 *
 * This is the piece the screen was missing. It owns no verification of its own — the manifest
 * is inspected by [ToolchainManager] (ABI, prefix, page size) and the archive is digested by
 * the installer — so it cannot weaken those checks by omission. What it adds is *acquisition*
 * and *ordering*: the download step a person should never have had to do by hand.
 *
 * Bundles are installed in dependency order ([REQUIRED_TOOLCHAIN_IDS]), and `install` still
 * refuses a tool whose base is absent, so a partial run leaves a working prefix rather than a
 * broken one.
 */
class CatalogInstaller(
    private val fetcher: BundleFetcher,
    private val manager: ToolchainManager,
    /** Where manifests and archives are cached between runs. */
    private val downloadDir: File,
    private val deviceAbi: String,
) {

    /**
     * Fetches and installs every entry in [catalog] that this device lacks.
     *
     * Returns success when everything that was wanted is installed — including the case where
     * nothing was wanted, which is not an error. The first failure stops the run and is
     * returned as-is: its message already names the bundle and what to do.
     */
    suspend fun installAll(
        catalog: BundleCatalog,
        onProgress: (CatalogInstallProgress) -> Unit = {},
    ): AppResult<Unit> {
        val available = catalog.bundles.filter { it.abi == deviceAbi }
        if (available.isEmpty()) {
            return AppError(
                category = ErrorCategory.Unavailable,
                message = "No toolchain is published for this device.",
                detail = "The bundle source has no entry for ${deviceAbi}.",
                recovery = "Check the bundle source, or install a bundle from a file.",
            ).asFailure()
        }

        val installed = when (val records = manager.installed()) {
            is AppResult.Failure -> return records
            is AppResult.Success -> records.value.map { it.id }.toSet()
        }

        val ordered = order(available)
        val wanted = ordered.filter { it.id !in installed }
        if (wanted.isEmpty()) return Unit.asSuccess()

        val total = wanted.size
        wanted.forEachIndexed { index, entry ->
            onProgress(CatalogInstallProgress(entry.id, entry.displayName,
                index.toFloat() / total))

            val manifestFile = File(downloadDir, "${entry.path}.json")
            val archiveFile = File(downloadDir, "${entry.path}.zip")

            // The manifest is tiny and always re-fetched if absent; it is what pins the
            // digest, so a stale copy from an older catalog is replaced rather than reused.
            if (!manifestFile.isFile) {
                runCatching { fetcher.fetch(entry.manifestUrl(catalog.baseUrl), manifestFile) }
                    .onFailure { return fetchError(entry, it) }
            }

            val inspected = when (
                val result = manager.inspect(manifestFile.toURI().toString())
            ) {
                is AppResult.Failure -> return result
                is AppResult.Success -> result.value
            }
            inspected.incompatibility?.let { reason ->
                return incompatibleError(entry, reason)
            }

            // Refetched when missing or when it no longer matches the manifest's declared
            // size, which is exactly the case a stale cache from an older version produces.
            if (!archiveFile.isFile || archiveFile.length() != inspected.sizeBytes) {
                runCatching {
                    fetcher.fetch(entry.archiveUrl(catalog.baseUrl), archiveFile) { fraction ->
                        onProgress(CatalogInstallProgress(
                            entry.id, entry.displayName, (index + fraction) / total))
                    }
                }.onFailure { return fetchError(entry, it) }
            }

            when (
                val result = manager.install(inspected.token, archiveFile.toURI().toString()) {
                    fraction ->
                    onProgress(CatalogInstallProgress(
                        entry.id, entry.displayName, (index + fraction) / total))
                }
            ) {
                is AppResult.Failure -> return result
                is AppResult.Success -> Unit
            }

            onProgress(CatalogInstallProgress(entry.id, entry.displayName,
                (index + 1f) / total))
        }

        return Unit.asSuccess()
    }

    /**
     * Dependency order, then catalog order.
     *
     * [REQUIRED_TOOLCHAIN_IDS] is the canonical order the setup flow uses; anything outside it
     * keeps its catalog position, which is the order its author published it in.
     */
    private fun order(entries: List<CatalogBundle>): List<CatalogBundle> =
        entries.withIndex().sortedWith(
            compareBy(
                { (_, entry) -> REQUIRED_TOOLCHAIN_IDS.indexOf(entry.id).takeIf { it >= 0 } ?: Int.MAX_VALUE },
                { (index, _) -> index },
            ),
        ).map { it.value }

    private fun fetchError(entry: CatalogBundle, cause: Throwable) = AppError(
        category = ErrorCategory.Network,
        message = "${entry.displayName} could not be downloaded.",
        detail = cause.message ?: cause.javaClass.simpleName,
        recovery = "Check the connection and the bundle source, then try again.",
        cause = cause,
        retryable = true,
    ).asFailure()

    private fun incompatibleError(entry: CatalogBundle, reason: String) = AppError(
        category = ErrorCategory.Unavailable,
        message = "${entry.displayName} cannot be installed on this device.",
        detail = reason,
        recovery = "Install a build for this device's architecture, or skip it.",
    ).asFailure()
}
