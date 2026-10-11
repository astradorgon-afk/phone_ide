package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Catalog parsing and the download-address policy.
 *
 * The policy is the security-relevant half: a bundle becomes executable files in `$PREFIX`,
 * so the app must refuse to pull one over cleartext from a host it cannot vouch for. The
 * exception for local addresses is what lets a person serve their own bundles from a PC.
 */
class BundleCatalogTest {

    private val catalogText = """
        {
          "schema": 1,
          "base_url": "https://bundles.example.com",
          "bundles": [
            {"id": "base", "display_name": "Base", "version": "1.0.0",
             "abi": "x86_64", "path": "base-1.0.0-x86_64"},
            {"id": "php", "display_name": "PHP", "version": "8.5.1",
             "abi": "x86_64", "path": "php-8.5.1-x86_64"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a catalog and derives manifest and archive urls`() {
        val catalog = BundleCatalog.parse(catalogText)

        assertThat(catalog.bundles).hasSize(2)
        val php = catalog.bundles[1]
        assertThat(php.manifestUrl(catalog.baseUrl))
            .isEqualTo("https://bundles.example.com/php-8.5.1-x86_64.json")
        assertThat(php.archiveUrl(catalog.baseUrl))
            .isEqualTo("https://bundles.example.com/php-8.5.1-x86_64.zip")
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        val catalog = BundleCatalog.parse(
            catalogText.replace("https://bundles.example.com\"", "https://bundles.example.com/\""),
        )

        assertThat(catalog.bundles.first().manifestUrl(catalog.baseUrl))
            .isEqualTo("https://bundles.example.com/base-1.0.0-x86_64.json")
    }

    @Test
    fun `an unknown schema is refused rather than guessed at`() {
        val failure = runCatching {
            BundleCatalog.parse(catalogText.replace("\"schema\": 1", "\"schema\": 99"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure!!.message).contains("99")
    }

    @Test
    fun `a path that could escape the download directory is refused`() {
        val failure = runCatching {
            BundleCatalog.parse(
                catalogText.replace("base-1.0.0-x86_64", "../../etc/passwd"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a cleartext public base url is refused`() {
        val failure = runCatching {
            BundleCatalog.parse(
                catalogText.replace("https://bundles.example.com", "http://bundles.example.com"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `https is permitted anywhere`() {
        assertThat(BundleUrlPolicy.isPermitted("https://bundles.example.com/x.json")).isTrue()
        assertThat(BundleUrlPolicy.isPermitted("https://cdn.example.org/a/b.zip")).isTrue()
    }

    @Test
    fun `cleartext is permitted only on this machine`() {
        assertThat(BundleUrlPolicy.isPermitted("http://127.0.0.1:8080/x.zip")).isTrue()
        assertThat(BundleUrlPolicy.isPermitted("http://localhost:8080/x.zip")).isTrue()
        assertThat(BundleUrlPolicy.isPermitted("http://127.5.5.5/x.zip")).isTrue()
        assertThat(BundleUrlPolicy.isPermitted("http://[::1]:8080/x.zip")).isTrue()
        // The emulator's alias for its host. Nothing else on a LAN is expressible at the OS
        // level, so nothing else is accepted here either.
        assertThat(BundleUrlPolicy.isPermitted("http://10.0.2.2:8080/x.zip")).isTrue()
    }

    @Test
    fun `cleartext is refused everywhere else`() {
        assertThat(BundleUrlPolicy.isPermitted("http://bundles.example.com/x.zip")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("http://192.168.1.20:8080/x.zip")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("http://10.0.0.5/x.zip")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("http://172.16.4.4/x.zip")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("http://8.8.8.8/x.zip")).isFalse()
    }

    @Test
    fun `non-http schemes are refused`() {
        assertThat(BundleUrlPolicy.isPermitted("ftp://bundles.example.com/x.zip")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("file:///etc/passwd")).isFalse()
        assertThat(BundleUrlPolicy.isPermitted("not a url at all")).isFalse()
    }
}
