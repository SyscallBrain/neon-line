package pt.tiago.neonline.core.rules

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*

class LegalMoveConsistencyTest {
    @Test fun `locked pop is absent from legal moves and rejected by apply`() {
        val state = Boards.state(".......",".......",".......",".......",".......","1......",popOut = true)
            .copy(lockedCol = 0, lockedFor = Player.P1)
        assertFalse(Move.Pop(0) in GameEngine.validMoves(state))
        assertTrue(GameEngine.apply(state,Move.Pop(0)).events.any { it is GameEvent.MoveRejected })
    }
    @Test fun `all announced moves are accepted with locks and pop out`() {
        val state = Boards.state(".......",".......",".......",".......",".......","1212...",popOut = true)
        for (player in Player.entries) for (column in 0..3) {
            val locked = state.copy(toMove=player,lockedCol=column,lockedFor=player)
            GameEngine.validMoves(locked).forEach { move ->
                assertFalse("$player $column $move",GameEngine.apply(locked,move).events.any { it is GameEvent.MoveRejected })
            }
        }
    }
}
