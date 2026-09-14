package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Prefix matching between a bundle and the device.
 *
 * Android exposes an app's data directory under two paths — `/data/data/<pkg>` and
 * `/data/user/<id>/<pkg>` — and they are the same directory for user 0. `Context.filesDir`
 * reports the second form; build systems that bake a prefix into binaries, termux-packages
 * included, use the first. A plain string comparison therefore rejected a bundle that worked
 * perfectly, which is exactly what happened the first time a real package was installed.
 *
 * The risk in fixing that is over-correcting into leniency, so the rejection cases below matter
 * at least as much as the acceptance ones: a bundle built for another app must still be refused,
 * because its binaries carry that app's paths internally and will not find their libraries here.
 */
class ToolchainPrefixMatchTest {

    @Test
    fun `identical prefixes match`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/data/dev.mobileforge/files/usr",
            ),
        ).isTrue()
    }

    @Test
    fun `the two spellings of the same directory match`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/user/0/dev.mobileforge/files/usr",
            ),
        ).isTrue()
    }

    @Test
    fun `matching is symmetric`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/user/0/dev.mobileforge/files/usr",
                "/data/data/dev.mobileforge/files/usr",
            ),
        ).isTrue()
    }

    @Test
    fun `a trailing slash does not change the answer`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr/",
                "/data/user/0/dev.mobileforge/files/usr",
            ),
        ).isTrue()
    }

    @Test
    fun `a secondary android user matches its data-data spelling`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/user/10/dev.mobileforge/files/usr",
            ),
        ).isTrue()
    }

    // --- Must still be refused ----------------------------------------------------

    @Test
    fun `another app's prefix is refused`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/com.termux/files/usr",
                "/data/user/0/dev.mobileforge/files/usr",
            ),
        ).isFalse()
    }

    @Test
    fun `a debug-suffixed application id is refused`() {
        // The exact case that made bundles unusable and prompted removing the suffix.
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/user/0/dev.mobileforge.debug/files/usr",
            ),
        ).isFalse()
    }

    @Test
    fun `a different subdirectory under the same app is refused`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/user/0/dev.mobileforge/files/other",
            ),
        ).isFalse()
    }

    @Test
    fun `a prefix outside app storage is refused`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/sdcard/dev.mobileforge/files/usr",
            ),
        ).isFalse()
    }

    @Test
    fun `a lookalike path is not rewritten`() {
        // `/data/userdata/...` merely starts with the same characters; rewriting it would be a
        // bug, so it must not normalise to anything.
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/userdata/dev.mobileforge/files/usr",
            ),
        ).isFalse()
    }

    @Test
    fun `a non-numeric user id is not rewritten`() {
        assertThat(
            ToolchainCompatibility.prefixesMatch(
                "/data/data/dev.mobileforge/files/usr",
                "/data/user/zero/dev.mobileforge/files/usr",
            ),
        ).isFalse()
    }
}
