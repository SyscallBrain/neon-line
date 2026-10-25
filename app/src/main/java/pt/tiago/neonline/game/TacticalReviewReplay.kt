package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.LocalSessionReplay

/** First frame is before the excerpt; other frames follow real journal actions. */
data class ReviewReplayFrame(val state: GameState, val moveNumber: Int,
    val move: Move? = null, val player: Player? = null, val column: Int? = null)
data class ReviewReplayWindow(val frames: List<ReviewReplayFrame>, val decisiveIndex: Int)

/** Recorded continuation only; never re-runs the AI or plays the suggested alternative. */
object TacticalReviewReplay {
    @JvmOverloads
    fun build(input: TacticalReviewRequest, finding: TacticalFinding,
        checkCancelled: () -> Unit = {}): ReviewReplayWindow? =
        buildRecorded(input, null, finding.humanMove, finding.before, finding.played, finding.playedAfter, checkCancelled)

    @JvmOverloads
    fun buildEndgame(input: TacticalReviewRequest, comparison: EndgameComparison,
        checkCancelled: () -> Unit = {}): ReviewReplayWindow? =
        buildRecorded(input, comparison.player, comparison.number, comparison.before, comparison.played,
            comparison.playedAfter, checkCancelled)

    private fun buildRecorded(input: TacticalReviewRequest, expectedPlayer: Player?, number: Int,
        before: GameState, played: Move, after: GameState, checkCancelled: () -> Unit): ReviewReplayWindow? {
        checkCancelled()
        val player: Player
        val turns = when (input) {
            is TacticalReviewInput -> {
                player = Player.P1
                val trace = MatchReplay.trace(input.start, input.journal, input.hints, input.undos, checkCancelled)
                    ?: return null
                if (input.start.fadeAfter != null) return null
                trace.turns
            }
            is LocalTacticalReviewInput -> {
                player = input.player
                val trace = LocalSessionReplay.trace(input.saved, checkCancelled) ?: return null
                trace.turns.map { turn ->
                    checkCancelled()
                    ReplayTurn(turn.before, turn.move, GameEngine.apply(turn.before, turn.move).state)
                }
            }
        }
        if (expectedPlayer != null && player != expectedPlayer) return null
        var ownMove = 0
        val decisive = turns.indexOfFirst { turn ->
            checkCancelled()
            turn.before.toMove == player && ++ownMove == number
        }
        if (decisive < 0) return null
        val turn = turns[decisive]
        if (turn.before != before || turn.move != played || turn.after != after) return null
        val first = (decisive - 3).coerceAtLeast(0)
        val last = (decisive + 3).coerceAtMost(turns.lastIndex)
        val frames = mutableListOf(ReviewReplayFrame(turns[first].before, first))
        for (index in first..last) {
            checkCancelled()
            val recorded = turns[index]
            val column = when (val move = recorded.move) {
                is Move.Drop -> move.col
                is Move.Pop -> move.col
                Move.TimeUp -> GameEngine.apply(recorded.before, move).events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col
            }
            frames += ReviewReplayFrame(recorded.after, index + 1, recorded.move, recorded.before.toMove, column)
        }
        return ReviewReplayWindow(frames.toList(), decisive - first + 1)
    }
}
