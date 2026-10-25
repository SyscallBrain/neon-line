package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

/** UI choices are filtered by the actual engine result, including locked-column rejection. */
object BoardActions {
    fun popColumns(state: GameState): List<Int> = GameEngine.validMoves(state).filterIsInstance<Move.Pop>()
        .filter { GameEngine.apply(state, it).events.none { event -> event is GameEvent.MoveRejected } }.map { it.col }

    fun landingRow(state: GameState, column: Int, kind: PieceKind): Int? =
        GameEngine.apply(state, Move.Drop(column, kind)).events.filterIsInstance<GameEvent.Dropped>().firstOrNull()?.row
}
