package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player

class RotationTest {

    @Test
    fun `rotation swaps the board dimensions`() {
        val s = Boards.blank(rotateEvery = 1)
        val r = GameEngine.apply(s, Move.Drop(3))
        assertEquals(6, r.state.width)
        assertEquals(7, r.state.height)
        assertEquals(1, r.state.orientation)
    }

    @Test
    fun `four rotations return to the initial orientation and dimensions`() {
        var s = Boards.blank(rotateEvery = 1)
        repeat(4) { s = GameEngine.apply(s, Move.Drop(it)).state }
        assertEquals(0, s.orientation)
        assertEquals(7, s.width)
        assertEquals(6, s.height)
    }

    @Test
    fun `pieces fall in the new direction after rotating`() {
        // Column 3 is filled from row 1 to 5 with alternating owners, so no line forms anywhere.
        // After the rotation everything sits on the new floor.
        val s = Boards.state(
            ".......",
            "...2...",
            "...1...",
            "...2...",
            "...1...",
            "...2...",
            rotateEvery = 1,
        )
        val r = GameEngine.apply(s, Move.Drop(0))
        val fell = r.events.filterIsInstance<GameEvent.Fell>()
        assertEquals(5, fell.size)
        val rotated = r.events.filterIsInstance<GameEvent.Rotated>()
        assertEquals(1, rotated.size)
        assertNull(r.outcome)
    }

    @Test
    fun `rotation forms a line that did not exist before`() {
        // P1 owns (5,0),(5,1),(5,3),(5,4) with a gap at (5,2): no horizontal line yet. P1 drops
        // col 6, the board turns and the row packs into a vertical line in the new board.
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "11.11..",
            rotateEvery = 1,
        )
        val r = GameEngine.apply(s, Move.Drop(6))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `rotation counts total moves, not moves per player`() {
        // rotateEvery = 3: the third move overall (P2's second) rotates the board.
        var s = Boards.blank(rotateEvery = 3)
        s = GameEngine.apply(s, Move.Drop(0)).state
        assertEquals(7, s.width) // no rotation yet
        s = GameEngine.apply(s, Move.Drop(1)).state
        assertEquals(7, s.width)
        s = GameEngine.apply(s, Move.Drop(2)).state
        assertEquals(6, s.width) // rotated on the third move
    }

    @Test
    fun `no rotation happens without the rule`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(0))
        assertEquals(7, r.state.width)
        assertEquals(0, r.state.orientation)
        assertTrue(r.events.none { it is GameEvent.Rotated })
    }

    @Test
    fun `a winning drop ends the match after the rotation`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            rotateEvery = 1,
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        assertTrue(r.outcome is MatchOutcome.Win)
        assertEquals(6, r.state.width)
    }
}
