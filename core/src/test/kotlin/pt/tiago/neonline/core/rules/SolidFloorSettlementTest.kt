package pt.tiago.neonline.core.rules

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher

class SolidFloorSettlementTest {
    private fun initial(version: Int, width: Int = 7, height: Int = 6, steps: Boolean = false): GameState = GameState.initial(
        "floor", Difficulty.EASY, version, "0123456789abcdef", width, height, 4,
        List(width * height) { index ->
            val col = index % width
            val floor = if (steps) kotlin.math.abs(col - width / 2) / 2 else if (col == 1 || col == width - 2) 2 else 0
            index / width < height - floor
        }, List(width * height) { null }, inventory = Player.entries.associateWith { mapOf(PieceKind.BOMB to 1) })
    private fun reproduce(start: GameState): MoveResult {
        var state = start
        for (move in listOf(Move.Drop(1), Move.Drop(1))) state = GameEngine.apply(state, move).state
        return GameEngine.apply(state, Move.Drop(1, PieceKind.BOMB))
    }
    @Test fun `engine three bomb leaves surviving piece above solid floor for either shape and geometry`() {
        for ((width,height) in listOf(7 to 6, 9 to 7)) for (steps in listOf(false,true)) {
            val start = initial(3, width, height, steps)
            val result = reproduce(start)
            assertTrue(result.events.none { it is GameEvent.MoveRejected })
            assertEquals(1, result.state.pieces.filterNotNull().size)
            val floor = GameEngine.landingRow(start.pieces,width,height,start.playable,1)!!
            assertEquals(Player.P1, result.state.pieceAt(floor,1)?.owner)
            assertTrue(result.state.pieces.indices.all { result.state.pieces[it] == null || result.state.playable[it] })
            assertTrue(result.events.filterIsInstance<GameEvent.Shifted>().all { shift -> shift.toRows.all { result.state.playable[result.state.index(it,shift.col)] } })
        }
    }
    @Test fun `engine two keeps frozen settlement and hashes instead of silently rewriting journals`() {
        val result = reproduce(initial(2))
        assertEquals(Player.P1, result.state.pieceAt(5,1)?.owner)
        assertNull(result.state.pieceAt(3,1))
        assertEquals("25c3f28927d518faf0661bb8b03a8a76d5c95ed7a3f771a69b02a245a3749ac8", StateHasher.hash(result.state))
        assertEquals(listOf(3), result.events.filterIsInstance<GameEvent.Shifted>().single().fromRows)
        assertEquals(listOf(5), result.events.filterIsInstance<GameEvent.Shifted>().single().toRows)
    }
    @Test fun `engine three preserves survivor order and emits only playable shift destinations`() {
        val start = initial(3)
        val pieces = start.pieces.toMutableList()
        // Bomb at row3 col0 clears bottom pillar piece at row3 col1; higher survivors settle there.
        pieces[start.index(5,0)] = Piece(Player.P1,PieceKind.NORMAL,0)
        pieces[start.index(4,0)] = Piece(Player.P2,PieceKind.NORMAL,1)
        pieces[start.index(3,1)] = Piece(Player.P1,PieceKind.NORMAL,2)
        pieces[start.index(2,1)] = Piece(Player.P2,PieceKind.NORMAL,3)
        pieces[start.index(1,1)] = Piece(Player.P1,PieceKind.WILD,4)
        pieces[start.index(0,1)] = Piece(Player.P2,PieceKind.LOCK,5)
        val result = GameEngine.apply(start.copy(pieces=pieces),Move.Drop(0,PieceKind.BOMB))
        assertEquals(PieceKind.WILD,result.state.pieceAt(3,1)?.kind)
        assertEquals(PieceKind.LOCK,result.state.pieceAt(2,1)?.kind)
        assertTrue(result.state.pieces.indices.all { result.state.pieces[it] == null || result.state.playable[it] })
        assertEquals(listOf(3,2),result.events.filterIsInstance<GameEvent.Shifted>().single { it.col==1 }.toRows)
    }
    @Test fun `rectangle specials pop and rotation are unchanged when opting into engine three`() {
        for ((pop,rotation) in listOf(true to null,false to 6)) {
            var old = initial(2).copy(playable=List(42){true},popOut=pop,rotateEvery=rotation)
            var modern = old.copy(engineVersion=3)
            val moves = if(pop) listOf(Move.Drop(0),Move.Drop(1),Move.Pop(0),Move.Drop(2,PieceKind.BOMB))
                else (0..5).map { Move.Drop(it) }
            for(move in moves) {
                val before = GameEngine.apply(old,move); val after=GameEngine.apply(modern,move)
                assertEquals(before.state,after.state.copy(engineVersion=2)); assertEquals(before.events,after.events)
                old=before.state; modern=after.state
            }
        }
    }
}
