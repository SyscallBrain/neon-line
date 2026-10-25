package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.*
import pt.tiago.neonline.data.LocalSessionReplay

enum class EndgameReviewStatus { EXACT, UNSUPPORTED, TOO_EARLY, INVALID_JOURNAL, NO_MOVES, LIMIT_REACHED, NO_WORSENING }
data class EndgameComparison(val player: Player, val number: Int, val before: GameState,
    val playedAfter: GameState, val alternativeAfter: GameState, val played: Move, val playedColumn: Int,
    val alternative: Move.Drop, val beforeValue: SolverValue, val afterValue: SolverValue, val nodes: Long)
data class EndgameReviewResult(val status: EndgameReviewStatus, val comparison: EndgameComparison? = null)

data class EndgameWindowResult(val status: EndgameReviewStatus, val comparison: EndgameComparison? = null,
    val firstNumber: Int? = null, val lastNumber: Int? = null, val reviewedMoves: Int = 0, val nodes: Long = 0)

/** Optional last-own-turn evaluation, not a claim about the first decisive move or puzzle objective. */
object EndgameReviewAnalyzer {
    const val MAX_EMPTY = 10

    @JvmOverloads
    fun analyze(input: TacticalReviewRequest, maxNodes: Long = 200_000L,
        checkCancelled: () -> Unit = {}): EndgameReviewResult {
        require(maxNodes > 0L)
        checkCancelled()
        fun status(value: EndgameReviewStatus) = EndgameReviewResult(value)
        val prepared = prepare(input, checkCancelled)
        prepared.failure?.let { return status(it) }
        val own = prepared.own
        val turn = own.lastOrNull() ?: return status(EndgameReviewStatus.NO_MOVES)
        return compare(turn, prepared.player, own.size, maxNodes, checkCancelled)
    }

    /** First value drop within the eligible endgame window, never the whole match. */
    @JvmOverloads
    fun analyzeMistake(input: TacticalReviewRequest, maxNodes: Long = 200_000L,
        checkCancelled: () -> Unit = {}): EndgameWindowResult {
        require(maxNodes > 0L)
        checkCancelled()
        val prepared = prepare(input, checkCancelled)
        prepared.failure?.let { return EndgameWindowResult(it) }
        val own = prepared.own
        if (own.isEmpty()) return EndgameWindowResult(EndgameReviewStatus.NO_MOVES)
        if (own.any { checkCancelled(); !classic(it.before) }) return EndgameWindowResult(EndgameReviewStatus.UNSUPPORTED)
        val eligible = own.withIndex().filter { checkCancelled(); it.value.before.pieces.count { p -> p == null } <= MAX_EMPTY }
        if (eligible.isEmpty()) return EndgameWindowResult(EndgameReviewStatus.TOO_EARLY)
        var nodes = 0L
        var reviewed = 0
        fun result(status: EndgameReviewStatus, comparison: EndgameComparison? = null) = EndgameWindowResult(
            status, comparison, eligible.first().index + 1, eligible.last().index + 1, reviewed, nodes)
        for ((index, turn) in eligible) {
            checkCancelled()
            val remaining = maxNodes - nodes
            if (remaining <= 0L) return result(EndgameReviewStatus.LIMIT_REACHED)
            val evaluated = compare(turn, prepared.player, index + 1, remaining, checkCancelled)
            val c = evaluated.comparison ?: return result(evaluated.status)
            nodes += c.nodes
            reviewed++
            if (rank(c.afterValue) < rank(c.beforeValue)) return result(EndgameReviewStatus.EXACT, c)
        }
        return result(EndgameReviewStatus.NO_WORSENING)
    }

    private fun rank(value: SolverValue) = when (value) { SolverValue.WIN -> 2; SolverValue.DRAW -> 1; SolverValue.LOSS -> 0 }

