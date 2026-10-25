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
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.BoardThemes
import pt.tiago.neonline.game.GameUiState
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

fun BoardSkin.titleResource(): Int = when (this) {
    BoardSkin.NEON_ROAD -> R.string.theme_neon
    BoardSkin.SYNTHWAVE -> R.string.theme_synthwave
    BoardSkin.CIRCUIT -> R.string.theme_circuit
    BoardSkin.AURORA -> R.string.theme_aurora
}

@Composable
fun BoardThemePicker(selected: BoardSkin, stars: Int, symbols: Boolean, onSelect: (BoardSkin) -> Unit, colorPalette: String = "normal") {
    var preview by remember(selected) { mutableStateOf(selected) }
    val state = remember {
        val pieces = MutableList<Piece?>(42) { null }
        listOf(0 to Player.P1, 1 to Player.P2, 2 to Player.P1, 3 to Player.P2, 4 to Player.P1, 5 to Player.P2).forEach { (col, player) ->
            pieces[35 + col] = Piece(player, PieceKind.NORMAL, 0)
        }
        pieces[30] = Piece(Player.P1, PieceKind.LOCK, 0)
        pieces[31] = Piece(Player.P1, PieceKind.WILD, 0)
        GameUiState(GameState.initial("theme-preview", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef", 7, 6, 4,
            List(42) { true }, pieces), paused = true, motionEnabled = false)
    }
    Text(stringResource(R.string.theme_total_stars, stars), style = MaterialTheme.typography.bodySmall)
    // All themes stay visible: the chips wrap instead of hiding behind a sideways scroll.
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardSkin.entries.forEach { skin -> FilterChip(preview == skin, { preview = skin }, label = { Text(stringResource(skin.titleResource())) }) }
    }
    BoardCanvas(state, preview, symbols, colorPalette = colorPalette, inputEnabled = false, turnDescription = stringResource(R.string.theme_preview_note),
        previewColumn = null, onColumnTap = {}, onColumnDrag = {}, modifier = Modifier.fillMaxWidth().height(210.dp))
    Text(stringResource(R.string.theme_preview_note), style = MaterialTheme.typography.bodySmall)
    val unlocked = BoardThemes.isUnlocked(preview, stars)
    if (!unlocked) Text(stringResource(R.string.theme_locked, preview.requiredStars, (preview.requiredStars - stars).coerceAtLeast(0)))
    // The theme in use needs no button; the line below already says it is active.
    if (selected != preview) Button(onClick = { onSelect(preview) }, enabled = unlocked, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (unlocked) R.string.theme_apply else R.string.theme_unavailable))
    }
    Text(stringResource(R.string.theme_active, stringResource(selected.titleResource())), style = MaterialTheme.typography.bodySmall)
}
