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
