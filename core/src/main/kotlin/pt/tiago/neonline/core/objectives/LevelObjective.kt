package pt.tiago.neonline.core.objectives

import pt.tiago.neonline.core.model.ShapeRule

/** The five campaign level kinds. */
enum class LevelKind {
    DUEL,
    PUZZLE,
    DEFENSE,
    SHAPE,
    BOSS,
}

/** What a level asks of the player. Comes from the level JSON. */
data class LevelObjective(
    val kind: LevelKind,
    /** Puzzle: win within this many of the player's own moves. */
    val winInMoves: Int? = null,
    /** Defense: survive this many rival moves without the rival making a line. */
    val surviveAiMoves: Int? = null,
    /** Shape: the rule the player's winning line must obey. */
    val shapeRule: ShapeRule? = null,
    /** Boss: best of how many duels. */
    val bestOf: Int = 3,
    /** Duel and shape: two stars within this many of the player's moves. */
    val starsTwo: Int? = null,
    /** Duel and shape: three stars within this many of the player's moves. */
    val starsThree: Int? = null,
)

/** How a level ended for the player. */
sealed interface LevelOutcome {
    data class Won(val stars: Int) : LevelOutcome
    data class Lost(val reason: String) : LevelOutcome
}

/** Why a level was lost, for the result screen. */
object LossReasons {
    const val RIVAL_LINE = "rival_line"
    const val DRAW = "draw"
    const val OUT_OF_MOVES = "out_of_moves"
    const val RIVAL_WON_BEST_OF = "rival_won_best_of"
}

/**
 * Maps a finished match to the level result. Pure: the caller supplies what it counted while
 * playing (the player's own moves, hints used and whether a player threat was on the board at the
 * end), so the same inputs always give the same stars.
 */
object LevelEvaluator {

    /**
     * @param outcome the engine's match outcome
     * @param myMoves how many moves the player made
     * @param hintsUsed how many hints the player asked for
     * @param threatAtEnd whether the player had a playable winning threat when the match ended
     */
    fun evaluate(
        objective: LevelObjective,
        outcome: pt.tiago.neonline.core.model.MatchOutcome?,
        myMoves: Int,
        hintsUsed: Int = 0,
        threatAtEnd: Boolean = false,
    ): LevelOutcome {
        val win = outcome as? pt.tiago.neonline.core.model.MatchOutcome.Win
        return when (objective.kind) {
            LevelKind.DUEL, LevelKind.SHAPE, LevelKind.BOSS -> when {
                win?.player == pt.tiago.neonline.core.model.Player.P1 ->
                    LevelOutcome.Won(starsForMoves(objective, myMoves))
                win != null -> LevelOutcome.Lost(LossReasons.RIVAL_LINE)
                else -> LevelOutcome.Lost(LossReasons.DRAW)
            }

            LevelKind.PUZZLE -> {
                val limit = objective.winInMoves
                when {
                    win?.player != pt.tiago.neonline.core.model.Player.P1 ->
                        if (win != null) LevelOutcome.Lost(LossReasons.RIVAL_LINE)
                        else LevelOutcome.Lost(LossReasons.DRAW)
                    limit != null && myMoves > limit -> LevelOutcome.Lost(LossReasons.OUT_OF_MOVES)
                    else -> LevelOutcome.Won(starsForHints(hintsUsed))
                }
            }

            LevelKind.DEFENSE -> when {
                win?.player == pt.tiago.neonline.core.model.Player.P2 ->
                    LevelOutcome.Lost(LossReasons.RIVAL_LINE)
                win?.player == pt.tiago.neonline.core.model.Player.P1 ->
                    LevelOutcome.Won(3) // won before the defense was over
                else -> if (threatAtEnd) LevelOutcome.Won(2) else LevelOutcome.Won(1)
            }
        }
    }

    /** Duel and shape: three stars inside the certified move count, two inside the slack, else one. */
    fun starsForMoves(objective: LevelObjective, myMoves: Int): Int = when {
        objective.starsThree != null && myMoves <= objective.starsThree -> 3
        objective.starsTwo != null && myMoves <= objective.starsTwo -> 2
        else -> 1
    }

    /** Puzzle: three stars without hints, two with one, one with more. */
    fun starsForHints(hintsUsed: Int): Int = when {
        hintsUsed <= 0 -> 3
        hintsUsed == 1 -> 2
        else -> 1
    }
}
