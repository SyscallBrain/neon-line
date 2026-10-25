package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.game.BoardActions
import pt.tiago.neonline.game.GameUiState

fun PieceKind.labelResource(): Int = when (this) {
    PieceKind.NORMAL -> R.string.piece_normal
    PieceKind.WILD -> R.string.piece_wild
    PieceKind.BOMB -> R.string.piece_bomb
    PieceKind.LOCK -> R.string.piece_lock
}

@Composable
fun PieceControls(
    ui: GameUiState, initial: GameState,
    onSelect: (PieceKind) -> Unit, onRequestPop: (Int) -> Unit,
    onConfirmPop: () -> Unit, onCancelPop: () -> Unit,
) {
    val player = if (ui.localPlayers) ui.state.toMove else Player.P1
    val inventory = ui.state.inventory[player].orEmpty()
    val granted = initial.inventory[player].orEmpty().filter { it.key != PieceKind.NORMAL && it.value > 0 }.keys
    val controlsEnabled = ui.acceptsInput && ui.tutorialStep?.forcedCol == null
    if (granted.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(PieceKind.NORMAL) + PieceKind.entries.filter { it in granted }).forEach { kind ->
                val count = inventory[kind] ?: 0
                FilterChip(
                    selected = ui.selectedPiece == kind,
                    enabled = controlsEnabled && (kind == PieceKind.NORMAL || count > 0),
                    onClick = { onSelect(kind) },
                    label = { Text(if (kind == PieceKind.NORMAL) stringResource(kind.labelResource()) else stringResource(R.string.piece_count, stringResource(kind.labelResource()), count)) },
                )
            }
        }
    }
    if (ui.state.popOut) {
        val columns = remember(ui.state, ui.peekColumn, ui.peekedColumns) { BoardActions.popColumns(ui.state).filter { pt.tiago.neonline.game.PeekVisibility.knowsBottom(ui, it) } }
        var showMenu by remember { mutableStateOf(false) }
        if (!controlsEnabled) showMenu = false
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.pop_instructions), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 8.dp))
            Box {
                TextButton(onClick = { showMenu = true }, enabled = controlsEnabled && columns.isNotEmpty()) { Text(stringResource(R.string.action_pop)) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    columns.forEach { column ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.column_number, column + 1)) }, onClick = { showMenu = false; onRequestPop(column) })
                    }
                }
            }
        }
    }
    ui.pendingPopColumn?.takeIf { ui.acceptsInput }?.let { column ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onConfirmPop) { Text(stringResource(R.string.pop_confirm, column + 1)) }
            TextButton(onClick = onCancelPop) { Text(stringResource(R.string.action_cancel)) }
        }
    }
    if (ui.hintUnavailable) Text(stringResource(R.string.hint_unavailable), style = MaterialTheme.typography.bodySmall)
    ui.hintMove?.let { move ->
        val text = when (move) {
            is Move.Pop -> stringResource(R.string.hint_pop, move.col + 1)
            is Move.Drop -> stringResource(R.string.hint_drop, stringResource(move.kind.labelResource()), move.col + 1)
            Move.TimeUp -> null
        }
        if (text != null) Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
