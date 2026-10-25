package pt.tiago.neonline.core.model

/** A player action. The clock is never inside the engine: the app sends [TimeUp] when it runs out. */
sealed interface Move {
    /** Drop a piece into [col]; special kinds must be in the player's inventory. */
    data class Drop(val col: Int, val kind: PieceKind = PieceKind.NORMAL) : Move

    /** Pop Out rule: remove the bottom-most own normal piece of a column. */
    data class Pop(val col: Int) : Move

    /** Per-move clock expired. The engine plays a valid column from the auxiliary stream. */
    data object TimeUp : Move
}

/** The result of one move: the new state, the events that explain it, and how the match ended (if it did). */
data class MoveResult(
    val state: GameState,
    val events: List<GameEvent>,
    val outcome: MatchOutcome?,
)
