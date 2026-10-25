package pt.tiago.neonline.core.model

/** Everything the UI animates, in order. Cells are row-major indices. */
sealed interface GameEvent {
    data class Dropped(val col: Int, val row: Int, val piece: Piece, val fromRow: Int) : GameEvent
    data class Popped(val col: Int, val piece: Piece) : GameEvent
    data class Shifted(val col: Int, val fromRows: List<Int>, val toRows: List<Int>) : GameEvent
    data class Rotated(val orientation: Int) : GameEvent
    data class Fell(val from: Int, val to: Int) : GameEvent
    data class BombExploded(val center: Int, val cleared: List<Int>) : GameEvent
    data class ColumnLocked(val col: Int, val forPlayer: Player) : GameEvent
    data class ColumnUnlocked(val col: Int) : GameEvent
    data class ThreatCreated(val cell: Int, val player: Player) : GameEvent
    data class LineFormed(val line: Line) : GameEvent
    data class TimeUpPlayed(val col: Int) : GameEvent
    data class MoveRejected(val reason: String) : GameEvent
    data class TurnChanged(val player: Player) : GameEvent
    data class MatchOver(val outcome: MatchOutcome) : GameEvent
}

/** Reasons a move can be rejected. */
object Rejections {
    const val GAME_OVER = "game_over"
    const val OUT_OF_RANGE = "out_of_range"
    const val COLUMN_FULL = "column_full"
    const val COLUMN_LOCKED = "column_locked"
    const val NOT_PLAYABLE = "not_playable"
    const val NO_POP_RULE = "no_pop_rule"
    const val NOT_YOUR_PIECE = "not_your_piece"
    const val NOT_NORMAL_PIECE = "not_normal_piece"
    const val NOTHING_TO_POP = "nothing_to_pop"
    const val NO_SUCH_PIECE = "no_such_piece"
}
