package dev.mobileforge.runtime.api

import dev.mobileforge.core.common.AppResult

/** Document references are opaque to features; only the Android adapter opens them. */
interface ToolchainManager {
    suspend fun inspect(manifestDocument: String): AppResult<ToolchainImport>
    suspend fun install(importToken: String, archiveDocument: String,
        onProgress: (Float) -> Unit): AppResult<Unit>
    suspend fun installed(): AppResult<List<ToolchainRecord>>
}

data class ToolchainImport(
    val token: String,
    val name: String,
    val version: String,
    val abi: String,
    val prefix: String,
    val license: String,
    val sourceUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val incompatibility: String? = null,
)

data class ToolchainRecord(
    val id: String,
    val name: String,
    val version: String,
    val abi: String,
    val license: String,
    val sourceUrl: String,
    val filesPresent: Boolean,
)

/**
 * Toolchain bundles every install is expected to end with, in install order.
 *
 * Order matters: a tool installed before its base lands in a prefix where its scripts
 * die on a missing interpreter, so the setup flow and any future dependency handling
 * walk this list front to back rather than discovering the order at runtime.
 */
val REQUIRED_TOOLCHAIN_IDS: List<String> = listOf("base", "php", "composer")

/**
 * The required toolchain ids that are not among [installedIds], in install order.
 *
 * Empty means everything required is present. The order is kept so a caller that wants to say
 * what to install first can. It answers only "is it there", which is why the screens that call it
 * through the container treat an unreadable installed list as "unknown" and say nothing, rather
 * than reporting every tool as missing.
 */
fun missingRequiredToolchains(installedIds: Collection<String>): List<String> {
    val installed = installedIds.toSet()
    return REQUIRED_TOOLCHAIN_IDS.filter { it !in installed }
}

/**
 * A toolchain bundle shipped inside the APK, ready to install without a download.
 *
 * The document references are opaque URIs the [ToolchainManager] implementation opens —
 * the same references a document picker would have produced, so embedded and imported
 * bundles travel one install path instead of two.
 */
data class EmbeddedBundle(
    val id: String,
    val displayName: String,
    val version: String,
    val abi: String,
    val sizeBytes: Long,
    val manifestDocument: String,
    val archiveDocument: String,
)
