package pt.tiago.neonline.core.objectives

import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Player

/** A draw retries the same slot once, preserving its opening player. No rounds after a decision. */
data class BossSeries(
    val wins: Int = 0,
    val losses: Int = 0,
    val repeatingDraw: Boolean = false,
    val winningMoves: Int = 0,
    val totalMoves: Int = 0,
    val hintsUsed: Int = 0,
) {
    val roundIndex: Int get() = wins + losses
    val decided: Boolean get() = wins >= 2 || losses >= 2

    fun record(outcome: MatchOutcome, moves: Int, hints: Int): BossSeries {
        require(!decided && moves >= 0 && hints >= 0)
        val win = outcome as? MatchOutcome.Win
        val won = win?.player == Player.P1
        val lost = win?.player == Player.P2 || (outcome is MatchOutcome.Draw && repeatingDraw)
        return copy(
            wins = wins + if (won) 1 else 0,
            losses = losses + if (lost) 1 else 0,
            repeatingDraw = outcome is MatchOutcome.Draw && !repeatingDraw,
            winningMoves = winningMoves + if (won) moves else 0,
            totalMoves = totalMoves + moves,
            hintsUsed = hintsUsed + hints,
        )
    }

    fun result(objective: LevelObjective): LevelOutcome? = when {
        wins >= 2 -> LevelOutcome.Won(LevelEvaluator.starsForMoves(objective, winningMoves))
        losses >= 2 -> LevelOutcome.Lost(LossReasons.RIVAL_WON_BEST_OF)
        else -> null
    }
}
