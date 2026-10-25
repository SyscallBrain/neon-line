package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

class EngineSurfaceTest {

    @Test
    fun `a blank board offers one normal move per column`() {
        val moves = GameEngine.validMoves(Boards.blank())
        assertEquals(7, moves.size)
        assertTrue(moves.all { it is Move.Drop && it.kind == PieceKind.NORMAL })
    }

    @Test
    fun `pops are offered only to the owner of the bottom piece`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..2.1..",
            popOut = true,
        )
        assertEquals(Player.P1, s.toMove)
        val p1 = GameEngine.validMoves(s)
        assertTrue(p1.any { it is Move.Pop && it.col == 4 })
        assertTrue(p1.none { it is Move.Pop && it.col == 2 })
        val p2 = GameEngine.validMoves(s.copy(toMove = Player.P2))
        assertTrue(p2.any { it is Move.Pop && it.col == 2 })
        assertTrue(p2.none { it is Move.Pop && it.col == 4 })
    }

    @Test
    fun `hasAnyMove is false on a full board`() {
        val s = Boards.state(
            "1122",
            "1221",
            "2112",
            "2211",
        )
        assertFalse(GameEngine.hasAnyMove(s))
        assertTrue(GameEngine.validMoves(s).isEmpty())
    }

    @Test
    fun `the second player can start a match`() {
        val s = Boards.blank(firstToMove = Player.P2)
        val r = GameEngine.apply(s, Move.Drop(3))
        assertEquals(Player.P2, r.state.pieceAt(5, 3)!!.owner)
        assertEquals(Player.P1, r.state.toMove)
    }

    @Test
    fun `the engine version travels with the state`() {
        val s = Boards.blank()
        assertEquals(2, s.engineVersion)
        assertEquals(GameEngine.ENGINE_VERSION, s.engineVersion)
        val after = GameEngine.apply(s, Move.Drop(0)).state
        assertEquals(2, after.engineVersion)
        assertTrue(GameEngine.ENGINE_VERSION >= 1)
    }

    @Test
    fun `every drop in a column lands on the previous piece`() {
        var s = Boards.blank()
        val rows = mutableListOf<Int>()
        repeat(6) {
            val r = GameEngine.apply(s, Move.Drop(3))
            rows += r.events.filterIsInstance<pt.tiago.neonline.core.model.GameEvent.Dropped>().single().row
            s = r.state
        }
        assertEquals(listOf(5, 4, 3, 2, 1, 0), rows)
        // The column is full: a seventh drop is rejected.
        val seventh = GameEngine.apply(s, Move.Drop(3))
        assertTrue(seventh.events.any { it is pt.tiago.neonline.core.model.GameEvent.MoveRejected })
    }
}
