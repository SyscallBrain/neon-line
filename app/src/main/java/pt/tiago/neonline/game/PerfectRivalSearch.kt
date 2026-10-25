package pt.tiago.neonline.game

import pt.tiago.neonline.core.ai.AiChoice
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.core.solver.SolverBudgetExceeded

data class PerfectRivalResult(
    val choice: AiChoice?,
    val nodesExplored: Int,
    val exact: Boolean,
    val usedBook: Boolean,
)

/** Uses an optional exact book, then the complete solver, then the existing deterministic deepening AI. */
object PerfectRivalSearch {
    @JvmOverloads
    fun search(
        state: GameState,
        fallbackProfile: AiProfile,
        book: PerfectOpeningBook? = null,
        maxExactNodes: Long = Long.MAX_VALUE,
        checkCancelled: () -> Unit = {},
        onExactNodes: ((Long) -> Unit)? = null,
    ): PerfectRivalResult {
        require(maxExactNodes > 0L)
        checkCancelled()
        if (state.status != pt.tiago.neonline.core.model.Status.InProgress) return PerfectRivalResult(null, 0, false, false)

        val bookMove = book?.lookup(state)
        if (bookMove != null) {
            checkCancelled()
            return PerfectRivalResult(choice(state, bookMove.move), 0, exact = true, usedBook = true)
        }

        var exactNodes = 0L
        val solver = PerfectSolver(
            nodeLimit = maxExactNodes,
            nodeProgressInterval = if (onExactNodes == null) 1L shl 20 else 1L,
        )
        val solved = try {
            if (onExactNodes == null) solver.solve(state, checkCancelled)
            else solver.solveWithProgress(state, checkCancelled, onExactNodes)
        } catch (e: SolverBudgetExceeded) {
            exactNodes = e.nodesExplored
            null
        }
        val exactMove = solved?.bestMoves?.firstOrNull() as? Move.Drop
        if (exactMove != null) {
            checkCancelled()
            return PerfectRivalResult(
                choice(state, exactMove),
                solved.nodesExplored.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                exact = true,
                usedBook = false,
            )
        }

        checkCancelled()
        val fallback = AiSearch(fallbackProfile)
        val result = fallback.chooseMove(state, checkCancelled)
        checkCancelled()
        return PerfectRivalResult(
            result,
            (exactNodes + fallback.nodesExplored).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            exact = false,
            usedBook = false,
        )
    }

    private fun choice(state: GameState, move: Move.Drop): AiChoice {
        val rng = state.aiRng()
        rng.nextInt(1000)
        return AiChoice(move, rng.stateHex())
    }
}
