package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.solver.SolverValue
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.game.*
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

/** Keyed by request in caller; leaving cancels the exact solver and drops old player facts. */
@Composable
internal fun EndgameReviewContent(input: TacticalReviewRequest, settings: Settings, skin: BoardSkin,
    names: Pair<String, String>?) {
    var windowMode by remember(input) { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!windowMode, onClick = { windowMode = false }, label = { Text(stringResource(R.string.endgame_mode_last)) })
        FilterChip(windowMode, onClick = { windowMode = true }, label = { Text(stringResource(R.string.endgame_mode_mistake)) })
    }
    key(input, windowMode) { EndgameReviewPane(input, settings, skin, names, windowMode) }
}

@Composable
private fun EndgameReviewPane(input: TacticalReviewRequest, settings: Settings, skin: BoardSkin,
    names: Pair<String, String>?, windowMode: Boolean) {
    val completed by produceState<Pair<EndgameReviewResult, EndgameWindowResult?>?>(null, input, windowMode) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            if (windowMode) {
                val window = EndgameReviewAnalyzer.analyzeMistake(input, checkCancelled = { context.ensureActive() })
                EndgameReviewResult(window.status, window.comparison) to window
            } else EndgameReviewAnalyzer.analyze(input, checkCancelled = { context.ensureActive() }) to null
        }
    }
    Text(stringResource(R.string.endgame_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (windowMode) R.string.endgame_window_scope else R.string.endgame_scope,
        EndgameReviewAnalyzer.MAX_EMPTY), style = MaterialTheme.typography.bodySmall)
    val current = completed?.first
    if (current == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.review_loading))
        return
    }
    completed?.second?.let { window ->
        if (window.firstNumber != null && window.lastNumber != null) {
            Text(stringResource(R.string.endgame_window_range, window.firstNumber, window.lastNumber, window.reviewedMoves),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    val c = current.comparison
    if (c == null) {
        Text(stringResource(when (current.status) {
            EndgameReviewStatus.TOO_EARLY -> if (windowMode) R.string.endgame_window_early else R.string.endgame_early
            EndgameReviewStatus.NO_WORSENING -> R.string.endgame_window_none
            EndgameReviewStatus.UNSUPPORTED -> R.string.endgame_unsupported
            EndgameReviewStatus.NO_MOVES -> R.string.endgame_no_moves
            EndgameReviewStatus.INVALID_JOURNAL -> R.string.review_invalid
            else -> R.string.review_limited
        }))
        return
    }
    val subject = names?.let { if (c.player == Player.P1) it.first else it.second }
        ?: stringResource(R.string.local_player, if (c.player == Player.P1) 1 else 2)
    Text(stringResource(if (windowMode) R.string.endgame_window_found else R.string.endgame_last_move,
        subject, c.number, reviewMoveLabel(c.played, c.playedColumn)))
    Text(stringResource(R.string.endgame_best, reviewMoveLabel(c.alternative, c.alternative.col)))
    var scene by remember(c) { mutableIntStateOf(0) }
    var readBoard by remember(c) { mutableStateOf(false) }
    var replay by remember(c) { mutableStateOf(false) }
    if (replay) {
        ReviewReplayContent(input, c, settings, skin, names)
        TextButton(onClick = { replay = false }) { Text(stringResource(R.string.review_replay_close)) }
        return
    }
    TextButton(onClick = { replay = true }) { Text(stringResource(R.string.review_replay)) }
    val titles = listOf(stringResource(if (windowMode) R.string.endgame_window_before else R.string.endgame_before), stringResource(R.string.endgame_after), stringResource(R.string.endgame_alternative))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        titles.forEachIndexed { index, title -> FilterChip(scene == index, onClick = { scene = index }, label = { Text(title) }) }
    }
    val state = when (scene) { 1 -> c.playedAfter; 2 -> c.alternativeAfter; else -> c.before }
    val value = if (scene == 1) c.afterValue else c.beforeValue
    val label = stringResource(when (value) { SolverValue.WIN -> R.string.endgame_win; SolverValue.LOSS -> R.string.endgame_loss; SolverValue.DRAW -> R.string.endgame_draw })
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(titles[scene], style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.endgame_value, subject, label), style = MaterialTheme.typography.titleMedium)
    }
    BoardCanvas(GameUiState(state, paused = true, motionEnabled = false), skin, settings.symbolsOnPieces,
        inputEnabled = false, showPreview = false, colorPalette = settings.colorPalette, turnDescription = titles[scene],
        previewColumn = null, onColumnTap = {}, onColumnDrag = {}, modifier = Modifier.fillMaxWidth().height(300.dp))
    TextButton(onClick = { readBoard = !readBoard }) {
        Text(stringResource(if (readBoard) R.string.review_hide_readout else R.string.review_read_board))
    }
    if (readBoard) ReviewBoardReadout(state, names)
}
