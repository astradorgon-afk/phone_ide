package dev.mobileforge.runtime

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import dev.mobileforge.runtime.toolchain.Compatibility
import dev.mobileforge.runtime.toolchain.DeviceProfile
import dev.mobileforge.runtime.toolchain.ToolchainCompatibility
import dev.mobileforge.runtime.toolchain.ToolchainInstaller
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

class AndroidToolchainManager(
    context: Context,
    private val prefix: File,
    private val dispatchers: AppDispatchers,
    logger: Logger,
) : ToolchainManager {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cache = File(context.cacheDir, "toolchain-imports")
    private val records = File(context.filesDir, "toolchain-records")
    private val installer = ToolchainInstaller(prefix, File(cache, "staging"), dispatchers, logger)
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = linkedMapOf<String, ToolchainManifest>()
    private val mutex = Mutex()

    private fun device(manifestPrefix: String) = DeviceProfile(
        abi = Build.SUPPORTED_ABIS.first(),
        pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt(),
        // Android exposes the same private directory through /data/data and /data/user/0.
        // Accept an alias only after resolving it to this app's actual prefix.
        prefix = if (File(manifestPrefix).canonicalFile == prefix.canonicalFile)
            manifestPrefix else prefix.absolutePath,
        availableBytes = allocatableBytes(prefix.parentFile ?: prefix),
    )

    /**
     * Space actually available for an install.
     *
     * `usableSpace` under-reports, sometimes badly: Android will reclaim other apps' caches on
     * demand, so a bundle that "does not fit" frequently does. That matters here because this
     * number gates the install outright — refusing a 20 MB toolchain on a device with 500 MB of
     * reclaimable cache would be a bug the user cannot diagnose.
     *
     * Falls back to `usableSpace` if the storage service is unavailable or the path has no
     * volume UUID, which is the honest answer when the better one cannot be obtained.
     */
    private fun allocatableBytes(target: File): Long {
        val storage = appContext.getSystemService(StorageManager::class.java)
            ?: return target.usableSpace
        return runCatching {
            storage.getAllocatableBytes(storage.getUuidForPath(target))
        }.getOrElse { target.usableSpace }
    }

    override suspend fun inspect(manifestDocument: String): AppResult<ToolchainImport> =
        withContext(dispatchers.io) {
            guarded("The bundle manifest could not be read.") {
                val bytes = open(manifestDocument).use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > 256 * 1024) throw IOException("Manifest is too large.")
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                }
                val manifest = json.decodeFromString<ToolchainManifest>(bytes.toString(Charsets.UTF_8))
                val compatibility = ToolchainCompatibility.check(manifest, device(manifest.prefix))
                val token = UUID.randomUUID().toString()
                mutex.withLock {
                    if (pending.size >= 8) pending.remove(pending.keys.first())
                    pending[token] = manifest
                }
                AppResult.Success(ToolchainImport(token, manifest.displayName, manifest.version,
                    manifest.abi, manifest.prefix, manifest.license, manifest.sourceUrl,
                    manifest.sha256, manifest.sizeBytes,
                    (compatibility as? Compatibility.Incompatible)?.reason))
            }
        }

    override suspend fun install(importToken: String, archiveDocument: String,
        onProgress: (Float) -> Unit): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            guarded("The toolchain could not be imported.") {
                val manifest = pending[importToken]
                    ?: return@guarded failure("Select the manifest again.", "The import selection expired.")
                val compatible = ToolchainCompatibility.check(manifest, device(manifest.prefix))
                if (compatible is Compatibility.Incompatible) {
                    return@guarded failure("This bundle cannot be installed.", compatible.reason)
                }

                // Required bundles are checked here rather than in the installer: the installer
                // deals with one archive and has no view of what else is installed, while this
                // layer already keeps the records. Installing a tool without its base would
                // produce a prefix where scripts die on a missing interpreter — a failure far
                // better refused now than met at a prompt later.
                val installedIds = records.listFiles { file -> file.extension == "json" }
                    .orEmpty().map { it.nameWithoutExtension }.toSet()
                val missing = ToolchainCompatibility.missingRequirements(manifest, installedIds)
                if (missing.isNotEmpty()) {
                    return@guarded failure(
                        "${manifest.displayName} needs another bundle first.",
                        "Install ${missing.joinToString(", ")} before this one.",
                    )
                }
                cache.mkdirs()
                records.mkdirs()
                val archive = File.createTempFile("bundle-", ".zip", cache)
                try {
                    open(archiveDocument).use { input ->
                        archive.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var copied = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > manifest.sizeBytes) throw IOException("Archive exceeds its declared size.")
                                output.write(buffer, 0, count)
                                onProgress(0.2f * copied / manifest.sizeBytes)
                            }
                        }
                    }
                    // Once promotion starts, finish the record even if the screen is closed.
                    // Cancelling during the preceding copy is safe and leaves no installed files.
                    withContext(NonCancellable) {
                    when (val result = installer.install(manifest, archive, device(manifest.prefix)) {
                        onProgress(0.2f + 0.75f * it)
                    }) {
                        is AppResult.Failure -> result
                        is AppResult.Success -> {
                            // The record reports installed files, never a fabricated working version.
                            val temporary = File.createTempFile("record-", ".tmp", records)
                            try {
                                temporary.outputStream().use {
                                    it.write(json.encodeToString(manifest).toByteArray())
                                    it.fd.sync()
                                }
                                Files.move(temporary.toPath(), File(records, "${manifest.id}.json").toPath(),
                                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            } finally { temporary.delete() }
                            pending.remove(importToken)
                            onProgress(1f)
                            AppResult.Success(Unit)
                        }
                    }
                    }
                } finally { archive.delete() }
            }
        }
    }

    override suspend fun installed(): AppResult<List<ToolchainRecord>> = withContext(dispatchers.io) {
        mutex.withLock {
            guarded("Installed toolchain records could not be read.") {
                val items = records.listFiles { file -> file.extension == "json" }.orEmpty().map { file ->
                    val manifest = json.decodeFromString<ToolchainManifest>(file.readText())
                    ToolchainRecord(manifest.id, manifest.displayName, manifest.version, manifest.abi,
                        manifest.license, manifest.sourceUrl,
                        manifest.executables.isNotEmpty() && manifest.executables.all {
                            File(prefix, it).isFile
                        })
                }
                AppResult.Success(items.sortedBy { it.name })
            }
        }
    }

    private fun open(reference: String) = resolver.openInputStream(reference.toUri())
        ?: throw IOException("The selected document is no longer available.")

    private suspend fun <T> guarded(message: String, action: suspend () -> AppResult<T>): AppResult<T> =
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(message, e.message ?: e.javaClass.simpleName) }

    private fun failure(message: String, detail: String) = AppResult.Failure(AppError(
        category = ErrorCategory.FileSystem, message = message, detail = detail,
        recovery = "Select a complete, compatible MobileForge bundle and try again.",
    ))
}
