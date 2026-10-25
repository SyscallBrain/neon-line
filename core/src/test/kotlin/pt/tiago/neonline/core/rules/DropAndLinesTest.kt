package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

class DropAndLinesTest {

    @Test
    fun `drop lands on the bottom of an empty column`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(3))
        val piece = r.state.pieceAt(5, 3)
        assertNotNull(piece)
        assertEquals(Player.P1, piece!!.owner)
        assertEquals(Player.P2, r.state.toMove)
        assertEquals(1, r.state.ply)
        assertNull(r.outcome)
    }

    @Test
    fun `drop stacks on top of the previous piece`() {
        var s = Boards.blank()
        s = GameEngine.apply(s, Move.Drop(3)).state
        s = GameEngine.apply(s, Move.Drop(3)).state
        assertEquals(Player.P1, s.pieceAt(5, 3)!!.owner)
        assertEquals(Player.P2, s.pieceAt(4, 3)!!.owner)
    }

    @Test
    fun `dropping the fourth horizontal piece wins`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(Player.P1, outcome.player)
        assertEquals(1, outcome.lines.size)
        assertEquals(listOf(35, 36, 37, 38), outcome.lines[0].cells)
        val formed = r.events.filterIsInstance<GameEvent.LineFormed>()
        assertEquals(1, formed.size)
        assertEquals(Player.P1, formed[0].line.player)
    }

    @Test
    fun `vertical line of four wins`() {
        val grounded = Boards.state(
            ".......",
            ".......",
            "..1....",
            "..1....",
            "..1....",
            ".......",
        )
        val r = GameEngine.apply(grounded, Move.Drop(2))
        assertTrue(r.outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (r.outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `diagonal line of four wins`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            "..122..",
            ".1222..",
            "11222..",
        )
        // P1 owns (5,0),(4,1),(3,2); the drop lands on (2,3) because column 3 is filled below it.
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
        assertEquals(listOf(17, 23, 29, 35), outcome.lines[0].cells)
        assertNoFloating(s)
    }

    @Test
    fun `anti-diagonal line of four wins`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".....12",
            "....122",
            "...1222",
        )
        // P1 owns (5,3),(4,4),(3,5); the drop lands on (2,6) because column 6 is filled below it.
        val r = GameEngine.apply(s, Move.Drop(6))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
        assertNoFloating(s)
    }

    @Test
    fun `four in a row wins with connect four on nine columns`() {
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            "1111.....",
        )
        val r = GameEngine.apply(s, Move.Drop(4))
        assertTrue(r.outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (r.outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `connect five needs five in a row`() {
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            "1111.....",
            connect = 5,
        )
        val noWin = GameEngine.apply(s, Move.Drop(0))
        assertNull(noWin.outcome)
        val win = GameEngine.apply(s, Move.Drop(4))
        val outcome = win.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
        assertEquals(1, outcome.lines.size)
        assertEquals(5, outcome.lines[0].cells.size)
    }

    @Test
    fun `wild piece only helps contiguous lines`() {
        val s = Boards.state(
            "A......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".1.....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        assertNull(r.outcome)
    }

    @Test
    fun `wild piece closes a line for its owner`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "11A....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `a line of wilds belongs to both players and the mover wins`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "BBB....",
            inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1)),
        )
        // P1 drops a wild at col 3: the four contiguous wild cells are a line for both players,
        // so the mover (P1) wins.
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.WILD))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `full board without a line is a draw`() {
        val almost = Boards.state(
            "1122",
            "1221",
            "2112",
            "221.",
        )
        val r = GameEngine.apply(almost, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Draw)
        assertEquals("board_full", (outcome as MatchOutcome.Draw).reason)
    }

    @Test
    fun `drop into a full column is rejected`() {
        val s = Boards.state(
            "1......",
            "2......",
            "1......",
            "2......",
            "1......",
            "2......",
        )
        val r = GameEngine.apply(s, Move.Drop(0))
        assertEquals(1, r.events.size)
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
        assertNull(r.outcome)
    }

    @Test
    fun `drop out of range is rejected`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(7))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
    }

    @Test
    fun `special piece requires inventory`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.WILD))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
    }

    @Test
    fun `events name the landing cell and the turn change`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(2))
        val dropped = r.events.filterIsInstance<GameEvent.Dropped>()
        assertEquals(1, dropped.size)
        assertEquals(5, dropped[0].row)
        assertEquals(2, dropped[0].col)
        val turn = r.events.filterIsInstance<GameEvent.TurnChanged>()
        assertEquals(Player.P2, turn.last().player)
    }

    @Test
    fun `moves after the match ended are rejected`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val won = GameEngine.apply(s, Move.Drop(3)).state
        val r = GameEngine.apply(won, Move.Drop(0))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(won, r.state)
    }

    @Test
    fun `pre-placed pieces must respect gravity`() {
        val floating = Boards.state(
            "..1....",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
        )
        // The engine does not silently fix content: the helper proves what a valid board looks like.
        assertNoFloating(
            Boards.state(
                ".......",
                ".......",
                ".......",
                ".......",
                ".......",
                "..1....",
            ),
        )
        var raised = false
        try {
            assertNoFloating(floating)
        } catch (e: AssertionError) {
            raised = true
        }
        assertTrue(raised)
    }
}
