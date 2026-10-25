package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

class DoubleLineTest {

    @Test
    fun `a single drop can complete two lines at once`() {
        // P1 owns a vertical three in column 3 (rows 3..5) and a horizontal three on row 2
        // (columns 0..2). Dropping column 3 lands on row 2 and completes both.
        val s = Boards.state(
            ".......",
            ".......",
            "111....",
            "2221...",
            "2221...",
            "2221...",
        )
        assertNoFloating(s)
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(Player.P1, outcome.player)
        assertEquals(2, outcome.lines.size)
        val formed = r.events.filterIsInstance<GameEvent.LineFormed>()
        assertEquals(2, formed.size)
        val shapes = formed.map { line ->
            val cells = line.line.cells
            val rows = cells.map { it / 7 }.toSet()
            val cols = cells.map { it % 7 }.toSet()
            if (rows.size == 1) "h${cols.min()}" else if (cols.size == 1) "v$cols" else "d"
        }.sorted()
        assertEquals(listOf("h0", "v[3]"), shapes)
    }

    @Test
    fun `wild completes a double line`() {
        // The wild on row 2 is neutral: it counts for P1 on the horizontal line.
        val s = Boards.state(
            ".......",
            ".......",
            "11A....",
            "2221...",
            "2221...",
            "2221...",
        )
        assertNoFloating(s)
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(Player.P1, outcome.player)
        assertEquals(2, outcome.lines.size)
    }

    @Test
    fun `an opponent owned wild still counts for the player`() {
        // The wild on the floor belongs to P2 but completes P1's horizontal four.
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "11B....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `a wild never completes a line by itself for one player when mixed`() {
        // P1, wild(P2), P2 on the floor: no four anywhere for either player.
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1A2....",
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        assertNull(r.outcome)
    }

    @Test
    fun `line at the right edge of the board wins`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "...111.",
        )
        val r = GameEngine.apply(s, Move.Drop(6))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(listOf(38, 39, 40, 41), outcome.lines[0].cells)
    }

    @Test
    fun `diagonal through the center wins`() {
        val grounded = Boards.state(
            ".......",
            ".......",
            ".......",
            "..122..",
            ".1222..",
            "11222..",
        )
        assertNoFloating(grounded)
        val r = GameEngine.apply(grounded, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        outcome as MatchOutcome.Win
        assertEquals(Player.P1, outcome.player)
        // One horizontal-free diagonal: four cells, one per column.
        assertEquals(1, outcome.lines.size)
        val cols = outcome.lines[0].cells.map { it % 7 }
        assertEquals(setOf(0, 1, 2, 3), cols.toSet())
    }

    @Test
    fun `threat events name the landing cell and the player`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        // P2 has no pieces, so only P1 threats exist after an unrelated move.
        val r = GameEngine.apply(s, Move.Drop(6))
        val threats = r.events.filterIsInstance<GameEvent.ThreatCreated>()
        assertTrue(threats.isNotEmpty())
        assertTrue(threats.all { it.player == Player.P1 })
        // Column 3's landing cell (row 5, col 3) is the decisive threat.
        assertTrue(threats.any { it.cell == 38 })
    }

    @Test
    fun `inventory grants special moves`() {
        val s = Boards.blank(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        val moves = GameEngine.validMoves(s)
        assertTrue(moves.contains(Move.Drop(0, PieceKind.BOMB)))
        assertTrue(moves.contains(Move.Drop(0, PieceKind.NORMAL)))
        // P2 has no specials.
        val after = GameEngine.apply(s, Move.Drop(0)).state
        assertTrue(GameEngine.validMoves(after).none { it is Move.Drop && it.kind == PieceKind.BOMB })
    }

    @Test
    fun `fade rule is carried in the state for the ui`() {
        val s = Boards.blank(fadeAfter = 3)
        val r = GameEngine.apply(s, Move.Drop(3))
        assertEquals(3, r.state.fadeAfter)
        assertEquals(0, r.state.pieceAt(5, 3)!!.placedAtPly)
        val second = GameEngine.apply(r.state, Move.Drop(3)).state
        assertEquals(1, second.pieceAt(4, 3)!!.placedAtPly)
    }
}
