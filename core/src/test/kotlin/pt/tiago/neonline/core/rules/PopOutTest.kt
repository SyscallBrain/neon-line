package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player

class MaskedBoardTest {

    @Test
    fun `drop rests on top of a pillar`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "###....",
        )
        val r = GameEngine.apply(s, Move.Drop(1))
        assertEquals(4, r.state.rowOf(r.state.pieces.indexOfFirst { it != null }))
    }

    @Test
    fun `pieces stack upward from a pillar`() {
        var s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "###....",
        )
        s = GameEngine.apply(s, Move.Drop(1)).state
        s = GameEngine.apply(s, Move.Drop(1)).state
        assertEquals(Player.P1, s.pieceAt(4, 1)!!.owner)
        assertEquals(Player.P2, s.pieceAt(3, 1)!!.owner)
    }

    @Test
    fun `column blocked from top to bottom rejects drops`() {
        val s = Boards.state(
            "#......",
            "#......",
            "#......",
            "#......",
            "#......",
            "#......",
        )
        val r = GameEngine.apply(s, Move.Drop(0))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
    }

    @Test
    fun `ceiling cells are not playable but the column below works`() {
        val s = Boards.state(
            "##.....",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
        )
        val r = GameEngine.apply(s, Move.Drop(1))
        assertNotNull(r.state.pieceAt(5, 1))
        // Column 0 is only blocked at the top: it is still playable below the ceiling.
        val r2 = GameEngine.apply(s, Move.Drop(0))
        assertNotNull(r2.state.pieceAt(5, 0))
    }
}

class PopOutTest {

    @Test
    fun `pop out is rejected without the rule`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1......",
        )
        val r = GameEngine.apply(s, Move.Pop(0))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals("no_pop_rule", (r.events[0] as GameEvent.MoveRejected).reason)
        assertEquals(s, r.state)
    }

    @Test
    fun `pop removes the bottom own normal piece and the column falls`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "..1....",
            "..1....",
            popOut = true,
        )
        val r = GameEngine.apply(s, Move.Pop(2))
        assertEquals(Player.P2, r.state.toMove)
        // The remaining piece fell from row 4 to row 5.
        assertEquals(Player.P1, r.state.pieceAt(5, 2)!!.owner)
        assertNull(r.state.pieceAt(4, 2))
        val popped = r.events.filterIsInstance<GameEvent.Popped>()
        assertEquals(1, popped.size)
        assertEquals(2, popped[0].col)
    }

    @Test
    fun `pop of the opponent piece is rejected`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..2....",
            popOut = true,
        )
        val r = GameEngine.apply(s, Move.Pop(2))
        assertEquals("not_your_piece", (r.events[0] as GameEvent.MoveRejected).reason)
        assertEquals(s, r.state)
    }

    @Test
    fun `pop of a wild or lock piece is rejected`() {
        val wild = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..A....",
            popOut = true,
        )
        assertEquals(
            "not_normal_piece",
            (GameEngine.apply(wild, Move.Pop(2)).events[0] as GameEvent.MoveRejected).reason,
        )
        val lock = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..L....",
            popOut = true,
        )
        assertEquals(
            "not_normal_piece",
            (GameEngine.apply(lock, Move.Pop(2)).events[0] as GameEvent.MoveRejected).reason,
        )
    }

    @Test
    fun `pop of an empty column is rejected`() {
        val s = Boards.blank(popOut = true)
        val r = GameEngine.apply(s, Move.Pop(3))
        assertEquals("nothing_to_pop", (r.events[0] as GameEvent.MoveRejected).reason)
    }

    @Test
    fun `simultaneous lines give the win to the mover`() {
        // P1 pops col 0: their piece lands on row 2 completing a horizontal four, and the shifted
        // P2 pieces land on row 5 completing another horizontal four for P2.
        val s = Boards.state(
            ".......",
            "1......",
            "2111...",
            "2###...",
            "2......",
            "1222...",
            popOut = true,
        )
        val r = GameEngine.apply(s, Move.Pop(0))
        val outcome = r.outcome
        assertTrue(outcome is pt.tiago.neonline.core.model.MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as pt.tiago.neonline.core.model.MatchOutcome.Win).player)
        val lines = r.events.filterIsInstance<GameEvent.LineFormed>()
        assertEquals(2, lines.size)
    }

    @Test
    fun `full board continues when the next player can pop`() {
        // 4x4 pattern with no line anywhere; P1 fills the last cell. The bottom row ends with a
        // P2 piece, so P2 can pop and the match continues.
        val almost = Boards.state(
            "1122",
            "1221",
            "2112",
            "221.",
            popOut = true,
        )
        val r = GameEngine.apply(almost, Move.Drop(3))
        assertEquals(Player.P2, r.state.toMove)
        assertNull(r.outcome)
        assertTrue(GameEngine.validMoves(r.state).any { it is Move.Pop })
    }

    @Test
    fun `three repetitions of the same position draw`() {
        // Cycle: P1 drops col 0, P2 drops col 0, P1 pops col 0, P2 pops col 0 -> back to start.
        var s = Boards.blank(popOut = true)
        val cycle = listOf(Move.Drop(0), Move.Drop(0), Move.Pop(0), Move.Pop(0))
        s = cycle.fold(s) { acc, move -> GameEngine.apply(acc, move).state }
        // First cycle done: the start position has appeared twice, no draw yet.
        assertEquals(pt.tiago.neonline.core.model.Status.InProgress, s.status)
        s = cycle.fold(s) { acc, move -> GameEngine.apply(acc, move).state }
        assertTrue(s.status is pt.tiago.neonline.core.model.Status.Draw)
        assertEquals("repetition", (s.status as pt.tiago.neonline.core.model.Status.Draw).reason)
    }
}
