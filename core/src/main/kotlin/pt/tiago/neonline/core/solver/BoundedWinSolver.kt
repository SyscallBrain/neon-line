package pt.tiago.neonline.core.solver

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

/** Exact bounded puzzle proof through the shared engine: our choice versus every legal defence. */
class BoundedWinSolver(private val maxNodes: Int = 120_000) {
    private class Exhausted : RuntimeException()
    private var nodes = 0
    private val memo = HashMap<Triple<Long, Int, List<Long>>, Boolean>()

    fun winningMoves(state: GameState, ownMoves: Int): List<Move>? = if (ownMoves <= 0 || state.status != Status.InProgress) emptyList() else try {
        nodes = 0
        memo.clear()
        require(state.toMove == Player.P1)
        GameEngine.validMoves(state).filter { move -> wins(GameEngine.apply(state, move).state, ownMoves - 1) }
    } catch (_: Exhausted) { null }

    fun canWin(state: GameState, ownMoves: Int): Boolean? = try {
        nodes = 0
        memo.clear()
        wins(state, ownMoves)
    } catch (_: Exhausted) { null }

    private fun wins(state: GameState, remaining: Int): Boolean {
        if (++nodes > maxNodes) throw Exhausted()
        when (val status = state.status) {
            is Status.Won -> return status.player == Player.P1
            is Status.Draw -> return false
            Status.InProgress -> Unit
        }
        if (remaining <= 0) return false
        val key = Triple(state.repetitionHash(), remaining, if (state.popOut) state.history else emptyList())
        memo[key]?.let { return it }
        val moves = GameEngine.validMoves(state)
        val answer = if (state.toMove == Player.P1) {
            moves.any { wins(GameEngine.apply(state, it).state, remaining - 1) }
        } else {
            moves.isNotEmpty() && moves.all { wins(GameEngine.apply(state, it).state, remaining) }
        }
        memo[key] = answer
        return answer
    }
}
