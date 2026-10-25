package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

/** Snapshot data for presentation; move legality and wins always come from the shared engine. */
data class AccessibleCell(val row: Int, val blocked: Boolean, val hidden: Boolean, val piece: Piece?)
data class AccessibleColumn(
    val column: Int, val occupied: Int, val free: Int, val locked: Boolean,
    val landingRow: Int?, val legalDrop: Boolean, val legalPop: Boolean,
    val winningPlayers: Set<Player>, val cells: List<AccessibleCell>,
)

object BoardAccessibility {
    @JvmOverloads
    fun columns(state: GameState, kind: PieceKind, peekColumn: Int? = null, peekedColumns: Set<Int> = emptySet()): List<AccessibleColumn> {
        val moves = if (state.status == Status.InProgress) GameEngine.validMoves(state) else emptyList()
        val hidesPieces = state.fadeAfter?.let { fade -> state.pieces.any { it != null && state.ply - it.placedAtPly >= fade } } ?: false
        return (0 until state.width).map { column ->
            val cells = (0 until state.height).map { row ->
                val index = state.index(row, column)
                val piece = state.pieces[index]
                val hidden = piece != null && PeekVisibility.isHidden(state, piece) && peekColumn != column
                AccessibleCell(row, !state.playable[index], hidden, piece.takeUnless { hidden })
            }
            val drop = Move.Drop(column, kind) in moves
            val winners = if (hidesPieces || state.status != Status.InProgress) emptySet() else Player.entries.filter { player ->
                val probe = state.copy(toMove = player)
                val result = GameEngine.apply(probe, Move.Drop(column))
                result.events.none { it is GameEvent.MoveRejected } && result.state.status == Status.Won(player)
            }.toSet()
            AccessibleColumn(column,
                (0 until state.height).count { state.pieces[state.index(it, column)] != null },
                (0 until state.height).count { state.isEmpty(state.index(it, column)) },
                state.lockedCol == column && state.lockedFor == state.toMove,
                if (drop) GameEngine.landingRow(state.pieces, state.width, state.height, state.playable, column) else null,
                drop, Move.Pop(column) in moves && PeekVisibility.knowsBottom(state, column, peekedColumns + listOfNotNull(peekColumn)), winners, cells)
        }
    }
    fun canDrop(ui: GameUiState, column: Int): Boolean {
        if (!ui.acceptsInput || column !in 0 until ui.state.width) return false
        val forced = ui.tutorialStep?.forcedCol
        if (forced != null && (forced != column || ui.selectedPiece != PieceKind.NORMAL)) return false
        return Move.Drop(column, ui.selectedPiece) in GameEngine.validMoves(ui.state)
    }
    fun canPop(ui: GameUiState, column: Int): Boolean = ui.acceptsInput && ui.tutorialStep?.forcedCol == null &&
        column in 0 until ui.state.width && PeekVisibility.knowsBottom(ui, column) && Move.Pop(column) in GameEngine.validMoves(ui.state)
}
