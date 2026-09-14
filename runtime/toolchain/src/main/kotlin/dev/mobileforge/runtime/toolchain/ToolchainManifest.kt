package dev.mobileforge.runtime.toolchain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import dev.mobileforge.core.security.BundlePathPolicy

/**
 * Describes an installable toolchain bundle.
 *
 * A bundle is a ZIP archive of files rooted at `$PREFIX`, plus this manifest. The manifest is
 * what makes installation verifiable rather than hopeful: it pins the ABI, the expected digest
 * and the minimum page size, so an incompatible or tampered bundle is rejected *before* a
 * single byte is written into the prefix.
 *
 * **Why we cannot simply consume Termux packages.** Termux bakes its prefix,
 * `/data/data/com.termux/files/usr`, into binaries at build time — RUNPATHs, interpreter paths,
 * certificate locations. Those packages are explicitly not relocatable. Our prefix is
 * `/data/data/dev.mobileforge/files/usr`, so a Termux `php` would not find its own libraries.
 * Bundles must be built against *our* prefix. See docs/adr/ADR-011-toolchain-strategy.md.
 */
@Serializable
data class ToolchainManifest(
    /** Stable identifier, e.g. "php", "node", "coreutils". */
    val id: String,

    /** Human-readable name for the installer UI. */
    @SerialName("display_name")
    val displayName: String,

    /** Upstream version of the packaged software, e.g. "8.3.14". */
    val version: String,

    /** Android ABI this bundle was built for: "arm64-v8a", "armeabi-v7a", "x86_64". */
    val abi: String,

    /**
     * The `$PREFIX` this bundle was built against.
     *
     * Recorded so a mismatch is caught explicitly rather than surfacing later as a baffling
     * "library not found". A bundle built for another app's prefix is simply not installable.
     */
    val prefix: String,

    /** Hex SHA-256 of the archive, checked before extraction. */
    @SerialName("sha256")
    val sha256: String,

    /** Archive size in bytes, for progress reporting and a cheap early sanity check. */
    @SerialName("size_bytes")
    val sizeBytes: Long,

    /**
     * Minimum supported page size in bytes.
     *
     * Newer Android devices use 16 KB pages, and a shared library linked for 4 KB alignment
     * will fail to load there. Declaring it lets us refuse with a real reason instead of
     * letting the linker fail cryptically at exec time (RISK-004).
     */
    @SerialName("page_size")
    val pageSize: Int = PAGE_SIZE_4K,

    /** Paths inside the archive that must end up executable, relative to `$PREFIX`. */
    @SerialName("executables")
    val executables: List<String> = emptyList(),

    /**
     * Symbolic links to create after extraction: link path -> link target.
     *
     * Declared here rather than carried inside the archive, for two reasons. The practical one
     * is size: `zip` follows symlinks by default, and a real toolchain is mostly links — git
     * alone has ~50 pointing at one 3.6 MB binary, which turned a 24 MB package into a 302 MB
     * bundle. The better one is that archive symlink metadata is invisible to review, whereas
     * every link here is explicit, covered by whatever signs the manifest, and checked against
     * the prefix before it is created.
     *
     * Both the link path and its resolved target must stay inside `$PREFIX`; a link pointing
     * out of the prefix is the same escape as a `..` archive entry and is refused the same way.
     */
    @SerialName("symlinks")
    val symlinks: Map<String, String> = emptyMap(),

    /**
     * Other bundles that must already be installed.
     *
     * Bundles all extract into one shared `$PREFIX`, so a tool bundle can rely on another
     * having put the common pieces there. Without this, every bundle had to carry its own copy
     * of a shell and coreutils — no termux `.deb` provides `$PREFIX/bin/sh`, Termux's bootstrap
     * does — which made `tree`, a 40 KB utility, a 19 MB download.
     *
     * Declared rather than assumed: installing a tool bundle whose base is missing produces a
     * prefix where scripts fail on a missing interpreter, and that is far better refused up
     * front than discovered at a prompt.
     */
    @SerialName("requires")
    val requires: List<String> = emptyList(),

    /** SPDX licence identifier of the packaged software. */
    val license: String,

    /**
     * Every package in the bundle, with its own licence and source.
     *
     * [license] and [sourceUrl] describe the headline tool; they do not describe the bundle. A
     * PHP bundle carries 33 packages — OpenSSL, ICU, libxml2, readline and more — several of
     * them GPL or LGPL, and the obligation to offer source attaches to each one separately.
     * Recording only the root package would understate what is being distributed and leave the
     * offer incomplete for everything underneath it.
     */
    @SerialName("components")
    val components: List<BundleComponent> = emptyList(),

    /**
     * Where the corresponding source can be obtained.
     *
     * Mandatory rather than optional: several tools we intend to ship are GPL-licensed, and
     * distributing those binaries carries an obligation to offer source. Making this a
     * required field means the obligation cannot be forgotten at packaging time.
     */
    @SerialName("source_url")
    val sourceUrl: String,
) {
    companion object {
        const val PAGE_SIZE_4K = 4_096
        const val PAGE_SIZE_16K = 16_384
    }
}

/**
 * One package inside a bundle.
 *
 * Exists so the licence and source offer can be made per component rather than per bundle. A
 * user is entitled to the source of the GPL library buried three dependencies down, not just
 * the source of the tool they installed.
 */
@Serializable
data class BundleComponent(
    val name: String,
    val version: String,
    /** SPDX identifier as recorded by the package that produced this component. */
    val license: String,
    @SerialName("source_url")
    val sourceUrl: String,
)

