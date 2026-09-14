package dev.mobileforge.logging

import android.util.Log
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.security.SecretRedactor
import dev.mobileforge.BuildConfig

/**
 * Logcat-backed [Logger].
 *
 * Two properties this implementation is responsible for:
 *
 * 1. **Redaction is unconditional.** Every message passes through [SecretRedactor] before it
 *    reaches logcat. Enforcing it here rather than at call sites means a future contributor
 *    cannot leak a token by forgetting — and logcat is readable by other processes in some
 *    contexts, so this genuinely matters (RISK-013).
 *
 * 2. **SECURITY is always emitted**, even in release builds where DEBUG is dropped. A blocked
 *    path escape or a refused bridge message is exactly the record you need after an incident,
 *    and a security log you compiled out is a security log you do not have.
 */
class AndroidLogger : Logger {

    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(prefixed(tag), redact(message))
    }

    override fun info(tag: String, message: String) {
        Log.i(prefixed(tag), redact(message))
    }

    override fun warn(tag: String, message: String, cause: Throwable?) {
        Log.w(prefixed(tag), redact(message), cause)
    }

    override fun error(tag: String, message: String, cause: Throwable?) {
        Log.e(prefixed(tag), redact(message), cause)
    }

    override fun security(tag: String, message: String) {
        Log.w(SECURITY_TAG, "[$tag] ${redact(message)}")
    }

    private fun redact(message: String) = SecretRedactor.redact(message)

    /** Single prefix so `adb logcat -s MF:*` catches everything the app emits. */
    private fun prefixed(tag: String) = "MF.$tag"

    private companion object {
        const val SECURITY_TAG = "MF.SECURITY"
    }
}
