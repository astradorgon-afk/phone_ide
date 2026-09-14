package dev.mobileforge.runtime.exec

import dev.mobileforge.runtime.api.ProcessOutput

/**
 * A fixed-capacity ring buffer of process output.
 *
 * RISK-009 is memory pressure, and unbounded log retention is the classic way a terminal
 * emulator on a phone gets killed by the OS: a build that prints a million lines would
 * otherwise be a million retained strings. This keeps the most recent [capacity] lines and
 * drops the oldest, which is the behaviour a scrollback buffer should have anyway.
 *
 * Individual lines are also truncated: a single minified-bundle line can be megabytes, and one
 * such line defeats a line-count cap entirely.
 *
 * Not thread-safe by itself; [DefaultProcessManager] confines access to one coroutine per
 * process.
 */
class BoundedOutputBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxLineLength > 0) { "maxLineLength must be positive" }
    }

    private val lines = ArrayDeque<ProcessOutput>(minOf(capacity, INITIAL_ALLOCATION))

    /** Total lines produced, including ones already evicted. Shown as "N lines (M dropped)". */
    var totalProduced: Long = 0
        private set

    val droppedCount: Long
        get() = (totalProduced - lines.size).coerceAtLeast(0)

    val size: Int get() = lines.size

    fun add(output: ProcessOutput) {
        totalProduced++
        val capped = if (output.line.length > maxLineLength) {
            output.copy(line = output.line.take(maxLineLength) + TRUNCATION_MARKER)
        } else {
            output
        }
        lines.addLast(capped)
        while (lines.size > capacity) lines.removeFirst()
    }

    /** A snapshot, oldest first. Copied so callers cannot mutate the buffer. */
    fun snapshot(): List<ProcessOutput> = lines.toList()

    fun clear() {
        lines.clear()
        totalProduced = 0
    }

    companion object {
        /** Roughly a screenful of scrollback on a phone, several times over. */
        const val DEFAULT_CAPACITY = 5_000
        const val DEFAULT_MAX_LINE_LENGTH = 4_000
        const val TRUNCATION_MARKER = "… [line truncated]"
        private const val INITIAL_ALLOCATION = 256
    }
}
