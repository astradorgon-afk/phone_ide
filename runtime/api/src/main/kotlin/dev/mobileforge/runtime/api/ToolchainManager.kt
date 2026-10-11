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
