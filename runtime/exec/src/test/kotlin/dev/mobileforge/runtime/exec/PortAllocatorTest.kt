package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppResult
import org.junit.Test

class PortAllocatorTest {

    /** Everything free except an explicit set, so collisions are deterministic. */
    private fun probeExcluding(vararg taken: Int) = PortProbe { it !in taken.toSet() }

    @Test
    fun `allocates the preferred port when free`() {
        val allocator = PortAllocator(probeExcluding())

        val port = allocator.allocate(PortAllocator.LARAVEL_SERVE, "laravel")

        assertThat(port.getOrNull()).isEqualTo(8000)
    }

    @Test
    fun `moves to the next free port when the preferred one is taken by another app`() {
        val allocator = PortAllocator(probeExcluding(8000, 8001))

        val port = allocator.allocate(8000, "laravel")

        assertThat(port.getOrNull()).isEqualTo(8002)
    }

    @Test
    fun `does not hand the same port to two owners`() {
        val allocator = PortAllocator(probeExcluding())

        val first = allocator.allocate(8000, "project-a").getOrNull()
        val second = allocator.allocate(8000, "project-b").getOrNull()

        assertThat(first).isEqualTo(8000)
        assertThat(second).isEqualTo(8001)
    }

    @Test
    fun `a released port can be reused`() {
        val allocator = PortAllocator(probeExcluding())
        allocator.allocate(8000, "a")
        allocator.release(8000)

        assertThat(allocator.allocate(8000, "b").getOrNull()).isEqualTo(8000)
    }

    @Test
    fun `tracks which owner holds which port`() {
        val allocator = PortAllocator(probeExcluding())
        allocator.allocate(5173, "vite")

        assertThat(allocator.portFor("vite")).isEqualTo(5173)
        assertThat(allocator.owner(5173)).isEqualTo("vite")
    }

    @Test
    fun `releaseAllFor frees every port an owner held`() {
        val allocator = PortAllocator(probeExcluding())
        allocator.allocate(8000, "project-a")
        allocator.allocate(5173, "project-a")
        allocator.allocate(9000, "project-b")

        allocator.releaseAllFor("project-a")

        assertThat(allocator.reserved().keys).containsExactly(9000)
    }

    @Test
    fun `reconcile drops reservations for processes the OS killed`() {
        // RISK-002: Android kills without unwinding, so nothing calls release().
        val allocator = PortAllocator(probeExcluding())
        allocator.allocate(8000, "alive")
        allocator.allocate(5173, "killed-by-android")

        allocator.reconcile(liveOwnerIds = setOf("alive"))

        assertThat(allocator.reserved()).containsExactly(8000, "alive")
    }

    @Test
    fun `reports exhaustion with a usable recovery`() {
        val allocator = PortAllocator(PortProbe { false })

        val result = allocator.allocate(8000, "x", searchRange = 4)

        assertThat(result.isSuccess).isFalse()
        assertThat((result as AppResult.Failure).error.detail).contains("8000 to 8003")
        assertThat(result.error.recovery).isNotNull()
    }

    @Test
    fun `rejects privileged ports`() {
        // Android will not grant an app the privilege to bind below 1024.
        val result = PortAllocator(probeExcluding()).allocate(80, "web")

        assertThat(result.isSuccess).isFalse()
        assertThat((result as AppResult.Failure).error.detail).contains("outside the allowed range")
    }

    @Test
    fun `rejects a port above the valid range`() {
        assertThat(PortAllocator(probeExcluding()).allocate(70_000, "x").isSuccess).isFalse()
    }
}
