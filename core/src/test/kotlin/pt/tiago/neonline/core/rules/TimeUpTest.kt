package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player

class TimeUpTest {

    @Test
    fun `time up plays a valid column deterministically`() {
        val s = Boards.blank(moveClockSec = 10)
        val first = GameEngine.apply(s, Move.TimeUp)
        val second = GameEngine.apply(s, Move.TimeUp)
        val played = first.events.filterIsInstance<GameEvent.TimeUpPlayed>()
        assertEquals(1, played.size)
        // Same state, same choice: the auxiliary stream is part of the state.
        assertEquals(played[0].col, second.events.filterIsInstance<GameEvent.TimeUpPlayed>()[0].col)
        assertEquals(first.state, second.state)
    }

    @Test
    fun `time up consumes the auxiliary stream`() {
        val s = Boards.blank(moveClockSec = 10)
        val r = GameEngine.apply(s, Move.TimeUp)
        assertNotEquals(s.auxStateHex, r.state.auxStateHex)
    }

    @Test
    fun `time up never picks a locked column`() {
        val s = Boards.state(
            "#..####",
            "#..####",
            "#..####",
            "#..####",
            "#..####",
            "#..####",
            inventory = mapOf(Player.P1 to mapOf(pt.tiago.neonline.core.model.PieceKind.LOCK to 1)),
            moveClockSec = 6,
        )
        // P1 locks column 1 (not the only free column), then P2 runs out of clock: the only
        // playable columns are 1 (locked) and 2, so TimeUp must choose column 2.
        val afterLock = GameEngine.apply(s, Move.Drop(1, pt.tiago.neonline.core.model.PieceKind.LOCK)).state
        val r = GameEngine.apply(afterLock, Move.TimeUp)
        val played = r.events.filterIsInstance<GameEvent.TimeUpPlayed>()
        assertEquals(1, played.size)
        assertEquals(2, played[0].col)
        val dropped = r.events.filterIsInstance<GameEvent.Dropped>()
        assertEquals(1, dropped.size)
        assertEquals(2, dropped[0].col)
    }

    @Test
    fun `time up with a full board is rejected`() {
        val s = Boards.state(
            "1122",
            "1221",
            "2112",
            "2211",
            moveClockSec = 6,
        )
        val r = GameEngine.apply(s, Move.TimeUp)
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
    }

    @Test
    fun `the same seed gives the same time up choices in two games`() {
        fun play(seed: String): List<Int> {
            var s = Boards.blank(seedHex = seed, moveClockSec = 5)
            val cols = mutableListOf<Int>()
            repeat(6) {
                if (s.status !is pt.tiago.neonline.core.model.Status.InProgress) return cols
                val r = GameEngine.apply(s, Move.TimeUp)
                cols += r.events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col
                s = r.state
            }
            return cols
        }
        assertEquals(play("aaaaaaaaaaaaaaaa"), play("aaaaaaaaaaaaaaaa"))
    }
}
