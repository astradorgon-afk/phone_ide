package dev.mobileforge.runtime.toolchain

import dev.mobileforge.core.security.BundlePathPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * The published bundles this build knows how to fetch.
 *
 * This is what removes the "choose a manifest and a ZIP" dead end: the versions and file
 * names are known to the app, so the screen can offer *install* instead of asking a person
 * to go and find an archive. Everything authoritative — digest, size, licence, dependencies,
 * ABI — still lives in the bundle's own manifest, which is downloaded and verified before a
 * byte of the archive is trusted. The catalog only says *where* to look.
 *
 * [baseUrl] is where the bundles live. It is overridable at runtime (a person running their
 * own bundle host) and defaults to the project's published location.
 */
@Serializable
data class BundleCatalog(
    /** Bumped if the shape of this file changes; an unknown schema is refused, not guessed. */
    val schema: Int = CURRENT_SCHEMA,

    @SerialName("base_url")
    val baseUrl: String,

    val bundles: List<CatalogBundle> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /**
         * Parses a catalog, refusing anything that is not exactly what it claims to be.
         *
         * A catalog is small and hand-published, so a malformed one is a release mistake
         * rather than an attack surface — but it is still parsed strictly, because a
         * half-understood catalog would send downloads to paths nobody intended.
         */
        fun parse(text: String, json: Json = Json { ignoreUnknownKeys = true }): BundleCatalog {
            val catalog = json.decodeFromString<BundleCatalog>(text)
            require(catalog.schema == CURRENT_SCHEMA) {
                "Unsupported catalog schema ${catalog.schema}; this build understands $CURRENT_SCHEMA."
            }
            require(BundleUrlPolicy.isPermitted(catalog.baseUrl)) {
                "The catalog's base URL is not an address this app will download from: ${catalog.baseUrl}"
            }
            catalog.bundles.forEach { entry ->
                require(BundlePathPolicy.isSafeIdentifier(entry.path)) {
                    "Unsafe bundle path in catalog: ${entry.path}"
                }
            }
            return catalog
        }
    }
}

/**
 * One fetchable bundle.
 *
 * `path` is the filename stem: the manifest is `<path>.json` and the archive `<path>.zip`
 * under [BundleCatalog.baseUrl]. Keeping them a fixed pair means a catalog entry cannot point
 * a manifest and an archive at two different origins, which would be a way to have one
 * publisher's digest bless another publisher's bytes.
 */
@Serializable
data class CatalogBundle(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val version: String,
    /** Android ABI this build serves; entries for other ABIs are ignored, not errors. */
    val abi: String,
    val path: String,
) {
    fun manifestUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/$path.json"

    fun archiveUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/$path.zip"
}

/**
 * Where this app is willing to download executable code from.
 *
 * HTTPS is required for anything off this device. Plain HTTP is permitted only for the three
 * addresses that cannot leave the machine — `localhost`, the loopback range, and the Android
 * emulator's host alias — which is the same set `res/xml/network_security_config.xml`
 * reopens at the OS level. Keeping the two in step matters: a URL this accepts must also be
 * one Android will actually let the request reach, or the failure is a confusing
 * "cleartext not permitted" instead of a clear one. A bundle server on the local network is
 * therefore served over HTTPS; the supported cleartext path is `adb reverse`.
 */
object BundleUrlPolicy {

    fun isPermitted(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        // URI reports an IPv6 host wrapped in brackets (`[::1]`); the brackets are not part
        // of the address and would make every literal comparison below miss.
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocal(host)
            else -> false
        }
    }

    private fun isLocal(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        // The Android emulator reaches its host machine through this fixed alias.
        if (host == "10.0.2.2") return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 127
    }
}
