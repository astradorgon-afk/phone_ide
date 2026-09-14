package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bundle-to-bundle requirements.
 *
 * Bundles all extract into one shared `$PREFIX`, which is what lets a tool bundle rely on a
 * `base` bundle having put a shell and coreutils there. That sharing is also the risk: install a
 * tool without its base and the prefix looks fine, the install reports success, and the failure
 * arrives much later as a script dying on a missing interpreter.
 *
 * The saving is the reason this exists at all — `tree` went from 19 MB, carrying its own copy of
 * bash and coreutils, to 56 KB once it could depend on `base` instead.
 */
class ToolchainRequirementsTest {

    private fun manifest(
        id: String = "php",
        requires: List<String> = emptyList(),
    ) = ToolchainManifest(
        id = id,
        displayName = id,
        version = "1.0.0",
        abi = "arm64-v8a",
        prefix = "/data/data/dev.mobileforge/files/usr",
        sha256 = "0".repeat(64),
        sizeBytes = 1,
        license = "MIT",
        sourceUrl = "https://example.invalid/src",
        requires = requires,
    )

    @Test
    fun `a bundle with no requirements is always satisfied`() {
        assertThat(ToolchainCompatibility.missingRequirements(manifest(), emptySet())).isEmpty()
    }

    @Test
    fun `a satisfied requirement reports nothing missing`() {
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base")),
            setOf("base"),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `a missing base is reported`() {
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base")),
            emptySet(),
        )
        assertThat(result).containsExactly("base")
    }

    @Test
    fun `other installed bundles do not satisfy the requirement`() {
        // Having git installed says nothing about whether base is.
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base")),
            setOf("git", "php", "nodejs"),
        )
        assertThat(result).containsExactly("base")
    }

    @Test
    fun `every unmet requirement is named, not just the first`() {
        // The user should be told everything to install, not sent round the loop once per item.
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base", "php")),
            setOf("git"),
        )
        assertThat(result).containsExactly("base", "php").inOrder()
    }

    @Test
    fun `partially satisfied requirements report only what is absent`() {
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base", "php")),
            setOf("base"),
        )
        assertThat(result).containsExactly("php")
    }

    @Test
    fun `requirement matching is exact`() {
        // "base-extra" is a different bundle; a prefix match would wrongly pass here.
        val result = ToolchainCompatibility.missingRequirements(
            manifest(requires = listOf("base")),
            setOf("base-extra"),
        )
        assertThat(result).containsExactly("base")
    }
}
