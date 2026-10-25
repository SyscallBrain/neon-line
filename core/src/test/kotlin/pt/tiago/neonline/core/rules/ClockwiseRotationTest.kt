package pt.tiago.neonline.core.rules

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher

class ClockwiseRotationTest {
    private fun blank(version: Int, width: Int = 7, height: Int = 6) = GameState.initial(
        "clockwise", Difficulty.EASY, version, "0123456789abcdef", width, height, 4,
        List(width * height) { true }, List(width * height) { null }, rotateEvery = 6, popOut = true)

    @Test fun `engine four rotates rectangular boards clockwise then settles onto the new floor`() {
        for ((width, height) in listOf(7 to 6, 6 to 7)) {
            val start = blank(4, width, height).copy(ply = 5)
            val hash = StateHasher.hash(start)
            val result = GameEngine.apply(start, Move.Drop(1))
            assertEquals(height, result.state.width)
            assertEquals(width, result.state.height)
            // Bottom row rotates into column zero; gravity takes the piece to the new floor.
            assertEquals(Player.P1, result.state.pieceAt(width - 1, 0)?.owner)
            assertEquals(1, result.state.pieces.count { it != null })
            assertEquals(1, result.state.orientation)
            assertEquals(hash, StateHasher.hash(start))
            assertTrue(result.events.indexOfFirst { it is GameEvent.Rotated } < result.events.indexOfFirst { it is GameEvent.Fell })
        }
    }

    @Test fun `clockwise order survives gravity with several distinct pieces`() {
        var state = blank(4)
        for (col in listOf(0, 1, 2, 3, 0)) state = GameEngine.apply(state, Move.Drop(col)).state
        val after = GameEngine.apply(state, Move.Drop(4)).state
        assertEquals(Player.P1, after.pieceAt(6, 1)?.owner)
        assertEquals(listOf(Player.P1, Player.P2, Player.P1, Player.P2, Player.P2),
            (2..6).map { after.pieceAt(it, 0)?.owner })
        assertTrue((2..6).all { after.pieceAt(it, 5) == null })
    }

    @Test fun `legacy engines retain opposite geometry and event order`() {
        for (version in 1..3) {
            val before = blank(version).copy(ply = 5)
            val result = GameEngine.apply(before, Move.Drop(1))
            assertEquals(Player.P1, result.state.pieceAt(6, 5)?.owner)
            assertTrue(result.events.indexOfFirst { it is GameEvent.Rotated } > result.events.indexOfFirst { it is GameEvent.Fell })
        }
    }

    @Test fun `legacy journals keep their frozen state hashes`() {
        val hashes = (1..3).map { version ->
            var state = blank(version)
            for (col in listOf(0, 1, 2, 3, 0, 4)) state = GameEngine.apply(state, Move.Drop(col)).state
            StateHasher.hash(state)
        }
        assertEquals(listOf(
            "d8ec9c57a89a5e4affda6b23846f18ccc79d7155f5de70070a6dc95855ce8d37",
            "6a5a0af72bcd49fb4f3102d37a3eddc3cd503575d807b80d1058da8f6264857d",
            "416461a9b4768f9c0edf6929f02bfde3cd9896137d6007f01a639f5a0ab6f6c8"), hashes)
    }

    @Test fun `four clockwise turns preserve piece identities and restore orientation deterministically`() {
        fun play(): GameState {
            var state = blank(4).copy(rotateEvery = 1)
            repeat(4) { col ->
                val result = GameEngine.apply(state, Move.Drop(col))
                assertTrue(result.events.any { it is GameEvent.Rotated })
                state = result.state
                for (c in 0 until state.width) {
                    var occupied = false
                    for (row in 0 until state.height) {
                        if (state.pieceAt(row, c) != null) occupied = true
                        else assertFalse("No empty cell below a settled piece", occupied)
                    }
                }
            }
            return state
        }
        val after = play()
        assertEquals(0, after.orientation)
        assertEquals(7, after.width); assertEquals(6, after.height)
        assertEquals(4, after.pieces.count { it != null })
        assertEquals(listOf(0, 1, 2, 3), after.pieces.filterNotNull().map { it.placedAtPly }.sorted())
        assertEquals(after, play())
    }
}
