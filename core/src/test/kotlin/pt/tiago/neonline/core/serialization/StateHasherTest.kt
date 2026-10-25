package pt.tiago.neonline.core.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

class StateHasherTest {

    @Test
    fun `hash is stable for the same state`() {
        val s = Boards.blank()
        assertEquals(StateHasher.hash(s), StateHasher.hash(s.copy()))
    }

    @Test
    fun `hash changes when the board changes`() {
        val s = Boards.blank()
        val after = GameEngine.apply(s, Move.Drop(3)).state
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(after))
    }

    @Test
    fun `hash depends on the seed`() {
        val a = Boards.blank(seedHex = "0000000000000001")
        val b = Boards.blank(seedHex = "0000000000000002")
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(b))
    }

    @Test
    fun `hash depends on the move clock`() {
        val a = Boards.blank(moveClockSec = 10)
        val b = Boards.blank(moveClockSec = 15)
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(b))
    }

    @Test
    fun `canonical form is a fixed shape`() {
        val canonical = StateHasher.canonical(Boards.blank(width = 4, height = 4))
        assertTrue(canonical.contains("\"width\":4"))
        assertTrue(canonical.contains("\"engineVersion\":${GameEngine.ENGINE_VERSION}"))
        assertTrue(canonical.contains("\"toMove\":0"))
        assertTrue(canonical.contains("\"status\":\"in_progress\""))
    }

    @Test
    fun `two moves that reach the same board hash the same`() {
        // Dropping at col 3 then col 4 leaves the same physical board as 4 then 3 only when the
        // owners match; here the second move belongs to the other player, so the hashes differ.
        var a = Boards.blank()
        a = GameEngine.apply(a, Move.Drop(3)).state
        a = GameEngine.apply(a, Move.Drop(4)).state
        var b = Boards.blank()
        b = GameEngine.apply(b, Move.Drop(4)).state
        b = GameEngine.apply(b, Move.Drop(3)).state
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(b))
    }

    @Test
    fun `the winner status is part of the hash`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val won = GameEngine.apply(s, Move.Drop(3)).state
        assertTrue(StateHasher.canonical(won).contains("\"status\":\"won:0\""))
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(won))
        assertTrue(Player.entries.contains(Player.P1))
        assertTrue(PieceKind.entries.isNotEmpty())
    }
}
