package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The strategy decision is the hinge the whole runtime turns on.
 *
 * If it picks Direct where W^X applies, nothing runs and the failure is a bare EACCES. If it
 * picks SystemLinker for a system binary, we add a pointless indirection that breaks
 * `/proc/self/exe` for no reason. Both cases are covered here.
 */
class ExecStrategySelectorTest {

    private fun env(
        deviceSdk: Int = 34,
        targetSdk: Int = 35,
        abi: String = "arm64-v8a",
    ) = ExecEnvironment(
        deviceSdkInt = deviceSdk,
        appTargetSdk = targetSdk,
        primaryAbi = abi,
        filesDir = "/data/data/dev.mobileforge/files",
        nativeLibraryDir = "/data/app/dev.mobileforge/lib/arm64",
    )

    private fun selector(environment: ExecEnvironment = env()) = ExecStrategySelector(environment)

    // ---------- the main path ----------

    @Test
    fun `app storage on a modern device uses the system linker`() {
        val strategy = selector().strategyFor("/data/data/dev.mobileforge/files/usr/bin/php")

        assertThat(strategy).isInstanceOf(ExecStrategy.SystemLinker::class.java)
        assertThat((strategy as ExecStrategy.SystemLinker).linkerPath)
            .isEqualTo("/system/bin/linker64")
    }

    @Test
    fun `a 32-bit device selects the 32-bit linker`() {
        val strategy = selector(env(abi = "armeabi-v7a"))
            .strategyFor("/data/data/dev.mobileforge/files/usr/bin/php")

        assertThat((strategy as ExecStrategy.SystemLinker).linkerPath)
            .isEqualTo("/system/bin/linker")
    }

    @Test
    fun `x86_64 emulator is treated as 64-bit`() {
        val strategy = selector(env(abi = "x86_64"))
            .strategyFor("/data/data/dev.mobileforge/files/usr/bin/php")

        assertThat((strategy as ExecStrategy.SystemLinker).linkerPath)
            .isEqualTo("/system/bin/linker64")
    }

    // ---------- locations that are already executable ----------

    @Test
    fun `system binaries run directly`() {
        assertThat(selector().strategyFor("/system/bin/sh")).isEqualTo(ExecStrategy.Direct)
    }

    @Test
    fun `apex binaries run directly`() {
        assertThat(selector().strategyFor("/apex/com.android.runtime/bin/linker"))
            .isEqualTo(ExecStrategy.Direct)
    }

    @Test
    fun `native library directory runs directly`() {
        // This directory is read-only, so it is executable without violating W^X.
        assertThat(selector().strategyFor("/data/app/dev.mobileforge/lib/arm64/libphp.so"))
            .isEqualTo(ExecStrategy.Direct)
    }

    @Test
    fun `a sibling of the native library directory does not run directly`() {
        // Prefix-only matching would wrongly accept ".../lib/arm64-evil/x".
        val strategy = selector().strategyFor("/data/app/dev.mobileforge/lib/arm64-evil/x")
        assertThat(strategy).isInstanceOf(ExecStrategy.SystemLinker::class.java)
    }

    // ---------- when W^X does not apply ----------

    @Test
    fun `an app targeting below API 29 may exec app storage directly`() {
        // The historical Termux loophole. Still correct to model, even though Play forbids it.
        val strategy = selector(env(deviceSdk = 34, targetSdk = 28))
            .strategyFor("/data/data/dev.mobileforge/files/usr/bin/php")

        assertThat(strategy).isEqualTo(ExecStrategy.Direct)
    }

    @Test
    fun `a device below API 29 may exec app storage directly`() {
        val strategy = selector(env(deviceSdk = 28, targetSdk = 35))
            .strategyFor("/data/data/dev.mobileforge/files/usr/bin/php")

        assertThat(strategy).isEqualTo(ExecStrategy.Direct)
    }

    // ---------- refusals ----------

    @Test
    fun `a relative path is refused with a reason`() {
        val strategy = selector().strategyFor("bin/php")

        assertThat(strategy).isInstanceOf(ExecStrategy.Unsupported::class.java)
        assertThat((strategy as ExecStrategy.Unsupported).reason).contains("absolute")
        assertThat(strategy.recovery).isNotNull()
    }

    // ---------- environment facts ----------

    @Test
    fun `W^X applies only when both device and target are API 29 or above`() {
        assertThat(env(deviceSdk = 34, targetSdk = 35).enforcesWriteXorExecute).isTrue()
        assertThat(env(deviceSdk = 34, targetSdk = 28).enforcesWriteXorExecute).isFalse()
        assertThat(env(deviceSdk = 28, targetSdk = 35).enforcesWriteXorExecute).isFalse()
    }

    @Test
    fun `system linker exec requires android 10`() {
        assertThat(env(deviceSdk = 29).supportsSystemLinkerExec).isTrue()
        assertThat(env(deviceSdk = 28).supportsSystemLinkerExec).isFalse()
    }
}
