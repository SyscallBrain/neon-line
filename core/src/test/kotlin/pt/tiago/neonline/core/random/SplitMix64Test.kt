package pt.tiago.neonline.core.random

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitMix64Test {
    @Test
    fun `same seed produces the same stream`() {
        val a = SplitMix64(0x1234abcdL)
        val b = SplitMix64(0x1234abcdL)
        repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `nextInt stays inside the bound`() {
        val rng = SplitMix64(42L)
        repeat(1000) {
            val v = rng.nextInt(7)
            assertTrue(v in 0..6)
        }
    }

    @Test
    fun `fromHex round trips through stateHex`() {
        val rng = SplitMix64.fromHex("deadbeefcafe1234")
        assertEquals("deadbeefcafe1234", rng.stateHex())
    }
}
