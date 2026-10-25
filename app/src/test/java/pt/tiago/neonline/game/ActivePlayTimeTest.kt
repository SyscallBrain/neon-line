package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test

class ActivePlayTimeTest {
    @Test fun `only running intervals count and lifecycle callbacks are idempotent`() {
        var now = 100L
        val clock = ActivePlayTime(500L) { now }
        now += 1000
        assertEquals(500L, clock.elapsedMs)
        clock.resume(); now += 200; clock.resume(); now += 300
        assertEquals(1000L, clock.elapsedMs)
        clock.pause(); now += 5000; clock.pause()
        assertEquals(1000L, clock.elapsedMs)
        clock.resume(); now += 150
        assertEquals(1150L, clock.elapsedMs)
        clock.reset(); now += 1000
        assertEquals(0L, clock.elapsedMs)
        clock.resume(); now += 100
        assertEquals(100L, clock.elapsedMs)
    }

    @Test fun `elapsed time saturates rather than overflowing`() {
        var now = 0L
        val clock = ActivePlayTime(Long.MAX_VALUE - 5) { now }
        clock.resume(); now = 10
        assertEquals(Long.MAX_VALUE, clock.elapsedMs)
        clock.pause(); clock.resume(); now = 20
        assertEquals(Long.MAX_VALUE, clock.elapsedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative stored time is rejected`() { ActivePlayTime(-1) { 0L } }
}
