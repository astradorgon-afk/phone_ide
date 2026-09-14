package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out local ports for dev servers, and remembers who holds what.
 *
 * Two problems this solves that a naive "just use 8000" does not:
 *
 * 1. **Collisions.** A Laravel server and a Vite server started in two projects both want
 *    their default port. The second silently failing to bind, or binding somewhere the preview
 *    does not look, is a confusing failure.
 * 2. **Leaks.** Android kills processes without unwinding anything, so a port can be recorded
 *    as taken by a process that no longer exists. [release] is called from the process
 *    manager's exit path, and [reconcile] drops entries for processes that are gone.
 *
 * Binding is checked by actually opening a socket, because another app on the device may hold
 * the port and no amount of internal bookkeeping would know.
 */
class PortAllocator(
    private val probe: PortProbe = SocketPortProbe,
) {

    private val reservations = ConcurrentHashMap<Int, String>()

    /**
     * Reserves [preferred] if it is free, otherwise the next free port after it.
     *
     * Preferring the conventional port matters: a developer expects `php artisan serve` on
     * 8000 and Vite on 5173, and quietly moving them makes tutorials and bookmarks wrong.
     * We only move when we must, and the caller is told which port was actually taken.
     */
    fun allocate(preferred: Int, ownerId: String, searchRange: Int = DEFAULT_SEARCH): AppResult<Int> {
        if (preferred !in MIN_PORT..MAX_PORT) {
            return AppError(
                category = ErrorCategory.Validation,
                message = "That port number is not usable.",
                detail = "$preferred is outside the allowed range $MIN_PORT-$MAX_PORT.",
                recovery = "Choose a port between $MIN_PORT and $MAX_PORT.",
            ).asFailure()
        }

        for (candidate in preferred until minOf(preferred + searchRange, MAX_PORT + 1)) {
            if (reservations.containsKey(candidate)) continue
            if (!probe.isFree(candidate)) continue
            // putIfAbsent guards two coroutines racing for the same candidate.
            if (reservations.putIfAbsent(candidate, ownerId) == null) return candidate.asSuccess()
        }

        return AppError(
            category = ErrorCategory.Unavailable,
            message = "No free port was available.",
            detail = "Ports $preferred to ${preferred + searchRange - 1} are all in use.",
            recovery = "Stop a running server, or choose a different port.",
        ).asFailure()
    }

    fun release(port: Int) {
        reservations.remove(port)
    }

    fun releaseAllFor(ownerId: String) {
        reservations.entries.removeIf { it.value == ownerId }
    }

    fun portFor(ownerId: String): Int? =
        reservations.entries.firstOrNull { it.value == ownerId }?.key

    fun owner(port: Int): String? = reservations[port]

    fun reserved(): Map<Int, String> = reservations.toMap()

    /**
     * Drops reservations whose owner is no longer alive.
     *
     * Called after the process list changes. Without it, an Android-killed server would hold
     * its port reservation for the lifetime of the app.
     */
    fun reconcile(liveOwnerIds: Set<String>) {
        reservations.entries.removeIf { it.value !in liveOwnerIds }
    }

    companion object {
        /** Below 1024 needs privileges Android will not grant an app. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535
        const val DEFAULT_SEARCH = 64

        /** Conventional defaults, so the common case lands where developers expect. */
        const val LARAVEL_SERVE = 8000
        const val VITE_DEV = 5173
    }
}

/** Whether a port can actually be bound. An interface so allocation is testable. */
fun interface PortProbe {
    fun isFree(port: Int): Boolean
}

/**
 * Real check: bind and immediately close.
 *
 * `reuseAddress` is deliberately left OFF. With it enabled we could bind a port still in
 * TIME_WAIT and report it free, only for the real server to fail — the exact false positive
 * this probe exists to prevent.
 */
object SocketPortProbe : PortProbe {
    override fun isFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
