package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.game.GameUiState
import pt.tiago.neonline.game.BoardAccessibility
import pt.tiago.neonline.game.RotationPreviews
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

/** No callbacks to the match: inspecting a projection cannot pause, play or save it. */
@Composable
internal fun RotationPreviewControls(ui: GameUiState, settings: Settings, skin: BoardSkin) {
    if (!RotationPreviews.due(ui.state) || ui.finished || ui.animating) return
    val moves = remember(ui.state, ui.acceptsInput, ui.selectedPiece, ui.tutorialStep) { RotationPreviews.moves(ui) }
    // A real move, timeout, pause or piece selection discards the open inspection.
    key(ui.state, ui.acceptsInput, ui.selectedPiece, settings.showPreview) {
        var open by remember { mutableStateOf(false) }
        Column {
            Text(stringResource(if (ui.state.engineVersion >= GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION)
                R.string.rotation_next_clockwise else R.string.rotation_next_counterclockwise), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (settings.showPreview && moves.isNotEmpty()) {
                TextButton(onClick = { open = true }) { Text(stringResource(R.string.rotation_preview)) }
                if (open) RotationPreviewDialog(ui, moves, settings, skin) { open = false }
            }
        }
    }
}

@Composable
private fun RotationPreviewDialog(ui: GameUiState, moves: List<Move>, settings: Settings, skin: BoardSkin, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<Move>(ui.pendingDrop?.takeIf { it in moves }
        ?: ui.pendingPopColumn?.let { Move.Pop(it) }?.takeIf { it in moves } ?: moves.first()) }
    val result = remember(ui.state, selected) { RotationPreviews.project(ui, selected) } ?: return
    val projected = result.state
    var readColumn by remember(selected) { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(R.string.rotation_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rotation_preview_note))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    moves.forEach { move ->
                        val label = when (move) {
                            is Move.Drop -> stringResource(R.string.rotation_drop, move.col + 1, stringResource(move.kind.labelResource()))
                            is Move.Pop -> stringResource(R.string.rotation_pop, move.col + 1)
                            Move.TimeUp -> return@forEach
                        }
                        FilterChip(selected == move, { selected = move }, label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.rotation_result_size, projected.width, projected.height), style = MaterialTheme.typography.labelLarge)
                BoardCanvas(GameUiState(projected, motionEnabled = false),
                    skin, settings.symbolsOnPieces, showPreview = false, inputEnabled = false,
                    turnDescription = stringResource(R.string.rotation_preview_board), colorPalette = settings.colorPalette,
                    previewColumn = null, onColumnTap = {}, onColumnDrag = {},
                    modifier = Modifier.fillMaxWidth().height(220.dp))
                Text(stringResource(R.string.rotation_read_help), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until projected.width) FilterChip(readColumn == col, { readColumn = col },
                        label = { Text(stringResource(R.string.rotation_read_column, col + 1)) })
                }
                val cells = remember(projected, readColumn) { BoardAccessibility.columns(projected, PieceKind.NORMAL)[readColumn].cells }
                val rows = cells.map { cell ->
                    val piece = cell.piece
                    val detail = when {
                        cell.blocked -> stringResource(R.string.a11y_cell_blocked)
                        cell.hidden -> stringResource(R.string.a11y_cell_hidden)
                        piece == null -> stringResource(R.string.a11y_cell_empty)
                        piece.kind == PieceKind.WILD -> stringResource(R.string.piece_wild)
                        else -> stringResource(R.string.a11y_cell_piece, if (piece.owner == Player.P1) 1 else 2, stringResource(piece.kind.labelResource()))
                    }
                    stringResource(R.string.a11y_row, cell.row + 1, detail)
                }.joinToString(" ")
                Text(rows, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.rotation_close)) } })
}
