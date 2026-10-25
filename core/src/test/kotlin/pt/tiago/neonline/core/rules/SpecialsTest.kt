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
import pt.tiago.neonline.core.model.Status

class SpecialsTest {

    private fun inventory(vararg kinds: Pair<PieceKind, Int>) = mapOf(
        Player.P1 to kinds.toMap(),
        Player.P2 to mapOf(PieceKind.BOMB to 0, PieceKind.WILD to 0, PieceKind.LOCK to 0),
    )

    @Test
    fun `bomb clears itself and its eight neighbours and leaves no piece`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            "..111..",
            "..121..",
            "..1.2..",
            inventory = inventory(PieceKind.BOMB to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.BOMB))
        // The bomb lands at (5,3) and clears the cells around it that hold pieces.
        val exploded = r.events.filterIsInstance<GameEvent.BombExploded>()
        assertEquals(1, exploded.size)
        assertEquals(38, exploded[0].center) // index(5,3)
        assertEquals(6, exploded[0].cleared.size)
        // Whatever was above the hole falls into place.
        assertNotNull(r.state.pieceAt(5, 2))
        assertNotNull(r.state.pieceAt(5, 3))
        assertEquals(0, r.state.inventory[Player.P1]!!.getValue(PieceKind.BOMB))
    }

    @Test
    fun `bomb at the edge clears fewer cells`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "11.....",
            ".2.....",
            inventory = inventory(PieceKind.BOMB to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(0, PieceKind.BOMB))
        val exploded = r.events.filterIsInstance<GameEvent.BombExploded>()
        // Corner drop: itself plus (4,0), (4,1) and (5,1).
        assertEquals(4, exploded[0].cleared.size)
    }

    @Test
    fun `bomb does not clear locked pieces`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "..#....",
            "..L....",
            inventory = inventory(PieceKind.BOMB to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.BOMB))
        val exploded = r.events.filterIsInstance<GameEvent.BombExploded>()
        // The bomb lands at (5,3): only itself is cleared, the lock at (5,2) survives.
        assertEquals(listOf(38), exploded[0].cleared)
        assertEquals(PieceKind.LOCK, r.state.pieceAt(5, 2)!!.kind)
    }

    @Test
    fun `bomb without inventory is rejected`() {
        val s = Boards.blank()
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.BOMB))
        assertTrue(r.events[0] is GameEvent.MoveRejected)
        assertEquals(s, r.state)
    }

    @Test
    fun `dropped wild is placed and counts for both players later`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1......",
            inventory = inventory(PieceKind.WILD to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(1, PieceKind.WILD))
        val wild = r.state.pieceAt(5, 1)!!
        assertEquals(PieceKind.WILD, wild.kind)
        assertEquals(Player.P1, wild.owner)
        assertEquals(0, r.state.inventory[Player.P1]!!.getValue(PieceKind.WILD))
    }

    @Test
    fun `lock closes the column for the opponent next move`() {
        val s = Boards.blank(inventory = inventory(PieceKind.LOCK to 1))
        val afterLock = GameEngine.apply(s, Move.Drop(4, PieceKind.LOCK))
        assertEquals(PieceKind.LOCK, afterLock.state.pieceAt(5, 4)!!.kind)
        val locked = afterLock.events.filterIsInstance<GameEvent.ColumnLocked>()
        assertEquals(1, locked.size)
        assertEquals(4, locked[0].col)
        assertEquals(Player.P2, locked[0].forPlayer)

        val blocked = GameEngine.apply(afterLock.state, Move.Drop(4))
        assertTrue(blocked.events[0] is GameEvent.MoveRejected)
        assertEquals("column_locked", (blocked.events[0] as GameEvent.MoveRejected).reason)
        assertEquals(afterLock.state, blocked.state)

        // P2 plays elsewhere: the lock expires after their move.
        val elsewhere = GameEngine.apply(afterLock.state, Move.Drop(0))
        assertNull(elsewhere.state.lockedCol)
        val unlocked = elsewhere.events.filterIsInstance<GameEvent.ColumnUnlocked>()
        assertEquals(1, unlocked.size)
        assertEquals(4, unlocked[0].col)
        // And P1 may use the column again: the new piece stacks above the lock.
        val again = GameEngine.apply(elsewhere.state, Move.Drop(4))
        assertNotNull(again.state.pieceAt(4, 4))
    }

    @Test
    fun `lock does not close the only free column`() {
        // Only column 4 is playable; a lock there must not shut the opponent out.
        val s = Boards.state(
            "####.###",
            "####.###",
            "####.###",
            "####.###",
            "####.###",
            "####.###",
            inventory = inventory(PieceKind.LOCK to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(4, PieceKind.LOCK))
        assertTrue(r.events.none { it is GameEvent.ColumnLocked })
        assertNull(r.state.lockedCol)
        val p2 = GameEngine.apply(r.state, Move.Drop(4))
        assertNotNull(p2.state.pieceAt(4, 4))
    }

    @Test
    fun `lock counts as the owner piece for lines`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "LLL....",
            inventory = inventory(PieceKind.LOCK to 1),
        )
        val r = GameEngine.apply(s, Move.Drop(3, PieceKind.LOCK))
        assertTrue(r.outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (r.outcome as MatchOutcome.Win).player)
        assertTrue(r.state.status is Status.Won)
    }

    @Test
    fun `specials combine with rotation`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            inventory = inventory(PieceKind.BOMB to 1),
            rotateEvery = 1,
        )
        val r = GameEngine.apply(s, Move.Drop(2, PieceKind.BOMB))
        val exploded = r.events.filterIsInstance<GameEvent.BombExploded>()
        assertEquals(3, exploded[0].cleared.size)
        assertEquals(1, r.state.orientation)
        assertNull(r.outcome)
    }
}
