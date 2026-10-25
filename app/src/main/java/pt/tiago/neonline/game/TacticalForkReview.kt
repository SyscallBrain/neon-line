package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.LocalSessionReplay

/** Additional exact, bounded check after immediate mistakes; not a strategic evaluator. */
object TacticalForkReviewAnalyzer {
    private class Exhausted : RuntimeException()

    @JvmOverloads
    fun analyze(input: TacticalReviewRequest, maxProbes: Int = 20_000,
        checkCancelled: () -> Unit = {}): TacticalReviewResult {
        require(maxProbes > 0)
        checkCancelled()
        val player: Player
        val turns = when (input) {
            is TacticalReviewInput -> {
                player = Player.P1
                val trace = MatchReplay.trace(input.start, input.journal, input.hints, input.undos, checkCancelled)
                    ?: return TacticalReviewResult(TacticalReviewStatus.INVALID_JOURNAL)
                if (input.start.fadeAfter != null) return TacticalReviewResult(TacticalReviewStatus.FADING_BOARD)
                // A defensive alternative does not prove the puzzle's move-limit objective.
                if (input.puzzleLimit != null) return TacticalReviewResult(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE)
                trace.turns
            }
            is LocalTacticalReviewInput -> {
                player = input.player
                val trace = LocalSessionReplay.trace(input.saved, checkCancelled)
                    ?: return TacticalReviewResult(TacticalReviewStatus.INVALID_JOURNAL)
                trace.turns.map { turn ->
                    checkCancelled()
                    ReplayTurn(turn.before, turn.move, GameEngine.apply(turn.before, turn.move).state)
                }
            }
        }
        val opponent = player.opponent()
        var probes = 0
        fun apply(state: GameState, move: Move): MoveResult {
            checkCancelled()
            if (++probes > maxProbes) throw Exhausted()
            return GameEngine.apply(state, move)
        }
        fun winningReply(state: GameState): Boolean = state.status == Status.InProgress &&
            GameEngine.validMoves(state).any { apply(state, it).state.status == Status.Won(opponent) }
        fun doubleNormalThreat(state: GameState): Boolean {
            // Hypothetical threats only. Actual defense/reply states below retain their real turn.
            val probe = state.copy(toMove = opponent)
            var wins = 0
            for (move in GameEngine.validMoves(probe).filterIsInstance<Move.Drop>().filter { it.kind == PieceKind.NORMAL }) {
                if (apply(probe, move).state.status == Status.Won(opponent) && ++wins == 2) return true
            }
            return false
        }
        fun forcedFork(state: GameState): Move.Drop? {
            if (state.status != Status.InProgress || state.toMove != opponent) return null
            for (reply in GameEngine.validMoves(state).filterIsInstance<Move.Drop>().filter { it.kind == PieceKind.NORMAL }) {
                val fork = apply(state, reply).state
                if (fork.status != Status.InProgress || fork.toMove != player || !doubleNormalThreat(fork)) continue
                val defenses = GameEngine.validMoves(fork)
                if (defenses.isNotEmpty() && defenses.all { move ->
                    val defended = apply(fork, move).state
                    defended.status == Status.Won(opponent) ||
                        (defended.status == Status.InProgress && winningReply(defended))
                }) return reply
            }
            return null
        }
        var ownMove = 0
        try {
            for (turn in turns) {
                checkCancelled()
                if (turn.before.toMove != player) continue
                ownMove++
                val fork = forcedFork(turn.after) ?: continue
                val alternative = GameEngine.validMoves(turn.before).firstOrNull { move ->
                    if (move == turn.move) false else {
                        val after = apply(turn.before, move).state
                        after.status != Status.Won(opponent) && !winningReply(after) && forcedFork(after) == null
                    }
                } ?: continue
                val after = apply(turn.before, alternative).state
                val actualColumn = when (val move = turn.move) {
                    is Move.Drop -> move.col
                    is Move.Pop -> move.col
                    Move.TimeUp -> apply(turn.before, move).events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col
                }
                val alternativeColumn = when (alternative) {
                    is Move.Drop -> alternative.col
                    is Move.Pop -> alternative.col
                    Move.TimeUp -> error("TimeUp is not a selectable alternative")
                }
                return TacticalReviewResult(TacticalReviewStatus.FOUND, TacticalFinding(turn.before, turn.after, after,
                    ownMove, turn.move, alternative, actualColumn, alternativeColumn,
                    TacticalReason.ALLOWED_DOUBLE_THREAT, fork.col))
            }
        } catch (_: Exhausted) {
            return TacticalReviewResult(TacticalReviewStatus.LIMIT_REACHED)
        }
        return TacticalReviewResult(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE)
    }
}