    private data class Prepared(val player: Player, val own: List<ReplayTurn>, val failure: EndgameReviewStatus? = null)
    private fun prepare(input: TacticalReviewRequest, checkCancelled: () -> Unit): Prepared {
        fun failed(status: EndgameReviewStatus) = Prepared(Player.P1, emptyList(), status)
        val player: Player
        val turns = when (input) {
            is TacticalReviewInput -> {
                player = Player.P1
                val trace = MatchReplay.trace(input.start, input.journal, input.hints, input.undos, checkCancelled)
                    ?: return failed(EndgameReviewStatus.INVALID_JOURNAL)
                if (input.puzzleLimit != null) return failed(EndgameReviewStatus.UNSUPPORTED)
                trace.turns
            }
            is LocalTacticalReviewInput -> {
                player = input.player
                val trace = LocalSessionReplay.trace(input.saved, checkCancelled)
                    ?: return failed(EndgameReviewStatus.INVALID_JOURNAL)
                trace.turns.map { turn ->
                    checkCancelled()
                    ReplayTurn(turn.before, turn.move, GameEngine.apply(turn.before, turn.move).state)
                }
            }
        }
        val own = turns.filter { checkCancelled(); it.before.toMove == player }
        return Prepared(player, own)
    }

    private fun compare(turn: ReplayTurn, player: Player, number: Int, maxNodes: Long,
        checkCancelled: () -> Unit): EndgameReviewResult {
        fun status(value: EndgameReviewStatus) = EndgameReviewResult(value)
        val before = turn.before
        if (!classic(before)) return status(EndgameReviewStatus.UNSUPPORTED)
        if (before.pieces.count { it == null } > MAX_EMPTY) return status(EndgameReviewStatus.TOO_EARLY)
        try {
            val solved = PerfectSolver(maxNodes).solve(before, checkCancelled)
                ?: return status(EndgameReviewStatus.UNSUPPORTED)
            var nodes = solved.nodesExplored
            val afterValue = when (val outcome = turn.after.status) {
                is Status.Won -> if (outcome.player == player) SolverValue.WIN else SolverValue.LOSS
                is Status.Draw -> SolverValue.DRAW
                Status.InProgress -> {
                    val remaining = maxNodes - nodes
                    if (remaining <= 0L) return status(EndgameReviewStatus.LIMIT_REACHED)
                    val after = PerfectSolver(remaining).solve(turn.after, checkCancelled)
                        ?: return status(EndgameReviewStatus.UNSUPPORTED)
                    nodes += after.nodesExplored
                    if (turn.after.toMove == player) after.value else when (after.value) {
                        SolverValue.WIN -> SolverValue.LOSS
                        SolverValue.LOSS -> SolverValue.WIN
                        SolverValue.DRAW -> SolverValue.DRAW
                    }
                }
            }
            val alternatives = solved.bestMoves.filterIsInstance<Move.Drop>()
            val alternative = alternatives.firstOrNull { it == turn.move } ?: alternatives.firstOrNull()
                ?: return status(EndgameReviewStatus.INVALID_JOURNAL)
            checkCancelled()
            val applied = GameEngine.apply(before, alternative)
            if (applied.events.any { it is GameEvent.MoveRejected }) return status(EndgameReviewStatus.INVALID_JOURNAL)
            val playedColumn = when (val move = turn.move) {
                is Move.Drop -> move.col
                is Move.Pop -> move.col
                Move.TimeUp -> GameEngine.apply(before, move).events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col
            }
            return EndgameReviewResult(EndgameReviewStatus.EXACT,
                EndgameComparison(player, number, before, turn.after, applied.state, turn.move, playedColumn,
                    alternative, solved.value, afterValue, nodes))
        } catch (_: SolverBudgetExceeded) {
            return status(EndgameReviewStatus.LIMIT_REACHED)
        }
    }

    private fun classic(state: GameState): Boolean = state.width == 7 && state.height == 6 && state.connect == 4 &&
        !state.popOut && state.rotateEvery == null && state.fadeAfter == null && state.moveClockSec == null &&
        state.shapeRule == null && state.targets.isEmpty() && state.lockedCol == null && state.lockedFor == null &&
        state.playable.all { it } && state.pieces.all { it == null || it.kind == PieceKind.NORMAL } &&
        state.inventory.values.none { kinds -> kinds.any { (kind, count) -> kind != PieceKind.NORMAL && count != 0 } }
}
