package pt.tiago.neonline.game

import pt.tiago.neonline.core.ai.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.core.solver.SolverBudgetExceeded

data class EndgameRivalResult(val choice: AiChoice?, val nodesExplored: Int)

/** Exact late-classic attempt with the published heuristic as a deterministic fallback. */
object EndgameRivalSearch {
    @JvmOverloads
    fun choose(state: GameState, profile: AiProfile, enabled: Boolean, maxNodes: Long = 200_000L,
        checkCancelled: () -> Unit = {}): AiChoice? = search(state, profile, enabled, maxNodes, checkCancelled).choice

    @JvmOverloads
    fun search(state: GameState, profile: AiProfile, enabled: Boolean, maxNodes: Long = 200_000L,
        checkCancelled: () -> Unit = {}): EndgameRivalResult {
        require(maxNodes > 0L)
        checkCancelled()
        if (state.status != Status.InProgress) return EndgameRivalResult(null, 0)
        var exactNodes = 0L
        if (enabled && state.width == 7 && state.height == 6 && state.connect == 4 &&
            state.pieces.count { it == null } <= EndgameReviewAnalyzer.MAX_EMPTY) {
            val solved = try { PerfectSolver(maxNodes).solve(state, checkCancelled) }
                catch (e: SolverBudgetExceeded) { exactNodes = e.nodesExplored; null }
            val move = solved?.bestMoves?.firstOrNull()
            if (move != null) {
                checkCancelled()
                val rng = state.aiRng()
                rng.nextInt(1000)
                return EndgameRivalResult(AiChoice(move, rng.stateHex()), solved.nodesExplored.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        checkCancelled()
        val fallbackSearch = AiSearch(profile)
        val fallback = fallbackSearch.chooseMove(state)
        checkCancelled()
        return EndgameRivalResult(
            fallback,
            (exactNodes + fallbackSearch.nodesExplored.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
