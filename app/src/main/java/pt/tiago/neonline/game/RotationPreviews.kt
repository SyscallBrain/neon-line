package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

/** Read-only one-turn projections, using exactly the same engine as a committed move. */
object RotationPreviews {
    fun due(state: GameState): Boolean {
        val every = state.rotateEvery ?: return false
        return state.status == Status.InProgress && every > 0 && state.ply % every == every - 1
    }

    fun hasHiddenPieces(state: GameState): Boolean = state.pieces.any { it != null && PeekVisibility.isHidden(state, it) }

    fun moves(ui: GameUiState): List<Move> {
        if (!due(ui.state) || !ui.acceptsInput || hasHiddenPieces(ui.state)) return emptyList()
        return GameEngine.validMoves(ui.state).filter { move -> when (move) {
            is Move.Drop -> move.kind == ui.selectedPiece && BoardAccessibility.canDrop(ui, move.col)
            is Move.Pop -> BoardAccessibility.canPop(ui, move.col)
            Move.TimeUp -> false
        } }
    }

    fun project(ui: GameUiState, move: Move): MoveResult? {
        if (move !in moves(ui)) return null
        val result = GameEngine.apply(ui.state, move)
        return result.takeIf { it.events.any { event -> event is GameEvent.Rotated } &&
            it.events.none { event -> event is GameEvent.MoveRejected } }
    }
}
