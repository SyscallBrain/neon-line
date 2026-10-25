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
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.game.*
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

/** Caller keys by request/finding; closing or changing perspective disposes work and choices. */
@Composable
internal fun ForkLessonContent(input: TacticalReviewRequest, finding: TacticalFinding,
    settings: Settings, skin: BoardSkin, names: Pair<String, String>?) {
    val result by produceState<ForkDemonstrationResult?>(null, input, finding) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            ForkDemonstration.build(input, finding, checkCancelled = { context.ensureActive() })
        }
    }
    Text(stringResource(R.string.fork_lesson_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.fork_lesson_scope), style = MaterialTheme.typography.bodySmall)
    val current = result
    if (current == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.review_loading))
        return
    }
    val lesson = current.lesson
    if (lesson == null) {
        Text(stringResource(if (current.status == ForkDemonstrationStatus.LIMIT_REACHED) R.string.review_limited else R.string.fork_lesson_unavailable))
        return
    }
    val player = names?.let { if (lesson.player == Player.P1) it.first else it.second }
        ?: stringResource(R.string.local_player, if (lesson.player == Player.P1) 1 else 2)
    val opponent = names?.let { if (lesson.opponent == Player.P1) it.first else it.second }
        ?: stringResource(R.string.local_player, if (lesson.opponent == Player.P1) 1 else 2)
    var selected by remember(lesson) { mutableIntStateOf(0) }
    var scene by remember(lesson) { mutableIntStateOf(0) }
    var readBoard by remember(lesson) { mutableStateOf(false) }
    val defense = lesson.defenses[selected]
    Text(stringResource(R.string.fork_lesson_created, opponent, reviewMoveLabel(lesson.creatingMove, lesson.creatingMove.col),
        lesson.winningColumns.joinToString(", ") { (it + 1).toString() }))
    Text(stringResource(R.string.fork_lesson_choose, player))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lesson.defenses.forEachIndexed { index, choice ->
            FilterChip(selected == index, onClick = { selected = index; scene = 1 },
                label = { Text(reviewMoveLabel(choice.move, null)) })
        }
    }
    val titles = listOf(stringResource(R.string.fork_lesson_fork), stringResource(R.string.fork_lesson_defense), stringResource(R.string.fork_lesson_response))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        titles.forEachIndexed { index, title -> FilterChip(scene == index, onClick = { scene = index }, label = { Text(title) }) }
    }
    val title = titles[scene]
    val state = when (scene) { 1 -> defense.after; 2 -> defense.finalState; else -> lesson.forkState }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.fork_lesson_selected, player, reviewMoveLabel(defense.move, null)))
        if (defense.response == null) Text(stringResource(R.string.fork_lesson_already_won, opponent))
        else Text(stringResource(R.string.fork_lesson_winning_reply, opponent, reviewMoveLabel(defense.response, null)))
    }
    BoardCanvas(GameUiState(state, paused = true, motionEnabled = false), skin, settings.symbolsOnPieces,
        inputEnabled = false, showPreview = false, colorPalette = settings.colorPalette, turnDescription = title,
        previewColumn = null, onColumnTap = {}, onColumnDrag = {}, modifier = Modifier.fillMaxWidth().height(300.dp))
    TextButton(onClick = { readBoard = !readBoard }) {
        Text(stringResource(if (readBoard) R.string.review_hide_readout else R.string.review_read_board))
    }
    if (readBoard) ReviewBoardReadout(state, names)
}
