package pt.tiago.neonline.core.ai

import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status

/**
 * Deterministic negamax with alpha-beta pruning, a fixed-size transposition table with
 * depth-preferred replacement and a node budget. Iterative deepening finds the best move at the
 * deepest depth that fits in the budget; the move from the last finished depth is the answer.
 *
 * The AI is part of the rules: the same state plus the same profile always produces the same move,
 * because the search is bounded by nodes (never by time), the evaluation is integer-only and every
 * collection iterates in a fixed order.
 */
class AiSearch(val profile: AiProfile) {

    /** Nodes expanded by the last [chooseMove] call, for tests and diagnostics. */
    var nodesExplored: Int = 0
        private set

    private class BudgetExceeded : Exception()

    private class Entry(val key: Long, val value: Int, val depth: Int, val bound: Byte)

    private val tableSize = 1 shl 16
    private val table = arrayOfNulls<Entry>(tableSize)

    fun chooseMove(state: GameState, checkCancelled: () -> Unit = {}): AiChoice? {
        checkCancelled()
        if (state.status != Status.InProgress) return null
        val moves = GameEngineMoves.ordered(state, profile)
        if (moves.isEmpty()) return null

        val rng = state.aiRng()
        val blunderRoll = rng.nextInt(1000)
        val aiStateHex = rng.stateHex()
        val mayBlunder = profile.blunderMayMiss && blunderRoll < profile.blunderRate

        // Safety rule: immediate wins and blocks are always played, except when the blunder
        // fires on Easy. Medium and Hard never miss them.
        if (!mayBlunder) {
            val winNow = moves.firstOrNull { winsNow(state, it) }
            val blockNow = if (winNow != null) null else moves.firstOrNull { blocksNow(state, it) }
            val safe = winNow ?: blockNow
            if (safe != null) return AiChoice(safe, aiStateHex)
            if (profile.mirror) {
                val mirrored = mirrorMove(state, moves)
                if (mirrored != null) return AiChoice(mirrored, aiStateHex)
            }
        }

        var best = moves[0]
        var bestScore = Int.MIN_VALUE
        nodesExplored = 0
        var budgetExceeded = false
        try {
            var depth = 1
            while (depth <= profile.maxDepth) {
                checkCancelled()
                var localBest: Move? = null
                var localScore = Int.MIN_VALUE
                for (move in moves) {
                    checkCancelled()
                    val child = apply(state, move)
                    val score = -negamax(child, depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, 1, checkCancelled)
                    if (score > localScore) {
                        localScore = score
                        localBest = move
                    }
                }
                if (localBest != null) {
                    best = localBest
                    bestScore = localScore
                }
                depth++
            }
        } catch (_: BudgetExceeded) {
            budgetExceeded = true
            // Keep the best move from the last completed depth.
        }

        // The blunder, when it fires, plays the second or third best move of the root ordering.
        if (mayBlunder && moves.size > 1) {
            val ranked = moves.map { move ->
                checkCancelled()
                val child = apply(state, move)
                fun staticScore(): Int = when (val status = child.status) {
                    is Status.Won -> if (status.player == state.toMove) 100_000 else -100_000
                    is Status.Draw -> 0
                    Status.InProgress -> -Evaluator.evaluate(child, profile)
                }
                val score = if (budgetExceeded || nodesExplored >= profile.nodeBudget) staticScore() else try {
                    -negamax(child, 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1, 1, checkCancelled)
                } catch (_: BudgetExceeded) {
                    budgetExceeded = true
                    staticScore()
                }
                move to score
            }.sortedByDescending { it.second }
            val pick = ranked.getOrNull(1 + (blunderRoll % 2)) ?: ranked[1]
            return AiChoice(pick.first, aiStateHex)
        }
        if (bestScore == Int.MIN_VALUE) best = moves[0]
        return AiChoice(best, aiStateHex)
    }

