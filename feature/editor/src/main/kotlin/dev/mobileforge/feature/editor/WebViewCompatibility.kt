package dev.mobileforge.feature.editor

import android.content.Context
import androidx.webkit.WebViewCompat

/**
 * Checks whether this device's WebView can actually run Monaco.
 *
 * **This exists because of a real failure, found by running the app on a device.** An Android 11
 * emulator carries the stock AOSP WebView — Chromium 83, from 2020 — and Monaco 0.52 is built
 * for ES2022. It fails to parse with `Uncaught SyntaxError: Unexpected token '{'` on a static
 * class initialisation block, and the user sees a blank pane with no explanation whatsoever.
 *
 * The critical detail is that **WebView version is independent of Android version.** WebView
 * ships as an updatable app, so a current Android 15 device with updates disabled can carry an
 * ancient engine, while an Android 11 device with Play Services can be fully current. Gating on
 * `Build.VERSION.SDK_INT` would therefore be wrong in both directions.
 *
 * So we detect the actual engine and, when it is too old, say so with something the user can
 * act on — rather than rendering nothing. See docs/adr/ADR-003-monaco-integration.md.
 */
object WebViewCompatibility {

    /**
     * Minimum Chromium major version.
     *
     * 94 is where static initialisation blocks landed, which is the specific syntax Monaco
     * 0.52 uses that Chromium 83 cannot parse. Raising Monaco's version may raise this floor;
     * lowering the floor means pinning an older Monaco.
     */
    const val MINIMUM_CHROMIUM_MAJOR = 94

    fun check(context: Context): WebViewStatus {
        val packageInfo = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }
            .getOrNull()
            ?: return WebViewStatus.Missing

        val versionName = packageInfo.versionName ?: return WebViewStatus.Unknown(
            packageName = packageInfo.packageName,
        )

        val major = parseMajorVersion(versionName)
            ?: return WebViewStatus.Unknown(packageName = packageInfo.packageName)

        return if (major >= MINIMUM_CHROMIUM_MAJOR) {
            WebViewStatus.Supported(versionName = versionName, majorVersion = major)
        } else {
            WebViewStatus.TooOld(
                versionName = versionName,
                majorVersion = major,
                packageName = packageInfo.packageName,
            )
        }
    }

    /**
     * Extracts the Chromium major version from a WebView version name.
     *
     * Version names look like "83.0.4103.120" or "120.0.6099.230". Pure and separately
     * testable, because a parsing slip here would either block a working device or let a
     * broken one through to a blank screen.
     */
    fun parseMajorVersion(versionName: String): Int? =
        versionName.substringBefore('.').trim().toIntOrNull()?.takeIf { it > 0 }
}

sealed interface WebViewStatus {

    data class Supported(val versionName: String, val majorVersion: Int) : WebViewStatus

    /** Present but too old to run the editor. Carries everything needed to explain it. */
    data class TooOld(
        val versionName: String,
        val majorVersion: Int,
        val packageName: String,
    ) : WebViewStatus

    /** No WebView provider at all — rare, but it happens on stripped-down builds. */
    data object Missing : WebViewStatus

    /**
     * A provider exists but its version could not be read.
     *
     * Deliberately treated as USABLE: refusing to open the editor because we could not parse a
     * version string would punish a probably-fine device for our own uncertainty. If it really
     * is too old, the editor surfaces the load error instead.
     */
    data class Unknown(val packageName: String) : WebViewStatus

    val canRunEditor: Boolean
        get() = this is Supported || this is Unknown

    /** One line for the diagnostics screen. */
    val summary: String
        get() = when (this) {
            is Supported -> "Chromium $majorVersion ($versionName)"
            is TooOld -> "Chromium $majorVersion ($versionName) — too old, " +
                "$MIN needed"
            Missing -> "No WebView provider installed"
            is Unknown -> "Version unreadable ($packageName)"
        }

    private companion object {
        const val MIN = "Chromium ${WebViewCompatibility.MINIMUM_CHROMIUM_MAJOR}+"
    }
}
