package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.data.LocalConfig
import pt.tiago.neonline.game.*
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas

/** A cancellable, read-only lesson. Returning to the result never changes the finished match. */
@Composable
internal fun TacticalReviewDialog(input: TacticalReviewInput, settings: Settings, skin: BoardSkin,
    bossDuel: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    ReviewDialog(input, settings, skin, bossDuel, onBack, onRetry)
}

@Composable
internal fun LocalTacticalReviewDialog(input: LocalTacticalReviewInput, settings: Settings, skin: BoardSkin,
    onPlayerChange: (Player) -> Unit, onBack: () -> Unit, onNextRound: () -> Unit) {
    ReviewDialog(input, settings, skin, false, onBack, onNextRound, onPlayerChange)
}

@Composable
private fun ReviewDialog(input: TacticalReviewRequest, settings: Settings, skin: BoardSkin,
    bossDuel: Boolean, onBack: () -> Unit, onRetry: () -> Unit, onPlayerChange: (Player) -> Unit = {}) {
    var endgame by remember(input) { mutableStateOf(false) }
    val completed by produceState<Pair<TacticalReviewRequest, TacticalReviewResult>?>(null, input) {
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val immediate = when (input) {
                is TacticalReviewInput -> TacticalReviewAnalyzer.analyze(input, checkCancelled = { context.ensureActive() })
                is LocalTacticalReviewInput -> TacticalReviewAnalyzer.analyzeLocal(input, checkCancelled = { context.ensureActive() })
            }
            if (immediate.status == TacticalReviewStatus.NO_IMMEDIATE_MISTAKE)
                TacticalForkReviewAnalyzer.analyze(input, checkCancelled = { context.ensureActive() }) else immediate
        }
        value = input to result
    }
    // A new selection must never show the previous player's facts while its worker is starting.
    val result = completed?.takeIf { it.first == input }?.second
    val names = if (input is LocalTacticalReviewInput)
        localReviewName(input.saved.config, Player.P1) to localReviewName(input.saved.config, Player.P2) else null
    val player = (input as? LocalTacticalReviewInput)?.player ?: Player.P1
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.review_back)) }
                Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.review_scope), style = MaterialTheme.typography.bodySmall)
                if (input is TacticalReviewInput && input.puzzleLimit != null)
                    Text(stringResource(R.string.review_puzzle_scope), style = MaterialTheme.typography.bodySmall)
                if (bossDuel) Text(stringResource(R.string.review_boss_scope), style = MaterialTheme.typography.bodySmall)
                if (names != null) {
                    Text(stringResource(R.string.review_local_subject, if (player == Player.P1) names.first else names.second))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Player.P1 to names.first, Player.P2 to names.second).forEach { (choice, name) ->
                            FilterChip(player == choice, onClick = { onPlayerChange(choice) }, label = { Text(name) })
                        }
                    }
                }
                if (endgame) {
                    key(input) { EndgameReviewContent(input, settings, skin, names) }
                    TextButton(onClick = { endgame = false }) { Text(stringResource(R.string.endgame_back)) }
                } else {
                    OutlinedButton(onClick = { endgame = true }, enabled = result != null) { Text(stringResource(R.string.endgame_action)) }
                    val current = result
                    if (current == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.review_loading))
                    } else if (current.finding != null) {
                        key(input, current.finding) { TacticalFindingContent(input, current.finding, settings, skin, names, player) }
                    } else {
                        Text(stringResource(when (current.status) {
                            TacticalReviewStatus.FADING_BOARD -> R.string.review_fading
                            TacticalReviewStatus.INVALID_JOURNAL -> R.string.review_invalid
                            TacticalReviewStatus.LIMIT_REACHED -> R.string.review_limited
                            else -> R.string.review_none
                        }))
                    }
                }
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (input is LocalTacticalReviewInput) R.string.local_next else if (bossDuel) R.string.boss_restart_series else R.string.action_retry))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TacticalFindingContent(input: TacticalReviewRequest, finding: TacticalFinding, settings: Settings, skin: BoardSkin,
    names: Pair<String, String>?, player: Player) {
    val subject = names?.let { if (player == Player.P1) it.first else it.second }
    val opponent = names?.let { if (player == Player.P1) it.second else it.first }
    val afterPlayedLabel = if (subject == null) stringResource(R.string.review_after_played)
        else stringResource(R.string.review_local_after, subject)
    var lesson by remember { mutableStateOf(false) }
    if (lesson) {
        ForkLessonContent(input, finding, settings, skin, names)
        TextButton(onClick = { lesson = false }) { Text(stringResource(R.string.review_replay_close)) }
        return
    }
    if (finding.reason == TacticalReason.ALLOWED_DOUBLE_THREAT)
        TextButton(onClick = { lesson = true }) { Text(stringResource(R.string.fork_lesson_action)) }
    var replay by remember { mutableStateOf(false) }
    if (replay) {
        ReviewReplayContent(input, finding, settings, skin, names)
        TextButton(onClick = { replay = false }) { Text(stringResource(R.string.review_replay_close)) }
        return
    }
    TextButton(onClick = { replay = true }) { Text(stringResource(R.string.review_replay)) }
    var scene by remember(finding) { mutableIntStateOf(0) }
    var readBoard by remember(finding) { mutableStateOf(false) }
    val played = reviewMoveLabel(finding.played, finding.playedColumn)
    val alternative = reviewMoveLabel(finding.alternative, finding.alternativeColumn)
    Text(if (subject == null) stringResource(R.string.review_played, finding.humanMove, played)
        else stringResource(R.string.review_local_played, finding.humanMove, subject, played), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.review_suggestion, alternative))
    Text(when (finding.reason) {
        TacticalReason.MISSED_WIN -> stringResource(R.string.review_missed_win, alternative)
        TacticalReason.ALLOWED_WIN -> if (opponent == null) stringResource(R.string.review_allowed_win, requireNotNull(finding.rivalWinningColumn) + 1, alternative)
            else stringResource(R.string.review_local_allowed_win, opponent, requireNotNull(finding.rivalWinningColumn) + 1, alternative)
        TacticalReason.GAVE_WIN -> if (opponent == null) stringResource(R.string.review_gave_win, alternative)
            else stringResource(R.string.review_local_gave_win, opponent, alternative)
        TacticalReason.ALLOWED_DOUBLE_THREAT -> if (opponent == null)
            stringResource(R.string.review_double_threat, requireNotNull(finding.rivalWinningColumn) + 1, alternative)
            else stringResource(R.string.review_local_double_threat, opponent, requireNotNull(finding.rivalWinningColumn) + 1, alternative)
    })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(stringResource(R.string.review_before), afterPlayedLabel, stringResource(R.string.review_after_alternative)).forEachIndexed { index, label ->
            FilterChip(scene == index, onClick = { scene = index }, label = { Text(label) })
        }
    }
    val state = when (scene) { 1 -> finding.playedAfter; 2 -> finding.alternativeAfter; else -> finding.before }
    val title = when (scene) { 1 -> afterPlayedLabel; 2 -> stringResource(R.string.review_after_alternative); else -> stringResource(R.string.review_before) }
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (scene == 0) Text(stringResource(R.string.review_outline_note), style = MaterialTheme.typography.bodySmall)
    BoardCanvas(GameUiState(state, paused = true, motionEnabled = false), skin, settings.symbolsOnPieces,
        inputEnabled = false, showPreview = false, colorPalette = settings.colorPalette, turnDescription = title,
        previewColumn = null, onColumnTap = {}, onColumnDrag = {},
        reviewPlayedColumn = finding.playedColumn.takeIf { scene == 0 },
        reviewSuggestedColumn = finding.alternativeColumn.takeIf { scene == 0 },
        modifier = Modifier.fillMaxWidth().height(300.dp))
    TextButton(onClick = { readBoard = !readBoard }) {
        Text(stringResource(if (readBoard) R.string.review_hide_readout else R.string.review_read_board))
    }
    if (readBoard) ReviewBoardReadout(state, names)
}

