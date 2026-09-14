package dev.mobileforge.runtime.exec

/**
 * Resolves `#!` interpreter lines.
 *
 * This is not optional polish. Under system-linker exec the kernel is handed
 * `/system/bin/linker64`, not the script, so **the kernel's own shebang handling never runs**.
 * A script invoked without this would fail with a confusing ELF error, and `composer`,
 * `artisan`, `npm` and most of a Termux `bin/` are scripts.
 *
 * So we do what the kernel would have done: read the `#!` line, and exec the interpreter with
 * the script as an argument.
 *
 * Deliberately kept as pure parsing over a supplied header, so every edge case is testable
 * without writing files.
 */
object ShebangResolver {

    /**
     * Linux caps the shebang line at 127 bytes (BINPRM_BUF_SIZE - 1) and silently truncates
     * beyond it. Matching that limit means our behaviour matches the kernel's rather than
     * being more permissive than the thing we are emulating.
     */
    const val MAX_SHEBANG_BYTES = 127

    /**
     * Parses the first line of a file.
     *
     * Returns null when there is no shebang — meaning the file is an ELF binary, or is not
     * executable at all, and the caller should treat it as a binary.
     */
    fun parse(header: ByteArray): Shebang? {
        if (header.size < 2) return null
        if (header[0] != '#'.code.toByte() || header[1] != '!'.code.toByte()) return null

        val limit = minOf(header.size, MAX_SHEBANG_BYTES)
        val newlineAt = (2 until limit).firstOrNull {
            header[it] == '\n'.code.toByte() || header[it] == '\r'.code.toByte()
        } ?: limit

        val line = String(header, 2, newlineAt - 2, Charsets.UTF_8).trim()
        if (line.isEmpty()) return null

        // The kernel splits into interpreter + AT MOST ONE argument, and does not tokenise
        // further: "#!/usr/bin/env -S node --flag" passes "-S node --flag" as a single argv
        // entry. Reproducing that exactly avoids surprises where a script works in the
        // terminal but not here.
        val firstSpace = line.indexOf(' ')
        return if (firstSpace < 0) {
            Shebang(interpreter = line, argument = null)
        } else {
            val interpreter = line.substring(0, firstSpace)
            val argument = line.substring(firstSpace + 1).trim().takeIf { it.isNotEmpty() }
            Shebang(interpreter = interpreter, argument = argument)
        }
    }

    /** True if the header is an ELF binary (magic 0x7F 'E' 'L' 'F'). */
    fun isElf(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x7F.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()

    /**
     * True if the ELF header describes a dynamically-linked executable or shared object.
     *
     * System-linker exec only works for these. A statically-linked binary cannot be loaded by
     * the linker and would fail at exec time, so detecting it here lets us say why instead of
     * surfacing a raw failure. ELF `e_type` sits at offset 16, little-endian.
     *
     *   2 = ET_EXEC (static or dynamic executable)
     *   3 = ET_DYN  (PIE executable or shared object) - what the NDK produces
     */
    fun isDynamicElf(header: ByteArray): Boolean {
        if (!isElf(header) || header.size < 18) return false
        val eType = (header[16].toInt() and 0xFF) or ((header[17].toInt() and 0xFF) shl 8)
        return eType == ET_DYN
    }

    private const val ET_DYN = 3
}

/**
 * A parsed interpreter line.
 *
 * [argument] is the single optional argument the kernel would pass, unsplit.
 */
data class Shebang(
    val interpreter: String,
    val argument: String?,
) {
    /** Argv prefix the interpreter should be invoked with, before the script path. */
    fun argvPrefix(): List<String> =
        if (argument == null) listOf(interpreter) else listOf(interpreter, argument)
}
