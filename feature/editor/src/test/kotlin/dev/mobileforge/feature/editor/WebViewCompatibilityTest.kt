package dev.mobileforge.feature.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Version parsing and gating.
 *
 * A slip here fails in one of two bad directions: too strict and a perfectly good device is
 * refused the editor, too loose and the user gets the blank pane this check exists to prevent.
 */
class WebViewCompatibilityTest {

    @Test
    fun `parses a standard webview version name`() {
        assertThat(WebViewCompatibility.parseMajorVersion("120.0.6099.230")).isEqualTo(120)
    }

    @Test
    fun `parses the version that actually broke the editor`() {
        // The stock AOSP WebView on the Android 11 emulator, where Monaco 0.52 fails with
        // "Unexpected token '{'" on a static initialisation block.
        assertThat(WebViewCompatibility.parseMajorVersion("83.0.4103.120")).isEqualTo(83)
    }

    @Test
    fun `parses a bare major version`() {
        assertThat(WebViewCompatibility.parseMajorVersion("94")).isEqualTo(94)
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertThat(WebViewCompatibility.parseMajorVersion(" 118.0.1 ")).isEqualTo(118)
    }

    @Test
    fun `returns null for an unparseable version`() {
        assertThat(WebViewCompatibility.parseMajorVersion("dev-build")).isNull()
    }

    @Test
    fun `returns null for an empty version`() {
        assertThat(WebViewCompatibility.parseMajorVersion("")).isNull()
    }

    @Test
    fun `returns null for a zero major version`() {
        assertThat(WebViewCompatibility.parseMajorVersion("0.1.2")).isNull()
    }

    // ---------- gating ----------

    @Test
    fun `a current webview can run the editor`() {
        val status = WebViewStatus.Supported(versionName = "120.0.6099.230", majorVersion = 120)
        assertThat(status.canRunEditor).isTrue()
    }

    @Test
    fun `an old webview cannot run the editor`() {
        val status = WebViewStatus.TooOld(
            versionName = "83.0.4103.120",
            majorVersion = 83,
            packageName = "com.android.webview",
        )
        assertThat(status.canRunEditor).isFalse()
    }

    @Test
    fun `a missing webview cannot run the editor`() {
        assertThat(WebViewStatus.Missing.canRunEditor).isFalse()
    }

    @Test
    fun `an unreadable version is allowed through rather than blocking a working device`() {
        // Refusing to open the editor because WE could not parse a string would punish the
        // user for our uncertainty. If it really is too old, the load error surfaces instead.
        assertThat(WebViewStatus.Unknown("com.google.android.webview").canRunEditor).isTrue()
    }

    @Test
    fun `the minimum is the version that introduced static initialisation blocks`() {
        // Documented so a future bump is a deliberate decision, not a drift.
        assertThat(WebViewCompatibility.MINIMUM_CHROMIUM_MAJOR).isEqualTo(94)
    }

    @Test
    fun `summary names the version found and the version needed`() {
        val summary = WebViewStatus.TooOld("83.0.4103.120", 83, "com.android.webview").summary
        assertThat(summary).contains("83")
        assertThat(summary).contains("94")
    }
}
