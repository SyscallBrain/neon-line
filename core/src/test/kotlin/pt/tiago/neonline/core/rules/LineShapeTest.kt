package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player

class LineShapeTest {

    @Test
    fun `a gap breaks the line`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "11.11..",
        )
        val r = GameEngine.apply(s, Move.Drop(5))
        assertNull(r.outcome)
    }

    @Test
    fun `an opponent piece breaks the line`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1112...",
        )
        val r = GameEngine.apply(s, Move.Drop(4))
        assertNull(r.outcome)
    }

    @Test
    fun `five in a row wins with connect four`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1111...",
        )
        val r = GameEngine.apply(s, Move.Drop(4))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        // The reported line is the first four found, and a five-long line is a single line object.
        outcome as MatchOutcome.Win
        assertTrue(outcome.lines[0].cells.size >= 4)
    }

    @Test
    fun `a line can form on a masked board`() {
        val s = Boards.state(
            "..#....",
            "..#....",
            "..#....",
            "..#....",
            "..#....",
            "...111.",
        )
        // Column 2 is blocked from floor to ceiling; the three pieces rest on the floor.
        assertNoFloating(s)
        val r = GameEngine.apply(s, Move.Drop(6))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `drops stack upward on a masked board`() {
        val s = Boards.state(
            "..#....",
            "..#....",
            "..#....",
            "..#....",
            "..#....",
            ".......",
        )
        var st = GameEngine.apply(s, Move.Drop(1)).state
        st = GameEngine.apply(st, Move.Drop(1)).state
        st = GameEngine.apply(st, Move.Drop(1)).state
        assertEquals(3, st.pieces.count { it != null })
        assertNotNull(st.pieceAt(5, 1))
        assertNotNull(st.pieceAt(4, 1))
        assertNotNull(st.pieceAt(3, 1))
        assertNull(st.status.let { if (it is pt.tiago.neonline.core.model.Status.Won) it else null })
    }

    @Test
    fun `the second player can also win`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            firstToMove = Player.P1,
        )
        // P1 plays somewhere harmless, P2 completes the four.
        val afterP1 = GameEngine.apply(s, Move.Drop(6)).state
        val r = GameEngine.apply(afterP1, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P2, (outcome as MatchOutcome.Win).player)
    }
}