    private fun negamax(state: GameState, depth: Int, alpha: Int, beta: Int, plyFromRoot: Int, checkCancelled: () -> Unit): Int {
        checkCancelled()
        if (++nodesExplored > profile.nodeBudget) throw BudgetExceeded()
        val outcome = state.status
        if (outcome is Status.Won) {
            // Pop and rotation can complete the other player's line.
            return if (outcome.player == state.toMove) 100_000 - plyFromRoot else -100_000 + plyFromRoot
        }
        if (outcome is Status.Draw) return 0
        if (depth <= 0) return Evaluator.evaluate(state, profile)

        val key = state.repetitionHash()
        val index = (key xor (key ushr 32)).toInt() and (tableSize - 1)
        val entry = table[index]
        if (entry != null && entry.key == key && entry.depth >= depth) {
            return entry.value
        }

        val moves = GameEngineMoves.ordered(state, profile)
        if (moves.isEmpty()) return 0
        var alpha = alpha
        var best = Int.MIN_VALUE
        for (move in moves) {
            checkCancelled()
            val score = -negamax(apply(state, move), depth - 1, -beta, -alpha, plyFromRoot + 1, checkCancelled)
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        table[index] = Entry(key, best, depth, 0)
        return best
    }

    private fun apply(state: GameState, move: Move): GameState =
        pt.tiago.neonline.core.rules.GameEngine.apply(state, move).state

    private fun winsNow(state: GameState, move: Move): Boolean =
        (apply(state, move).status as? Status.Won)?.player == state.toMove

    private fun blocksNow(state: GameState, move: Move): Boolean {
        val opponent = state.toMove.opponent()
        if (!Evaluator.hasThreat(state, opponent)) return false
        val child = apply(state, move)
        // After the move it is the opponent's turn: they must have no immediate win left.
        return !Evaluator.hasThreat(child, child.toMove)
    }

    /** The column symmetric to the opponent's last move, when playing it is safe. */
    private fun mirrorMove(state: GameState, moves: List<Move>): Move? {
        val me = state.toMove
        val them = me.opponent()
        val theirLast = state.pieces
            .mapIndexedNotNull { cell, piece -> if (piece != null && piece.owner == them) cell else null }
            .maxByOrNull { state.pieces[it]!!.placedAtPly } ?: return null
        val col = state.width - 1 - state.colOf(theirLast)
        val candidate = moves.firstOrNull { it is Move.Drop && it.col == col } ?: return null
        // Safe: it does not hand the opponent an immediate win.
        val child = apply(state, candidate)
        if (Evaluator.countThreats(child, me) > 0) return null
        return candidate
    }
}

/** Move ordering shared by the search: immediate threats first, then centre-out. */
internal object GameEngineMoves {
    fun ordered(state: GameState, profile: AiProfile): List<Move> {
        val moves = pt.tiago.neonline.core.rules.GameEngine.validMoves(state)
        val me = state.toMove
        val them = me.opponent()
        val myMask = Evaluator.threatMask(state, me)
        val theirMask = Evaluator.threatMask(state, them)
        val base: (Move) -> Int = { move ->
            when (move) {
                is Move.Drop -> {
                    val bit = 1L shl move.col
                    when {
                        (myMask and bit) != 0L -> 100
                        (theirMask and bit) != 0L -> 90
                        move.kind != PieceKind.NORMAL -> if (profile.attack >= 5) 70 else 10
                        else -> centerBias(move.col, state.width)
                    }
                }
                is Move.Pop -> if (profile.prefersPop) 80 else 5
                Move.TimeUp -> 0
            }
        }
        return moves.sortedByDescending(base)
    }

    private fun landingRow(state: GameState, col: Int): Int? {
        var r = state.height - 1
        while (r >= 0) {
            val cell = state.index(r, col)
            if (!state.playable[cell] || state.pieces[cell] != null) r-- else return r
        }
        return null
    }

    private fun centerBias(col: Int, width: Int): Int = width - Math.abs(col - (width - 1) / 2)
}
