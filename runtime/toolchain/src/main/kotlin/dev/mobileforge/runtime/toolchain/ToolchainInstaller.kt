package dev.mobileforge.runtime.toolchain

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.mobileforge.core.security.BundlePathPolicy
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Installs a toolchain bundle into `$PREFIX`.
 *
 * The order of operations is the security design, not an implementation detail:
 *
 *   1. **Check compatibility** — before any download, so an unusable bundle costs nothing.
 *   2. **Verify the digest** — over the whole archive, before extraction. Verifying afterwards
 *      would mean writing attacker-controlled files first and hoping to clean up.
 *   3. **Extract to a staging directory** — never straight into a live `$PREFIX`, so a failure
 *      halfway through cannot leave a half-installed, unusable toolchain.
 *   4. **Reject path traversal** — an archive entry named `../../../../system/bin/sh` is a real
 *      attack, and the one the whole "Zip Slip" class of CVEs is built on.
 *   5. **Promote atomically** — rename staging into place only once everything succeeded.
 *
 * Bundles are installed, not downloaded, by this class: fetching bytes is the caller's problem,
 * so the install path can be tested end to end from a local file with no network.
 */
class ToolchainInstaller(
    private val prefixDir: File,
    private val stagingDir: File,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) {
    private val installMutex = Mutex()

    /**
     * Installs [archive] after verifying it against [manifest].
     *
     * [onProgress] reports 0.0..1.0 so a large extraction can show progress rather than
     * appearing frozen.
     */
    suspend fun install(
        manifest: ToolchainManifest,
        archive: File,
        device: DeviceProfile,
        onProgress: (Float) -> Unit = {},
    ): AppResult<InstalledToolchain> = installMutex.withLock {
        installLocked(manifest, archive, device, onProgress)
    }

    private suspend fun installLocked(
        manifest: ToolchainManifest,
        archive: File,
        device: DeviceProfile,
        onProgress: (Float) -> Unit,
    ): AppResult<InstalledToolchain> = withContext(dispatchers.io) {
        when (val compatibility = ToolchainCompatibility.check(manifest, device)) {
            is Compatibility.Incompatible -> return@withContext AppError(
                category = ErrorCategory.Unavailable,
                message = "${manifest.displayName} cannot be installed on this device.",
                detail = compatibility.reason,
                recovery = compatibility.recovery,
            ).asFailure()

            Compatibility.Compatible -> Unit
        }

        if (!archive.isFile) {
            return@withContext AppError(
                category = ErrorCategory.FileSystem,
                message = "The download is missing.",
                detail = "No archive at '${archive.absolutePath}'.",
                recovery = "Download ${manifest.displayName} again.",
            ).asFailure()
        }

        onProgress(PROGRESS_VERIFYING)

        if (archive.length() != manifest.sizeBytes) {
            return@withContext securityError("The archive size does not match its manifest.",
                "The download is incomplete or has been changed.")
        }

        // Verified BEFORE extraction. Doing it after would mean writing unverified,
        // attacker-controlled files into the prefix first.
        val actualDigest = try {
            sha256Of(archive)
        } catch (e: IOException) {
            return@withContext ioError("The download could not be read.", e)
        }

        if (!actualDigest.equals(manifest.sha256, ignoreCase = true)) {
            logger.security(
                TAG,
                "Digest mismatch for ${manifest.id}: expected ${manifest.sha256}, " +
                    "got $actualDigest",
            )
            return@withContext AppError(
                category = ErrorCategory.Security,
                message = "${manifest.displayName} failed its integrity check.",
                detail = "The downloaded file does not match the expected SHA-256. It may be " +
                    "corrupt, or it may have been tampered with in transit.",
                recovery = "Delete the download and try again. If it keeps failing, do not " +
                    "install it.",
            ).asFailure()
        }

        onProgress(PROGRESS_EXTRACTING)

        val staging = try {
            stagingDir.mkdirs()
            Files.createTempDirectory(stagingDir.toPath(), "install-").toFile()
        } catch (e: IOException) {
            return@withContext AppError(
                category = ErrorCategory.FileSystem,
                message = "Installation could not start.",
                detail = "The staging directory could not be created.",
                recovery = "Check available storage and try again.",
            ).asFailure()
        }

        val extracted = try {
            extract(archive, staging, manifest, onProgress)
        } catch (e: IOException) {
            staging.deleteRecursively()
            return@withContext ioError("${manifest.displayName} could not be extracted.", e)
        }

        if (extracted is AppResult.Failure) {
            staging.deleteRecursively()
            return@withContext extracted
        }

        onProgress(PROGRESS_PROMOTING)

        // Merge into the live prefix. Bundles share $PREFIX/bin and $PREFIX/lib, so a
        // wholesale directory swap would delete previously installed tools.
        val promoted = promote(staging, manifest)
        staging.deleteRecursively()
        if (promoted is AppResult.Failure) return@withContext promoted

        // Links are created last, directly in the prefix. They cannot be staged: the merge
        // copies with follow-links semantics, so a staged link would arrive as a full copy of
        // its target — which is exactly the size problem links exist to avoid.
        createSymlinks(prefixDir, manifest)?.let { return@withContext it }

        onProgress(PROGRESS_DONE)
        logger.info(TAG, "Installed ${manifest.id} ${manifest.version} (${manifest.abi})")

        InstalledToolchain(
            id = manifest.id,
            version = manifest.version,
            abi = manifest.abi,
            installedAtEpochMs = System.currentTimeMillis(),
            executables = manifest.executables.map { "${prefixDir.absolutePath}/$it" },
        ).asSuccess()
    }

    /**
     * Extracts the archive, refusing any entry that escapes the destination.
     *
     * This is the "Zip Slip" defence. An entry named `../../../../data/data/other.app/x` is a
     * real, catalogued attack, and a bundle is exactly the kind of artefact that carries one.
     * Containment is checked against the canonical path, because `..` is not the only way out —
     * a symlink entry is another.
     */
    private fun extract(
        archive: File,
        destination: File,
        manifest: ToolchainManifest,
        onProgress: (Float) -> Unit,
    ): AppResult<Unit> {
        val destinationPath = destination.canonicalPath
        var entriesSeen = 0
        var bytesWritten = 0L
        val seen = mutableSetOf<String>()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.removeSuffix("/")
                if (!BundlePathPolicy.isSafeEntry(entry.name) || !seen.add(relative)) {
                    return securityError("The package contains unsafe or duplicate paths.",
                        "This package is not safe to install.")
                }
                entriesSeen++

                if (entriesSeen > MAX_ENTRIES) {
                    return securityError(
                        "The package contains an implausible number of files.",
                        "It may be a decompression bomb.",
                    )
                }

                val target = File(destination, entry.name)
                if (!target.canonicalPath.startsWith(destinationPath + File.separator) &&
                    target.canonicalPath != destinationPath
                ) {
                    logger.security(TAG, "Blocked path traversal in bundle: ${entry.name}")
                    return securityError(
                        "The package tried to write outside its install location.",
                        "This package is not safe to install.",
                    )
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }

                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break

                        bytesWritten += read
                        if (bytesWritten > MAX_EXTRACTED_BYTES) {
                            return securityError(
                                "The package expands to more data than expected.",
                                "It may be a decompression bomb.",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()

                if (manifest.sizeBytes > 0) {
                    val fraction = (bytesWritten.toFloat() / manifest.sizeBytes)
                        .coerceIn(0f, 1f)
                    onProgress(
                        PROGRESS_EXTRACTING +
                            fraction * (PROGRESS_PROMOTING - PROGRESS_EXTRACTING),
                    )
                }
            }
        }

        if (entriesSeen == 0) {
            return securityError("The package is empty or is not a ZIP archive.",
                "Obtain a complete MobileForge ZIP bundle.")
        }
        for (relative in manifest.executables) {
            // Declared symlinks do not exist yet: they are created in the prefix after
            // promotion, because a link in the staging tree would be followed and copied by
            // the merge, defeating the reason they are links at all.
            if (relative in manifest.symlinks) continue

            val executable = File(destination, relative)
            if (!executable.isFile) {
                return securityError("A declared executable is missing from the package.", relative)
            }
            if (!executable.setExecutable(true, false)) {
                return ioError("An executable could not be prepared.", IOException(relative))
            }
        }

        return AppResult.Success(Unit)
    }

    /**
     * Creates the manifest's declared symlinks inside the staging tree.
     *
     * Every link is checked twice: the link's own path must be a safe archive path, and the
     * target must resolve back inside the staging root. A link is just as capable of escaping
     * the prefix as a `../..` entry — writing through `$PREFIX/bin/x -> /data/data/other.app/y`
     * would put us in another app's storage — so it gets the same refusal.
     *
     * Returns null on success, or the failure to propagate.
     */
    private fun createSymlinks(
        destination: File,
        manifest: ToolchainManifest,
    ): AppResult<Nothing>? {
        if (manifest.symlinks.isEmpty()) return null
        val destinationPath = destination.canonicalPath

        for ((linkPath, target) in manifest.symlinks) {
            if (!BundlePathPolicy.isSafeEntry(linkPath)) {
                logger.security(TAG, "Blocked unsafe symlink path in bundle: $linkPath")
                return securityError(
                    "The package contains an unsafe link path.",
                    "This package is not safe to install.",
                )
            }

            val link = File(destination, linkPath)
            // An absolute target must NOT be joined onto the link's directory: File(parent,
            // "/system/bin/sh") yields a path under the parent, so an absolute escape would
            // look contained and be created pointing straight out of the prefix.
            //
            // A leading "/" is treated as absolute regardless of the host's path rules. Bundle
            // paths are Android paths, and a JVM on Windows does not consider "/system/bin/sh"
            // absolute (it has no drive letter) — so relying on Path.isAbsolute would let this
            // check pass on a Windows build machine and fail open on the device.
            val resolved = if (target.startsWith("/")) {
                Paths.get(target).normalize().toString()
            } else {
                File(link.parentFile, target).canonicalPath
            }
            if (!resolved.startsWith(destinationPath + File.separator) &&
                resolved != destinationPath
            ) {
                logger.security(TAG, "Blocked symlink escaping the prefix: $linkPath -> $target")
                return securityError(
                    "The package tried to link outside its install location.",
                    "This package is not safe to install.",
                )
            }

            link.parentFile?.mkdirs()
            // A bundle may legitimately declare a link where extraction already wrote one.
            if (Files.isSymbolicLink(link.toPath())) Files.delete(link.toPath())

            try {
                Files.createSymbolicLink(link.toPath(), Paths.get(target))
            } catch (e: IOException) {
                return ioError("A package link could not be created.", e)
            } catch (e: UnsupportedOperationException) {
                return ioError("This device does not support links.", IOException(e))
            }
        }
        return null
    }

    /** Moves staged files into the live prefix, merging rather than replacing. */
    private fun promote(staging: File, manifest: ToolchainManifest): AppResult<Unit> {
        if (!prefixDir.exists() && !prefixDir.mkdirs()) {
            return AppError(
                category = ErrorCategory.FileSystem,
                message = "The install location could not be created.",
                detail = "mkdirs() failed for '${prefixDir.absolutePath}'.",
            ).asFailure()
        }

        val promoted = mutableListOf<Pair<File, File?>>()
        val createdDirectories = mutableListOf<File>()
        val backup = try {
            Files.createTempDirectory(stagingDir.toPath(), "backup-").toFile()
        } catch (e: IOException) {
            return ioError("Installation backup could not be created.", e)
        }
        var retainBackup = false
        try {
            val sources = staging.walkTopDown().drop(1).toList()
            // Preflight the entire merge before changing existing files. A shell may have
            // left symlinks in the prefix; even links within it are not safe install targets.
            for (source in sources) {
                val target = File(prefixDir, source.relativeTo(staging).path)
                var ancestor: File? = target
                while (ancestor != null && ancestor != prefixDir.parentFile) {
                    if (Files.isSymbolicLink(ancestor.toPath())) {
                        return securityError("The install path contains a symbolic link.",
                            "Remove the link before installing this bundle.")
                    }
                    ancestor = ancestor.parentFile
                }
                if (target.exists() && source.isDirectory != target.isDirectory) {
                    throw IOException("A file conflicts with a directory: ${target.name}")
                }
            }
            for (source in sources) {
                val relative = source.relativeTo(staging).path
                val target = File(prefixDir, relative)
                if (source.isDirectory) {
                    if (!target.exists()) {
                        if (!target.mkdir()) throw IOException("Cannot create $relative")
                        createdDirectories += target
                    }
                    continue
                }
                val saved = if (target.exists()) File(backup, relative).also {
                    it.parentFile.mkdirs()
                    Files.copy(target.toPath(), it.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                } else null
                // Stage next to the target so rename is atomic even when the download cache
                // and prefix reside on different filesystems.
                val replacement = Files.createTempFile(target.parentFile.toPath(), ".mf-", ".tmp")
                try {
                    Files.copy(source.toPath(), replacement, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES)
                    Files.move(replacement, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                    promoted += target to saved
                } finally {
                    Files.deleteIfExists(replacement)
                }
            }
            return AppResult.Success(Unit)
        } catch (e: IOException) {
            for ((target, saved) in promoted.asReversed()) {
                try {
                    if (saved == null) Files.deleteIfExists(target.toPath())
                    else Files.copy(saved.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                } catch (rollback: IOException) {
                    retainBackup = true
                    e.addSuppressed(rollback)
                }
            }
            createdDirectories.asReversed().forEach { it.delete() }
            if (retainBackup) logger.warn(TAG, "Recovery files retained in $backup", e)
            return ioError("${manifest.displayName} could not be installed." +
                if (retainBackup) " Recovery files are at $backup." else " Changes were rolled back.", e)
        } finally {
            if (!retainBackup) backup.deleteRecursively()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { stream ->
            val buffer = ByteArray(COPY_BUFFER)
            @Suppress("ControlFlowWithEmptyBody")
            while (stream.read(buffer) > 0) {
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun securityError(message: String, detail: String): AppResult<Nothing> = AppError(
        category = ErrorCategory.Security,
        message = message,
        detail = detail,
        recovery = "Do not install this package.",
    ).asFailure()

    private fun ioError(message: String, cause: IOException): AppResult<Nothing> = AppError(
        category = ErrorCategory.FileSystem,
        message = message,
        detail = cause.message,
        recovery = "Check available storage and try again.",
        cause = cause,
    ).asFailure()

    private companion object {
        const val TAG = "ToolchainInstaller"
        const val COPY_BUFFER = 64 * 1024

        /** Bomb guards. Generous for a real toolchain, far below anything pathological. */
        const val MAX_ENTRIES = 200_000
        const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024

        const val PROGRESS_VERIFYING = 0.05f
        const val PROGRESS_EXTRACTING = 0.15f
        const val PROGRESS_PROMOTING = 0.90f
        const val PROGRESS_DONE = 1.0f
    }
}

data class InstalledToolchain(
    val id: String,
    val version: String,
    val abi: String,
    val installedAtEpochMs: Long,
    val executables: List<String>,
)

/** Reads a gzip stream. Kept separate so the extractor stays format-agnostic. */
internal fun InputStream.gunzip(): InputStream = GZIPInputStream(this)
