package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.SavedMatch
import pt.tiago.neonline.data.SavedLocalSession
import pt.tiago.neonline.data.LocalSessionReplay

sealed interface TacticalReviewRequest

/** One finished duel, never a mutable handle to the live game or its profile. */
data class TacticalReviewInput(val start: GameState, val journal: SavedMatch,
    val hints: Int, val undos: Int, val puzzleLimit: Int? = null) : TacticalReviewRequest

data class LocalTacticalReviewInput(val saved: SavedLocalSession, val player: Player) : TacticalReviewRequest

enum class TacticalReviewStatus { FOUND, NO_IMMEDIATE_MISTAKE, FADING_BOARD, INVALID_JOURNAL, LIMIT_REACHED }
enum class TacticalReason { MISSED_WIN, ALLOWED_WIN, GAVE_WIN, ALLOWED_DOUBLE_THREAT }

data class TacticalFinding(val before: GameState, val playedAfter: GameState, val alternativeAfter: GameState,
    val humanMove: Int, val played: Move, val alternative: Move, val playedColumn: Int?,
    val alternativeColumn: Int, val reason: TacticalReason, val rivalWinningColumn: Int? = null)

data class TacticalReviewResult(val status: TacticalReviewStatus, val finding: TacticalFinding? = null)

/** Exact one-move facts from the shared engine. This is not a strategic position evaluator. */
object TacticalReviewAnalyzer {
    private class Exhausted : RuntimeException()
    private data class Decision(val before: GameState, val move: Move, val number: Int)

    @JvmOverloads
    fun analyze(input: TacticalReviewInput, maxProbes: Int = 20_000,
        checkCancelled: () -> Unit = {}): TacticalReviewResult {
        require(maxProbes > 0)
        checkCancelled()
        val replay = MatchReplay.restore(input.start, input.journal, input.hints, input.undos, allowTerminal = true, checkCancelled = checkCancelled)
            ?: return TacticalReviewResult(TacticalReviewStatus.INVALID_JOURNAL)
        if (input.start.fadeAfter != null) return TacticalReviewResult(TacticalReviewStatus.FADING_BOARD)
        val decisions = replay.undoPoints.map { turn ->
            checkCancelled()
            val step = input.journal.steps[turn.stepCount]
            val move = when (step.action) {
                "drop" -> Move.Drop(requireNotNull(step.column), step.kind)
                "pop" -> Move.Pop(requireNotNull(step.column))
                else -> Move.TimeUp
            }
            Decision(turn.state, move, turn.humanMoves + 1)
        }
        return evaluate(decisions, Player.P1, input.puzzleLimit, maxProbes, checkCancelled)
    }

    @JvmOverloads
    fun analyzeLocal(input: LocalTacticalReviewInput, maxProbes: Int = 20_000,
        checkCancelled: () -> Unit = {}): TacticalReviewResult {
        require(maxProbes > 0)
        val replay = LocalSessionReplay.trace(input.saved, checkCancelled)
            ?: return TacticalReviewResult(TacticalReviewStatus.INVALID_JOURNAL)
        var number = 0
        val decisions = replay.turns.mapNotNull { turn ->
            checkCancelled()
            if (turn.before.toMove == input.player) Decision(turn.before, turn.move, ++number) else null
        }
        return evaluate(decisions, input.player, null, maxProbes, checkCancelled)
    }

    private fun evaluate(decisions: List<Decision>, player: Player, puzzleLimit: Int?, maxProbes: Int,
        checkCancelled: () -> Unit): TacticalReviewResult {
        val opponent = player.opponent()
        var probes = 0
        fun apply(state: GameState, move: Move): MoveResult {
            checkCancelled()
            if (++probes > maxProbes) throw Exhausted()
            return GameEngine.apply(state, move)
        }
        fun column(move: Move, result: MoveResult? = null): Int? = when (move) {
            is Move.Drop -> move.col
            is Move.Pop -> move.col
            Move.TimeUp -> result?.events?.filterIsInstance<GameEvent.TimeUpPlayed>()?.firstOrNull()?.col
        }
        fun winningReply(state: GameState): Move? = if (state.status != Status.InProgress) null else
            GameEngine.validMoves(state).firstOrNull { apply(state, it).state.status == Status.Won(opponent) }
        try {
            for (turn in decisions) {
                checkCancelled()
                if (puzzleLimit?.let { turn.number > it } == true) break
                val played = turn.move
                val actual = apply(turn.before, played)
                if (actual.state.status == Status.Won(player)) continue
                val alternatives = GameEngine.validMoves(turn.before).filter { it != played }
                val results = mutableMapOf<Move, GameState>()
                fun after(move: Move) = results.getOrPut(move) { apply(turn.before, move).state }
                fun found(move: Move, reason: TacticalReason, reply: Move? = null) = TacticalReviewResult(
                    TacticalReviewStatus.FOUND, TacticalFinding(turn.before, actual.state, after(move),
                        turn.number, played, move, column(played, actual), requireNotNull(column(move)), reason,
                        reply?.let { column(it) }))
                val win = alternatives.firstOrNull { after(it).status == Status.Won(player) }
                if (win != null) return found(win, TacticalReason.MISSED_WIN)
                // A block on the puzzle's last turn cannot rescue its move-limit objective.
                if (puzzleLimit?.let { turn.number >= it } == true) continue
                val gaveWin = actual.state.status == Status.Won(opponent)
                val reply = winningReply(actual.state)
                if (!gaveWin && reply == null) continue
                val safe = alternatives.firstOrNull { move ->
                    val next = after(move)
                    next.status != Status.Won(opponent) && winningReply(next) == null
                }
                if (safe != null) return found(safe, if (gaveWin) TacticalReason.GAVE_WIN else TacticalReason.ALLOWED_WIN, reply)
            }
        } catch (_: Exhausted) {
            return TacticalReviewResult(TacticalReviewStatus.LIMIT_REACHED)
        }
        return TacticalReviewResult(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE)
    }
}
