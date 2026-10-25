package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status

/** Engine invariants, rule combinations and the corner cases the campaign content will hit. */
class EdgeCasesTest {

    @Test
    fun `a locked only free column leaves the opponent without moves and draws`() {
        val base = Boards.state(
            "1122",
            "1221",
            "2112",
            "22..",
        )
        val locked = base.copy(lockedCol = 3, lockedFor = Player.P2)
        // P1 fills column 2; afterwards column 3 is the only free one and it is locked for P2.
        val r = GameEngine.apply(locked, Move.Drop(2))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Draw)
        assertEquals("no_moves", (outcome as MatchOutcome.Draw).reason)
        assertTrue(r.state.status is Status.Draw)
    }

    @Test
    fun `apply never mutates the state it was given`() {
        val s = Boards.blank(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        val before = s.copy()
        GameEngine.apply(s, Move.Drop(3, PieceKind.BOMB))
        GameEngine.apply(s, Move.Drop(0))
        assertEquals(before, s)
    }

    @Test
    fun `time up also works with the pop out rule`() {
        val s = Boards.blank(popOut = true, moveClockSec = 6)
        val r = GameEngine.apply(s, Move.TimeUp)
        val played = r.events.filterIsInstance<GameEvent.TimeUpPlayed>()
        assertEquals(1, played.size)
        val dropped = r.events.filterIsInstance<GameEvent.Dropped>()
        assertEquals(1, dropped.size)
        assertEquals(played[0].col, dropped[0].col)
    }

    @Test
    fun `pop out and rotation combine in one level`() {
        var s = Boards.blank(popOut = true, rotateEvery = 2)
        s = GameEngine.apply(s, Move.Drop(0)).state
        assertEquals(7, s.width) // no rotation yet
        s = GameEngine.apply(s, Move.Drop(1)).state
        assertEquals(6, s.width) // rotated on the second move overall
        // Column layout changed with the board; the moves still come from the engine.
        val moves = GameEngine.validMoves(s)
        assertTrue(moves.all { it is Move.Pop || it is Move.Drop })
        s = GameEngine.apply(s, Move.Drop(0)).state
        s = GameEngine.apply(s, Move.Drop(1)).state
        assertEquals(2, s.orientation) // four moves: two rotations
        assertNoFloating(s)
        // Four rotations (eight moves) return the board to its original shape.
        repeat(4) { s = GameEngine.apply(s, Move.Drop(it % 6)).state }
        assertEquals(0, s.orientation)
        assertEquals(7, s.width)
        assertNoFloating(s)
    }

    @Test
    fun `valid moves exclude the locked column`() {
        val s = Boards.blank(inventory = mapOf(Player.P1 to mapOf(PieceKind.LOCK to 1)))
        val afterLock = GameEngine.apply(s, Move.Drop(2, PieceKind.LOCK)).state
        val p2Moves = GameEngine.validMoves(afterLock)
        assertTrue(p2Moves.none { it is Move.Drop && it.col == 2 })
        assertTrue(p2Moves.any { it is Move.Drop && it.col == 0 })
        // Once P2 has moved elsewhere the column is free again.
        val afterP2 = GameEngine.apply(afterLock, Move.Drop(0)).state
        assertTrue(GameEngine.validMoves(afterP2).any { it is Move.Drop && it.col == 2 })
    }

    @Test
    fun `pop is rejected after the match ended`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            popOut = true,
        )
        val won = GameEngine.apply(s, Move.Drop(3)).state
        val r = GameEngine.apply(won, Move.Pop(0))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(won, r.state)
    }

    @Test
    fun `the initial history records the starting position`() {
        val s = Boards.blank(popOut = true)
        assertEquals(1, s.history.size)
        val after = GameEngine.apply(s, Move.Drop(0)).state
        assertEquals(2, after.history.size)
    }

    @Test
    fun `a full board with a line still ends as a win, not a draw`() {
        // P1 fills the last cell of the 4x4 and completes a horizontal four on the bottom row.
        val s = Boards.state(
            "2211",
            "1122",
            "2211",
            ".111",
        )
        val r = GameEngine.apply(s, Move.Drop(0))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(Player.P1, outcome.player)
        assertEquals(1, outcome.lines.size)
        assertEquals(listOf(12, 13, 14, 15), outcome.lines[0].cells)
    }

    @Test
    fun `threats are not computed once the match is over`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        assertTrue(r.events.none { it is GameEvent.ThreatCreated })
        assertTrue(r.events.any { it is GameEvent.MatchOver })
    }

    @Test
    fun `events keep the documented order for a plain drop`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(3))
        val kinds = r.events.map { it::class.simpleName }
        assertEquals(listOf("Dropped", "TurnChanged"), kinds)
        assertFalse(r.events.isEmpty())
    }

    @Test
    fun `a rejected move keeps the rng streams untouched`() {
        val s = Boards.blank(moveClockSec = 5)
        val r = GameEngine.apply(s, Move.Drop(9))
        assertEquals(s.auxStateHex, r.state.auxStateHex)
        assertEquals(s.aiStateHex, r.state.aiStateHex)
        assertNull(r.outcome)
    }
}