@Composable
internal fun reviewMoveLabel(move: Move, column: Int?): String = when (move) {
    is Move.Drop -> stringResource(R.string.review_drop, stringResource(move.kind.labelResource()), move.col + 1)
    is Move.Pop -> stringResource(R.string.review_pop, move.col + 1)
    Move.TimeUp -> stringResource(R.string.review_timeout, requireNotNull(column) + 1)
}

@Composable
internal fun ReviewBoardReadout(state: GameState, names: Pair<String, String>?) {
    val columns = remember(state) { BoardAccessibility.columns(state, PieceKind.NORMAL) }
    columns.forEach { column ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.a11y_column, column.column + 1, column.occupied, column.free), style = MaterialTheme.typography.titleSmall)
            column.cells.forEach { cell ->
                val detail = when {
                    cell.blocked -> stringResource(R.string.a11y_cell_blocked)
                    cell.hidden -> stringResource(R.string.a11y_cell_hidden)
                    cell.piece == null -> stringResource(R.string.a11y_cell_empty)
                    names != null -> stringResource(R.string.review_local_cell_piece,
                        if (cell.piece.owner == Player.P1) names.first else names.second, stringResource(cell.piece.kind.labelResource()))
                    cell.piece.kind == PieceKind.WILD -> stringResource(R.string.piece_wild)
                    else -> stringResource(R.string.a11y_cell_piece, if (cell.piece.owner == Player.P1) 1 else 2, stringResource(cell.piece.kind.labelResource()))
                }
                Text(stringResource(R.string.a11y_row, cell.row + 1, detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun localReviewName(config: LocalConfig, player: Player): String {
    val number = if (player == Player.P1) 1 else 2
    val name = if (player == Player.P1) config.player1 else config.player2
    return if (name.isBlank()) stringResource(R.string.local_player, number)
        else stringResource(R.string.review_local_player, number, name)
}
