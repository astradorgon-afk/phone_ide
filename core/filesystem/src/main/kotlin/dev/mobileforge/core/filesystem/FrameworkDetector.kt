package dev.mobileforge.core.filesystem

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.model.DetectedFramework
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Identifies what kind of project a directory holds, from on-disk evidence only.
 *
 * Detection never guesses. A project is Laravel only when Laravel's actual markers are present;
 * otherwise the honest answer is a weaker classification or [DetectedFramework.Unknown]. The
 * project card shows what was found, not what was assumed.
 *
 * Cheap by construction: a handful of existence checks and, at most, one small file read. It
 * runs on every project-list render, so it must not walk the tree (RISK-009).
 */
class FrameworkDetector(
    private val dispatchers: AppDispatchers,
) {

    suspend fun detect(rootPath: String): DetectedFramework = withContext(dispatchers.io) {
        val root = File(rootPath)
        if (!root.isDirectory) return@withContext DetectedFramework.Unknown

        // Laravel: artisan + composer.json + the app/ and routes/ directories together.
        // Requiring all four avoids classifying a bare Composer package as a Laravel app.
        val hasArtisan = File(root, "artisan").isFile
        val hasComposer = File(root, "composer.json").isFile
        val hasApp = File(root, "app").isDirectory
        val hasRoutes = File(root, "routes").isDirectory
        if (hasArtisan && hasComposer && hasApp && hasRoutes) {
            return@withContext DetectedFramework.Laravel
        }

        if (hasComposer) return@withContext DetectedFramework.Php
        if (File(root, "package.json").isFile) return@withContext DetectedFramework.Node

        val hasPhpSource = root.listFiles()
            ?.any { it.isFile && it.extension.equals("php", ignoreCase = true) } == true
        if (hasPhpSource) return@withContext DetectedFramework.Php

        if (File(root, "index.html").isFile) return@withContext DetectedFramework.Static

        DetectedFramework.Unknown
    }

    /**
     * Facts about the project that are safely readable without running anything.
     *
     * Everything here comes from files. Versions that would require executing a tool — the
     * installed PHP, Composer, Node or Git version — are NOT reported, because in Phase 1
     * there is no runtime to ask, and printing a plausible-looking version we did not verify
     * would be exactly the kind of fake the brief prohibits.
     */
    suspend fun describe(rootPath: String): ProjectFacts = withContext(dispatchers.io) {
        val root = File(rootPath)
        val composerJson = File(root, "composer.json").takeIf { it.isFile }
        val packageJson = File(root, "package.json").takeIf { it.isFile }

        ProjectFacts(
            framework = detect(rootPath),
            declaredLaravelVersion = composerJson
                ?.let { readDeclaredVersion(it, "laravel/framework") },
            declaredPhpConstraint = composerJson?.let { readDeclaredVersion(it, "php") },
            hasComposerLock = File(root, "composer.lock").isFile,
            hasNodeModules = File(root, "node_modules").isDirectory,
            hasVendor = File(root, "vendor").isDirectory,
            hasEnvFile = File(root, ".env").isFile,
            hasEnvExample = File(root, ".env.example").isFile,
            isGitRepository = File(root, ".git").exists(),
            hasViteConfig = VITE_CONFIG_NAMES.any { File(root, it).isFile },
            packageManager = packageJson?.let { detectPackageManager(root) },
        )
    }

    /**
     * Extracts a declared dependency constraint from composer.json without a JSON parser.
     *
     * A small, forgiving regex is used on purpose: composer.json in a user's project may be
     * malformed or huge, and a strict parse failure would make the project card blank. A missing
     * version renders as "not declared", which is honest; a wrong version would not be.
     */
    private fun readDeclaredVersion(composerJson: File, dependency: String): String? = try {
        if (composerJson.length() > MAX_MANIFEST_BYTES) {
            null
        } else {
            val text = composerJson.readText()
            Regex(""""${Regex.escape(dependency)}"\s*:\s*"([^"]+)"""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
        }
    } catch (_: Exception) {
        null
    }

    private fun detectPackageManager(root: File): String? = when {
        File(root, "pnpm-lock.yaml").isFile -> "pnpm"
        File(root, "yarn.lock").isFile -> "yarn"
        File(root, "bun.lockb").isFile -> "bun"
        File(root, "package-lock.json").isFile -> "npm"
        else -> null
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 512L * 1024
        val VITE_CONFIG_NAMES = listOf(
            "vite.config.js",
            "vite.config.ts",
            "vite.config.mjs",
        )
    }
}

/**
 * What we can state about a project without executing anything.
 *
 * Every field is either a file-derived fact or null. There is deliberately no "phpVersion"
 * field: that requires running PHP, which Phase 1 cannot do.
 */
data class ProjectFacts(
    val framework: DetectedFramework,
    val declaredLaravelVersion: String?,
    val declaredPhpConstraint: String?,
    val hasComposerLock: Boolean,
    val hasNodeModules: Boolean,
    val hasVendor: Boolean,
    val hasEnvFile: Boolean,
    val hasEnvExample: Boolean,
    val isGitRepository: Boolean,
    val hasViteConfig: Boolean,
    val packageManager: String?,
)
