package pt.tiago.neonline.core.solver

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*

class BoundedWinSolverTest {
    private fun board(columns: List<Int>) = GameState.initial(
        "proof", Difficulty.HARD, 1, "0123456789abcdef", 7, 6, 4, List(42) { true },
        MutableList<Piece?>(42) { null }.also { pieces -> columns.forEach { pieces[35 + it] = Piece(Player.P1, PieceKind.NORMAL, 0) } },
    )
    @Test fun `three pieces have an immediate winning move`() {
        assertTrue(BoundedWinSolver().canWin(board(listOf(0,1,2)), 1)!!)
        assertEquals(listOf(Move.Drop(3)), BoundedWinSolver().winningMoves(board(listOf(0,1,2)), 1))
    }
    @Test fun `a fork needs two own moves against every defence`() {
        val state = board(listOf(2,3))
        assertFalse(BoundedWinSolver().canWin(state, 1)!!)
        assertTrue(BoundedWinSolver().canWin(state, 2)!!)
        assertEquals(setOf(Move.Drop(1), Move.Drop(4)), BoundedWinSolver().winningMoves(state, 2)!!.toSet())
    }
    @Test fun `no move is offered after the own move budget is exhausted`() {
        assertTrue(BoundedWinSolver().winningMoves(board(listOf(0,1,2)), 0)!!.isEmpty())
        assertFalse(BoundedWinSolver().canWin(board(emptyList()), 0)!!)
    }
    @Test fun `budget exhaustion is unknown rather than a false proof`() {
        assertNull(BoundedWinSolver(1).canWin(board(emptyList()), 3))
        assertNull(BoundedWinSolver(1).winningMoves(board(emptyList()), 3))
    }
}
