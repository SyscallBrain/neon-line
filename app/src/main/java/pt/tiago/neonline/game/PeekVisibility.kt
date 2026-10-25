package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*

/** Temporary observation never alters the state passed to the engine or AI. */
object PeekVisibility {
    const val MAX_COUNT = Int.MAX_VALUE - 1
    const val DURATION_MS = 600L
    fun isHidden(state: GameState, piece: Piece): Boolean = state.fadeAfter?.let { state.ply - piece.placedAtPly >= it } == true
    fun activeColumn(ui: GameUiState): Int? = ui.peekColumn?.takeIf { ui.acceptsInput && ui.state.fadeAfter != null && it in 0 until ui.state.width }
    fun canPeek(ui: GameUiState, column: Int): Boolean = ui.acceptsInput && !ui.localPlayers && ui.state.fadeAfter != null &&
        column in 0 until ui.state.width && activeColumn(ui) != column &&
        (0 until ui.state.height).any { row -> ui.state.pieces[ui.state.index(row, column)]?.let { isHidden(ui.state, it) } == true }
    fun knowsBottom(state: GameState, column: Int, observed: Set<Int>): Boolean = column in 0 until state.width &&
        (state.pieces[state.index(state.height - 1, column)]?.let { !isHidden(state, it) || column in observed } ?: true)
    fun knowsBottom(ui: GameUiState, column: Int): Boolean = knowsBottom(ui.state, column, ui.peekedColumns + listOfNotNull(activeColumn(ui)))
    fun alpha(ui: GameUiState, piece: Piece, column: Int, baseAlpha: Float): Float =
        if (activeColumn(ui) == column && isHidden(ui.state, piece)) 1f else baseAlpha
}
