package dev.mobileforge.runtime.exec

/**
 * The device facts that decide how a binary can be invoked.
 *
 * Passed in as plain data rather than read from `android.os.Build` inside this module, so the
 * whole strategy layer is unit-testable against every combination of API level, target SDK and
 * ABI — including the ones the development machine is not.
 *
 * See docs/adr/ADR-002-runtime-strategy.md for why any of this is necessary.
 */
data class ExecEnvironment(
    /** `Build.VERSION.SDK_INT` — the API level the device is running. */
    val deviceSdkInt: Int,

    /** The app's own `targetSdkVersion`. Together with [deviceSdkInt] this decides W^X. */
    val appTargetSdk: Int,

    /** Primary ABI, e.g. "arm64-v8a", "x86_64", "armeabi-v7a". */
    val primaryAbi: String,

    /** Absolute path to the app's private files directory (writable, NOT executable). */
    val filesDir: String,

    /**
     * Absolute path to the APK's extracted native library directory.
     *
     * This directory IS executable — it is read-only, so it does not violate W^X. It can only
     * hold binaries packaged at build time as `lib*.so`, which is why it is a fallback for a
     * fixed core set rather than the primary mechanism.
     */
    val nativeLibraryDir: String,
) {

    val is64Bit: Boolean get() = primaryAbi in SIXTY_FOUR_BIT_ABIS

    /**
     * The system dynamic linker for this ABI.
     *
     * On a 64-bit device the app may still be running as a 32-bit process, but [primaryAbi]
     * reports the ABI the app was actually loaded with, so this matches the process.
     */
    val systemLinkerPath: String
        get() = if (is64Bit) LINKER_64 else LINKER_32

    /**
     * True when the platform forbids executing files in the app's data directory.
     *
     * Android 10 (API 29) introduced the SELinux W^X policy, and it applies based on the app's
     * *target* SDK, not the device's. An app targeting 28 on Android 14 is still exempt — which
     * is precisely the loophole Google Play's target-API requirement closes for us.
     */
    val enforcesWriteXorExecute: Boolean
        get() = deviceSdkInt >= ANDROID_10 && appTargetSdk >= ANDROID_10

    /** System-linker exec needs a linker that accepts a program argument: Android 10+. */
    val supportsSystemLinkerExec: Boolean
        get() = deviceSdkInt >= ANDROID_10

    companion object {
        const val ANDROID_10 = 29
        const val LINKER_64 = "/system/bin/linker64"
        const val LINKER_32 = "/system/bin/linker"

        private val SIXTY_FOUR_BIT_ABIS = setOf("arm64-v8a", "x86_64", "riscv64", "mips64")
    }
}
