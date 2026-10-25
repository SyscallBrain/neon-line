package pt.tiago.neonline.core.solver

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.rules.Boards

class PerfectSolverCancellationTest {
    private fun immediate() = Boards.state(".......", ".......", ".......", ".......", ".......", "111....")
    private fun fork() = Boards.state(".......", ".......", ".......", ".......", ".......", "..11...")
    private fun late() = Boards.state("1.2221.", "2.11122", "1.22211", "2221121", "2112121", "1211212")
    private fun cancelAt(solver: PerfectSolver, state: GameState, at: Int) {
        var checks = 0
        val exception = CancellationException("stop at $at")
        val thrown = assertThrows(CancellationException::class.java) {
            solver.solve(state) { if (++checks == at) throw exception }
        }
        assertSame(exception, thrown)
        assertEquals(at, checks)
    }

    @Test fun `entry cancellation propagates even for unsupported input`() {
        cancelAt(PerfectSolver(), immediate(), 1)
        cancelAt(PerfectSolver(), immediate().copy(moveClockSec = 10), 1)
    }

    @Test fun `short root proofs and bestMove obey cancellation`() {
        cancelAt(PerfectSolver(), fork(), 3)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PerfectSolver().bestMove(immediate()) { if (++checks == 2) throw CancellationException() }
        }
        assertEquals(2, checks)
    }

    @Test fun `deep search cancellation never poisons the reused solver`() {
        val solver = PerfectSolver()
        // Root short-proof visits are fewer than100 on7columns; this cancels recursive search.
        cancelAt(solver, Boards.blank(), 100)
        assertEquals(PerfectSolver().solve(late()), solver.solve(late()))
        assertEquals(PerfectSolver().bestMove(immediate()), solver.bestMove(immediate()))
    }

    @Test fun `cancellation during final move enumeration returns no partial answer`() {
        val complete = PerfectSolver().solve(late())!!
        assertTrue(complete.nodesExplored > 5)
        // Entry adds one callback; this boundary is one node before completion of enumeration.
        cancelAt(PerfectSolver(), late(), complete.nodesExplored.toInt())
    }

    @Test fun `noop callback preserves exact results and cancellation wins at budget boundary`() {
        for (state in listOf(immediate(), fork(), late())) {
            var checks = 0
            val old = PerfectSolver().solve(state)!!
            val checked = PerfectSolver().solve(state) { checks++ }
            assertEquals(old, checked)
            assertEquals(old.nodesExplored + 1, checks.toLong())
        }
        cancelAt(PerfectSolver(1), Boards.blank(), 3)
    }
}
