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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.game.*
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

/** Mounted only while replay is open; leaving or changing perspective cancels its work. */
@Composable
internal fun ReviewReplayContent(input: TacticalReviewRequest, finding: TacticalFinding,
    settings: Settings, skin: BoardSkin, names: Pair<String, String>?) {
    key(input, finding) {
        RecordedReplayContent(input, finding, settings, skin, names) { cancel -> TacticalReviewReplay.build(input, finding, cancel) }
    }
}

@Composable
internal fun ReviewReplayContent(input: TacticalReviewRequest, comparison: EndgameComparison,
    settings: Settings, skin: BoardSkin, names: Pair<String, String>?) {
    key(input, comparison) {
        RecordedReplayContent(input, comparison, settings, skin, names) { cancel -> TacticalReviewReplay.buildEndgame(input, comparison, cancel) }
    }
}

@Composable
private fun RecordedReplayContent(input: TacticalReviewRequest, anchor: Any, settings: Settings,
    skin: BoardSkin, names: Pair<String, String>?, load: (() -> Unit) -> ReviewReplayWindow?) {
    val loaded by produceState<Pair<Boolean, ReviewReplayWindow?>>(false to null, input, anchor) {
        value = true to withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            load { context.ensureActive() }
        }
    }
    // Wrapper keys this component by request/anchor: old frames and playback are disposed.
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var playing by remember { mutableStateOf(!settings.reducedMotion) }
    var index by remember { mutableIntStateOf(0) }
    var readBoard by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; playing = false }
            if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings.reducedMotion) { if (settings.reducedMotion) playing = false }
    val current = loaded.second
    LaunchedEffect(current, playing, index, foreground) {
        if (current != null && playing && foreground) {
            if (index >= current.frames.lastIndex) playing = false
            else { delay(900); index++ }
        }
    }
    Text(stringResource(R.string.review_replay_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.review_replay_scope), style = MaterialTheme.typography.bodySmall)
    if (current == null) {
        if (!loaded.first) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.review_loading))
        } else Text(stringResource(R.string.review_invalid))
        return
    }
    val frame = current.frames[index]
    val actor = frame.player?.let { player ->
        names?.let { if (player == Player.P1) it.first else it.second }
            ?: stringResource(R.string.local_player, if (player == Player.P1) 1 else 2)
    }
    val title = if (frame.move == null) stringResource(R.string.review_replay_start)
        else stringResource(R.string.review_replay_action, frame.moveNumber, requireNotNull(actor), reviewMoveLabel(frame.move, frame.column))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.review_replay_position, index + 1, current.frames.size))
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (index == current.decisiveIndex) Text(stringResource(R.string.review_replay_decisive))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { playing = false; index-- }, enabled = index > 0) { Text(stringResource(R.string.review_replay_previous)) }
        Button(onClick = {
            if (!playing && index == current.frames.lastIndex) index = 0
            playing = !playing
        }) { Text(stringResource(if (playing) R.string.review_replay_pause else R.string.review_replay_play)) }
        OutlinedButton(onClick = { playing = false; index++ }, enabled = index < current.frames.lastIndex) { Text(stringResource(R.string.review_replay_next)) }
    }
    BoardCanvas(GameUiState(frame.state, paused = true, motionEnabled = false), skin, settings.symbolsOnPieces,
        inputEnabled = false, showPreview = false, colorPalette = settings.colorPalette, turnDescription = title,
        previewColumn = null, onColumnTap = {}, onColumnDrag = {},
        modifier = Modifier.fillMaxWidth().height(300.dp))
    TextButton(onClick = { readBoard = !readBoard }) {
        Text(stringResource(if (readBoard) R.string.review_hide_readout else R.string.review_read_board))
    }
    if (readBoard) ReviewBoardReadout(frame.state, names)
}