/** A device's own facts, injected so compatibility logic stays testable off-device. */
data class DeviceProfile(
    val abi: String,
    /** From `Os.sysconf(_SC_PAGESIZE)`. */
    val pageSize: Int,
    val prefix: String,
    /** Free bytes in the install location. */
    val availableBytes: Long,
)

/**
 * Whether a bundle can be installed here, and if not, why.
 *
 * A sealed result rather than a boolean because every rejection needs to be explainable: "this
 * bundle is for arm64-v8a and this device is x86_64" is actionable, "installation failed" is not.
 */
sealed interface Compatibility {

    data object Compatible : Compatibility

    data class Incompatible(val reason: String, val recovery: String?) : Compatibility

    val isCompatible: Boolean get() = this is Compatible
}

/**
 * Checks a bundle against a device before anything is downloaded or written.
 *
 * Pure and total. Every check here is one that would otherwise fail late — at link time, at
 * exec time, or with a half-written prefix — and be far harder to diagnose.
 */
object ToolchainCompatibility {

    fun check(manifest: ToolchainManifest, device: DeviceProfile): Compatibility {
        if (!BundlePathPolicy.isSafeIdentifier(manifest.id) ||
            !BundlePathPolicy.isSafeIdentifier(manifest.version) ||
            manifest.displayName.isBlank() || manifest.license.isBlank() ||
            !manifest.sourceUrl.startsWith("https://") ||
            !manifest.sha256.matches(Regex("[a-fA-F0-9]{64}")) ||
            manifest.sizeBytes !in 1..(2L * 1024 * 1024 * 1024) ||
            manifest.pageSize !in listOf(4096, 16384) ||
            manifest.executables.any { !BundlePathPolicy.isSafeEntry(it) || it.endsWith('/') }
        ) {
            return Compatibility.Incompatible(
                "The bundle manifest contains invalid metadata or unsafe paths.",
                "Obtain a valid MobileForge bundle from its publisher.",
            )
        }
        if (manifest.abi != device.abi) {
            return Compatibility.Incompatible(
                reason = "This package is built for ${manifest.abi}, but this device is " +
                    "${device.abi}.",
                recovery = "Install the ${device.abi} build instead.",
            )
        }

        if (!prefixesMatch(manifest.prefix, device.prefix)) {
            // Not a warning: binaries carry their prefix internally and will not find their
            // libraries anywhere else.
            return Compatibility.Incompatible(
                reason = "This package was built for a different install location " +
                    "(${manifest.prefix}).",
                recovery = "Packages must be built for this app's own prefix; they are not " +
                    "relocatable.",
            )
        }

        if (device.pageSize > manifest.pageSize) {
            return Compatibility.Incompatible(
                reason = "This device uses ${device.pageSize / 1024} KB memory pages, but the " +
                    "package is aligned for ${manifest.pageSize / 1024} KB.",
                recovery = "A 16 KB-aligned build is required on this device.",
            )
        }

        // Extraction needs room for the archive and the expanded files at once.
        val required = manifest.sizeBytes * SPACE_HEADROOM_MULTIPLIER
        if (device.availableBytes < required) {
            return Compatibility.Incompatible(
                reason = "Not enough free space: ${required / 1_048_576} MB is needed and " +
                    "${device.availableBytes / 1_048_576} MB is free.",
                recovery = "Free up space and try again.",
            )
        }

        return Compatibility.Compatible
    }

    /**
     * Which of [ToolchainManifest.requires] are not yet installed.
     *
     * Pure so it can be tested without a device, and separate from [check] because it needs
     * knowledge [check] does not have: what else is installed. The caller holds that.
     *
     * Empty means the bundle may proceed. A non-empty result must block the install — a tool
     * bundle without its base lands in a prefix with no shell, where its scripts fail on a
     * missing interpreter long after the install reported success.
     */
    fun missingRequirements(
        manifest: ToolchainManifest,
        installedIds: Set<String>,
    ): List<String> = manifest.requires.filterNot { it in installedIds }

    /** Archive plus extracted contents coexist during install; 3x is a safe floor. */
    private const val SPACE_HEADROOM_MULTIPLIER = 3

    /**
     * Whether two prefixes name the same directory.
     *
     * Android exposes an app's data directory under two paths: `/data/data/<pkg>` and
     * `/data/user/<id>/<pkg>`, the former being a symlink to the latter for user 0. `filesDir`
     * reports the `/data/user/0` form, while build systems that bake a prefix into binaries —
     * termux-packages among them — use the `/data/data` form. They are the same directory, and
     * a plain string comparison rejects a bundle that would work perfectly.
     *
     * This is normalisation, not leniency: nothing here makes a genuinely foreign prefix pass,
     * because the package name still has to match exactly.
     */
    internal fun prefixesMatch(manifestPrefix: String, devicePrefix: String): Boolean =
        normalisePrefix(manifestPrefix) == normalisePrefix(devicePrefix)

    /** Rewrites the `/data/user/<id>/` form to the equivalent `/data/data/` one. */
    private fun normalisePrefix(prefix: String): String {
        val trimmed = prefix.trimEnd('/')
        val match = USER_DATA_DIR.matchEntire(trimmed) ?: return trimmed
        return "/data/data/${match.groupValues[2]}"
    }

    private val USER_DATA_DIR = Regex("""^/data/user/(\d+)/(.*)$""")
}
