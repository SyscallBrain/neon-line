package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

enum class ForkDemonstrationStatus { READY, UNAVAILABLE, LIMIT_REACHED }
data class ForkDefense(val move: Move, val after: GameState, val response: Move?, val finalState: GameState)
data class ForkLesson(val player: Player, val opponent: Player, val creatingMove: Move.Drop,
    val forkState: GameState, val winningColumns: List<Int>, val defenses: List<ForkDefense>)
data class ForkDemonstrationResult(val status: ForkDemonstrationStatus, val lesson: ForkLesson? = null)

/** Rechecked hypothetical examples, never a live game or a claim about the recorded continuation. */
object ForkDemonstration {
    private class Exhausted : RuntimeException()

    @JvmOverloads
    fun build(input: TacticalReviewRequest, finding: TacticalFinding, maxProbes: Int = 20_000,
        checkCancelled: () -> Unit = {}): ForkDemonstrationResult {
        require(maxProbes > 0)
        checkCancelled()
        fun unavailable() = ForkDemonstrationResult(ForkDemonstrationStatus.UNAVAILABLE)
        if (finding.reason != TacticalReason.ALLOWED_DOUBLE_THREAT ||
            (input is TacticalReviewInput && input.puzzleLimit != null)) return unavailable()
        // This validates the entire journal and exact own-move/before/played/after anchor, not just its prefix.
        if (TacticalReviewReplay.build(input, finding, checkCancelled) == null) return unavailable()
        val player = (input as? LocalTacticalReviewInput)?.player ?: Player.P1
        val opponent = player.opponent()
        val reply = Move.Drop(finding.rivalWinningColumn ?: return unavailable())
        val actual = finding.playedAfter
        if (actual.status != Status.InProgress || actual.toMove != opponent || reply !in GameEngine.validMoves(actual)) return unavailable()
        var probes = 0
        fun apply(state: GameState, move: Move): GameState {
            checkCancelled()
            if (++probes > maxProbes) throw Exhausted()
            return GameEngine.apply(state, move).state
        }
        try {
            val fork = apply(actual, reply)
            if (fork.status != Status.InProgress || fork.toMove != player) return unavailable()
            // Static threat columns are hypothetical; defenses and replies below retain their actual owners/turns.
            val probe = fork.copy(toMove = opponent)
            val columns = GameEngine.validMoves(probe).filterIsInstance<Move.Drop>().filter { it.kind == PieceKind.NORMAL }
                .filter { apply(probe, it).status == Status.Won(opponent) }.map { it.col }
            if (columns.size < 2) return unavailable()
            val legal = GameEngine.validMoves(fork)
            if (legal.isEmpty()) return unavailable()
            val defenses = mutableListOf<ForkDefense>()
            for (move in legal) {
                checkCancelled()
                val defended = apply(fork, move)
                if (defended.status == Status.Won(opponent)) {
                    defenses += ForkDefense(move, defended, null, defended)
                    continue
                }
                if (defended.status != Status.InProgress || defended.toMove != opponent) return unavailable()
                var response: Move? = null
                var won: GameState? = null
                for (candidate in GameEngine.validMoves(defended)) {
                    val after = apply(defended, candidate)
                    if (after.status == Status.Won(opponent)) { response = candidate; won = after; break }
                }
                // A single rescue refutes the lesson; no partial list may escape.
                if (response == null || won == null) return unavailable()
                defenses += ForkDefense(move, defended, response, won)
            }
            return ForkDemonstrationResult(ForkDemonstrationStatus.READY,
                ForkLesson(player, opponent, reply, fork, columns.toList(), defenses.toList()))
        } catch (_: Exhausted) {
            return ForkDemonstrationResult(ForkDemonstrationStatus.LIMIT_REACHED)
        }
    }
}
