package pt.tiago.neonline.ui.board

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import pt.tiago.neonline.ui.theme.TubeBlue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.game.BoardAccessibility
import pt.tiago.neonline.game.GameUiState
import pt.tiago.neonline.game.PeekVisibility
import pt.tiago.neonline.game.RivalThreatCue
import pt.tiago.neonline.ui.screens.labelResource

/** Semantic targets have no pointer handlers, leaving tap/drag/pop gestures on the Canvas. */
@Composable
internal fun AccessibleColumns(ui: GameUiState, onDrop: (Int) -> Unit, onPop: (Int) -> Unit, onPeek: (Int) -> Unit,
    rivalThreats: List<RivalThreatCue> = emptyList()) {
    val latestUi by rememberUpdatedState(ui)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestPeek by rememberUpdatedState(onPeek)
    val latestPop by rememberUpdatedState(onPop)
    val activePeek = PeekVisibility.activeColumn(ui)
    val columns = remember(ui.state, ui.selectedPiece, activePeek, ui.peekedColumns) {
        BoardAccessibility.columns(ui.state, ui.selectedPiece, activePeek, ui.peekedColumns)
    }
    val peekLabel = stringResource(R.string.peek_read_column)
    var focused by remember { mutableStateOf<Int?>(null) }
    var reading by remember { mutableStateOf<Int?>(null) }
    val dropLabel = stringResource(R.string.a11y_drop, stringResource(ui.selectedPiece.labelResource()))
    val popLabel = stringResource(R.string.action_pop)
    val readLabel = stringResource(R.string.a11y_read_column)
    val collapseLabel = stringResource(R.string.a11y_collapse_column)
    val winningLabel = stringResource(R.string.a11y_winning_drop)
    val otherThreatLabel = stringResource(R.string.a11y_other_threat)
    val status = stringResource(when {
        ui.paused -> R.string.pause_title
        ui.animating -> R.string.turn_animating
        ui.finished -> R.string.turn_over
        ui.rivalThinking || (!ui.localPlayers && ui.state.toMove == Player.P2) -> R.string.board_rival_turn
        else -> R.string.a11y_ready
    })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell = minOf(maxWidth / (ui.state.width + .6f), maxHeight / (ui.state.height + .6f))
        val width = cell * ui.state.width
        val height = cell * ui.state.height
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.offset((maxWidth - width) / 2, (maxHeight - height) / 2).width(width).height(height)) {
            for (column in columns) {
                val availableDrop = BoardAccessibility.canDrop(ui, column.column)
                val availablePop = BoardAccessibility.canPop(ui, column.column)
                val columnStatus = when {
                    ui.pendingDrop?.col == column.column -> stringResource(R.string.a11y_confirm_drop)
                    !availableDrop && !availablePop && ui.acceptsInput -> stringResource(R.string.a11y_no_move)
                    else -> status
                }
                val base = stringResource(R.string.a11y_column, column.column + 1, column.occupied, column.free)
                val locked = if (column.locked) stringResource(R.string.a11y_locked) else ""
                val landing = column.landingRow?.let { stringResource(R.string.a11y_landing, it + 1) }.orEmpty()
                val detailedThreats = rivalThreats.filter { it.cell % ui.state.width == column.column }.map { rivalThreatText(it) }
                val threats = column.winningPlayers.mapNotNull { player -> when {
                    player == ui.state.toMove -> winningLabel
                    detailedThreats.isEmpty() -> otherThreatLabel
                    else -> null
                } }.joinToString(" ")
                val lines = column.cells.map { cell ->
                    val detail = when {
                        cell.blocked -> stringResource(R.string.a11y_cell_blocked)
                        cell.hidden -> stringResource(R.string.a11y_cell_hidden)
                        cell.piece == null -> stringResource(R.string.a11y_cell_empty)
                        cell.piece.kind == PieceKind.WILD -> stringResource(R.string.piece_wild)
                        else -> stringResource(R.string.a11y_cell_piece, if (cell.piece.owner == Player.P1) 1 else 2, stringResource(cell.piece.kind.labelResource()))
                    }
                    stringResource(R.string.a11y_row, cell.row + 1, detail)
                }
                val description = (listOf(base, locked, landing, threats) + detailedThreats + if (reading == column.column || activePeek == column.column) lines else emptyList())
                    .filter { it.isNotBlank() }.joinToString(" ")
                fun drop(): Boolean {
                    if (!BoardAccessibility.canDrop(latestUi, column.column)) return false
                    latestDrop(column.column); return true
                }
                Box(Modifier.weight(1f).fillMaxHeight()
                    .then(if (focused == column.column) Modifier.border(2.dp, TubeBlue) else Modifier)
                    .onFocusChanged { focus -> if (focus.isFocused) focused = column.column else if (focused == column.column) focused = null }
                    .onKeyEvent { event ->
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) {
                            if (event.type == KeyEventType.KeyUp) drop() else true
                        } else false
                    }
                    .focusable()
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                        stateDescription = columnStatus
                        if (!availableDrop && !availablePop && !ui.acceptsInput) disabled()
                        if (reading == column.column || activePeek == column.column || ui.pendingDrop?.col == column.column) liveRegion = LiveRegionMode.Polite
                        if (availableDrop) onClick(label = dropLabel) { drop() }
                        customActions = buildList {
                            if (PeekVisibility.canPeek(ui, column.column)) add(CustomAccessibilityAction(peekLabel) {
                                if (PeekVisibility.canPeek(latestUi, column.column)) { latestPeek(column.column); true } else false
                            })
                            if (!ui.animating) add(CustomAccessibilityAction(if (reading == column.column) collapseLabel else readLabel) { reading = if (reading == column.column) null else column.column; true })
                            if (availablePop) add(CustomAccessibilityAction(popLabel) {
                                if (BoardAccessibility.canPop(latestUi, column.column)) { latestPop(column.column); true } else false
                            })
                        }
                    })
            }
        }
        }
    }
}
