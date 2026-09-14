package dev.mobileforge.runtime

import android.content.Context
import android.os.Build
import dev.mobileforge.runtime.exec.ExecEnvironment
import dev.mobileforge.runtime.exec.FileHeaderReader
import java.io.File
import java.io.IOException

/**
 * The only place that reads Android's own APIs to describe the execution environment.
 *
 * Everything downstream of this takes [ExecEnvironment] as plain data, which is what keeps
 * `:runtime:exec` pure and its logic testable against API levels and ABIs this machine is not.
 */
object AndroidRuntimeFactory {

    fun execEnvironment(context: Context): ExecEnvironment = ExecEnvironment(
        deviceSdkInt = Build.VERSION.SDK_INT,
        // Read from applicationInfo rather than BuildConfig so it stays correct if the
        // manifest merger or a build variant changes the effective target.
        appTargetSdk = context.applicationInfo.targetSdkVersion,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        filesDir = context.filesDir.absolutePath,
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
    )

    /**
     * Reads file headers for shebang and ELF detection.
     *
     * Returns null rather than throwing for a missing or unreadable file: "not installed" is a
     * normal, expected answer here, not an exceptional one.
     */
    fun fileHeaderReader(): FileHeaderReader = FileHeaderReader { path, maxBytes ->
        try {
            val file = File(path)
            if (!file.isFile || !file.canRead()) {
                null
            } else {
                file.inputStream().use { stream ->
                    val buffer = ByteArray(maxBytes)
                    val read = stream.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
