package dev.mobileforge.core.security

/** Bundle names are portable relative paths, never aliases or platform-specific paths. */
object BundlePathPolicy {
    fun isSafeIdentifier(value: String): Boolean =
        value.length in 1..100 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))

    fun isSafeEntry(value: String): Boolean {
        if (value.isEmpty() || value.length > 4096 || value.any { it.code < 32 }) return false
        if ('\\' in value || ':' in value || value.startsWith('/')) return false
        return value.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
