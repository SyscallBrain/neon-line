package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*

enum class MoveNoticeAction { DROP, POP, TIMEOUT }
enum class MoveNoticeDirection { HORIZONTAL, VERTICAL, DIAGONAL }
data class MoveNoticeWin(val player: Player, val directions: Set<MoveNoticeDirection>)
data class BoardMoveNotice(val player: Player, val column: Int, val kind: PieceKind,
    val action: MoveNoticeAction, val rotated: Boolean, val win: MoveNoticeWin?, val threatColumns: List<Int>)

/** Facts for the live turn label, never a queued history or a mutable handle to the match. */
object BoardMoveNotices {
    fun snapshot(ui: GameUiState): BoardMoveNotice? {
        if (ui.paused || ui.animating || ui.events.any { it is GameEvent.MoveRejected }) return null
        val drop = ui.events.filterIsInstance<GameEvent.Dropped>().firstOrNull()
        val pop = ui.events.filterIsInstance<GameEvent.Popped>().firstOrNull()
        val timeout = ui.events.filterIsInstance<GameEvent.TimeUpPlayed>().firstOrNull()
        val piece = drop?.piece ?: pop?.piece ?: return null
        val column = timeout?.col ?: drop?.col ?: requireNotNull(pop).col
        val action = when { timeout != null -> MoveNoticeAction.TIMEOUT; pop != null -> MoveNoticeAction.POP; else -> MoveNoticeAction.DROP }
        val state = ui.state
        val winner = (state.status as? Status.Won)?.player
        val directions = ui.events.filterIsInstance<GameEvent.LineFormed>().mapNotNull { event ->
            val line = event.line
            if (line.player != winner || line.cells.size < 2 || line.cells.any { index ->
                index !in state.pieces.indices || state.pieces[index]?.let { PeekVisibility.isHidden(state, it) } != false
            }) return@mapNotNull null
            when {
                line.cells.map { it / state.width }.distinct().size == 1 -> MoveNoticeDirection.HORIZONTAL
                line.cells.map { it % state.width }.distinct().size == 1 -> MoveNoticeDirection.VERTICAL
                else -> MoveNoticeDirection.DIAGONAL
            }
        }.toSet()
        val threats = if (ui.finished || (!ui.localPlayers && piece.owner != Player.P2)) emptyList() else
            BoardAccessibility.columns(state, PieceKind.NORMAL).filter { piece.owner in it.winningPlayers }.map { it.column }
        return BoardMoveNotice(piece.owner, column, piece.kind, action,
            ui.events.any { it is GameEvent.Rotated },
            winner?.takeIf { directions.isNotEmpty() }?.let { MoveNoticeWin(it, directions) }, threats)
    }
}
