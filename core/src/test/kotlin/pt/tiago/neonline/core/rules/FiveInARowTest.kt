package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player

class FiveInARowTest {

    @Test
    fun `horizontal five wins`() {
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
        val r = GameEngine.apply(s, Move.Drop(4))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(5, outcome.lines[0].cells.size)
        assertEquals(listOf(54, 55, 56, 57, 58), outcome.lines[0].cells)
    }

    @Test
    fun `vertical five wins`() {
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            "..1......",
            "..1......",
            "..1......",
            "..1......",
            connect = 5,
        )
        val r = GameEngine.apply(s, Move.Drop(2))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(5, outcome.lines[0].cells.size)
    }

    @Test
    fun `diagonal five wins`() {
        // Diagonal from the bottom-left: (6,0),(5,1),(4,2),(3,3) plus the drop at (2,4).
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            "...12....",
            "..122....",
            ".1222....",
            "11222....",
            connect = 5,
        )
        assertNoFloating(s)
        val r = GameEngine.apply(s, Move.Drop(4))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
        assertEquals(5, outcome.lines[0].cells.size)
    }

    @Test
    fun `anti-diagonal five wins`() {
        // Anti-diagonal to the right: (6,4),(5,3),(4,2),(3,1) plus the drop at (2,0).
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            "21.......",
            "221......",
            "2221.....",
            "22221....",
            connect = 5,
        )
        assertNoFloating(s)
        val r = GameEngine.apply(s, Move.Drop(0))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
        assertEquals(5, outcome.lines[0].cells.size)
    }

    @Test
    fun `four in a row does not win with connect five`() {
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            "111......",
            connect = 5,
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        assertNull(r.outcome)
    }

    @Test
    fun `five in a row of wilds counts for the mover`() {
        val s = Boards.state(
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            "AAAA.....",
            connect = 5,
        )
        val r = GameEngine.apply(s, Move.Drop(4))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }
}
