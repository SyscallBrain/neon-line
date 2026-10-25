package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import pt.tiago.neonline.game.AnimationTimeline
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.data.LocalConfig
import pt.tiago.neonline.data.Settings
import pt.tiago.neonline.game.LocalGameViewModel
import pt.tiago.neonline.game.AudioController
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas
import pt.tiago.neonline.ui.theme.Pink
import pt.tiago.neonline.ui.theme.Amber

@Composable
fun LocalSetupScreen(existing: LocalConfig?, canResume: Boolean, onResume: () -> Unit,
                     onStart: (LocalConfig) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var player1 by rememberSaveable { mutableStateOf(existing?.player1.orEmpty()) }
    var player2 by rememberSaveable { mutableStateOf(existing?.player2.orEmpty()) }
    var large by rememberSaveable { mutableStateOf(existing?.width == 9) }
    var five by rememberSaveable { mutableStateOf(existing?.connect == 5) }
    var pop by rememberSaveable { mutableStateOf(existing?.popOut ?: false) }
    var rotation by rememberSaveable { mutableStateOf(existing?.rotation ?: false) }
    var specials by rememberSaveable { mutableStateOf(existing?.specials ?: false) }
    var clock by rememberSaveable { mutableIntStateOf(existing?.clockSeconds ?: 0) }
    var table by rememberSaveable { mutableStateOf(existing?.tableMode ?: false) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    fun start() = onStart(LocalConfig(player1.trim(), player2.trim(), if (large) 9 else 7, if (large) 7 else 6,
        if (five) 5 else 4, pop, rotation, specials, clock, table, version = 2))
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(stringResource(R.string.local_title), onBack)
        Text(stringResource(R.string.local_description))
        if (canResume) OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_resume)) }
        OutlinedTextField(player1, { player1 = it.take(32) }, label = { Text(stringResource(R.string.local_player, 1)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(player2, { player2 = it.take(32) }, label = { Text(stringResource(R.string.local_player, 2)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.local_size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!large, { large = false }, label = { Text(stringResource(R.string.local_dimensions, 7, 6)) })
            FilterChip(large, { large = true; rotation = false }, label = { Text(stringResource(R.string.local_dimensions, 9, 7)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!five, { five = false }, label = { Text(stringResource(R.string.local_connect, 4)) })
            FilterChip(five, { five = true }, label = { Text(stringResource(R.string.local_connect, 5)) })
        }
        LocalToggle(R.string.local_pop, pop) { pop = it }
        LocalToggle(R.string.local_rotation, rotation, enabled = !large) { rotation = it }
        LocalToggle(R.string.local_specials, specials) { specials = it }
        LocalToggle(R.string.local_table, table) { table = it }
        Text(stringResource(R.string.local_clock))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 10, 20).forEach { seconds -> FilterChip(clock == seconds, { clock = seconds }, label = {
                Text(if (seconds == 0) stringResource(R.string.local_clock_off) else stringResource(R.string.local_seconds, seconds))
            }) }
        }
        Text(stringResource(R.string.local_rules_note), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { if (existing != null) confirmNew = true else start() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_start)) }
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false },
        title = { Text(stringResource(R.string.local_new_title)) }, text = { Text(stringResource(R.string.local_new_note)) },
        confirmButton = { TextButton(onClick = { confirmNew = false; start() }) { Text(stringResource(R.string.local_start)) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun LocalToggle(label: Int, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun LocalGameScreen(model: LocalGameViewModel, settings: Settings, skin: BoardSkin,
    onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit, onExit: () -> Unit) {
    val ui by model.ui.collectAsState()
    val score by model.score.collectAsState()
    val saveFailed by model.saveFailed.collectAsState()
    var preview by remember { mutableStateOf<Int?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<pt.tiago.neonline.game.LocalTacticalReviewInput?>(null) }
    val palette = pt.tiago.neonline.ui.art.BoardStyles.palette(skin, settings.colorPalette)
    val player1Color = androidx.compose.ui.graphics.Color(palette.p1)
    val player2Color = androidx.compose.ui.graphics.Color(palette.p2)
    val name1 = model.config.player1.ifBlank { stringResource(R.string.local_player, 1) }
    val name2 = model.config.player2.ifBlank { stringResource(R.string.local_player, 2) }
    fun name(player: Player) = if (player == Player.P1) name1 else name2
    val turn = when (val status = ui.state.status) {
        is Status.Won -> stringResource(R.string.local_winner, name(status.player))
        is Status.Draw -> stringResource(R.string.local_draw)
        Status.InProgress -> stringResource(R.string.local_turn, name(ui.state.toMove))
    }
    val noticeNames = (if (model.config.player1.isBlank()) name1 else stringResource(R.string.review_local_player, 1, name1)) to
        (if (model.config.player2.isBlank()) name2 else stringResource(R.string.review_local_player, 2, name2))
    val moveNotice = pt.tiago.neonline.ui.board.boardMoveNoticeText(ui, noticeNames)
    BackHandler { model.pause() }
    val context = LocalContext.current
    val audio = remember { AudioController(context) }
    SideEffect { audio.configure(settings) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); model.pause() }
    }
    LaunchedEffect(ui.animation?.id, ui.events) {
        if (ui.animation == null) audio.playEvents(ui.events.filterNot { it is GameEvent.MatchOver })
    }
    LaunchedEffect(ui.state.status, ui.animating) {
        if (!ui.animating) when (val status = ui.state.status) {
            is Status.Won -> audio.play("win")
            is Status.Draw -> audio.play("draw")
            Status.InProgress -> Unit
        }
    }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = model::pause) { Text(stringResource(R.string.local_pause)) }
            Text(stringResource(R.string.local_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
    // Table mode: player 2 sits across the table, so their score faces them, upside down.
    val topScore: @Composable () -> Unit = {
        if (model.config.tableMode) PlayerScore(name2, score.second, ui.state.toMove == Player.P2 && !ui.finished, player2Color, Modifier.rotate(180f))
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerScore(name1, score.first, ui.state.toMove == Player.P1 && !ui.finished, player1Color, Modifier.weight(1f))
            PlayerScore(name2, score.second, ui.state.toMove == Player.P2 && !ui.finished, player2Color, Modifier.weight(1f))
        }
    }
    val status: @Composable () -> Unit = {
        Text(turn, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = listOf(moveNotice, turn).filter { it.isNotBlank() }.joinToString(" ")
        })
        Text(stringResource(R.string.local_draw_count, score.third), style = MaterialTheme.typography.bodySmall)
        ui.clockMs?.let { Text(stringResource(R.string.local_time_left, (it + 999) / 1000), color = if (it <= 3000) MaterialTheme.colorScheme.error else Amber) }
        if (saveFailed) Text(stringResource(R.string.local_save_error), color = MaterialTheme.colorScheme.error)
        RotationPreviewControls(ui, settings, skin)
    }
    val board: @Composable (Modifier) -> Unit = { boardModifier ->
        BoardCanvas(ui, skin, settings.symbolsOnPieces, showPreview = settings.showPreview, colorPalette = settings.colorPalette,
            turnDescription = turn, previewColumn = preview, onColumnTap = model::playColumn,
            onColumnDrag = { preview = it }, onBottomPieceTap = model::requestPop,
            onAnimationFinished = model::completeAnimation,
            onAnimationPhase = { phase ->
                when (phase.effect) {
                    AnimationTimeline.Effect.FALL -> audio.play(if (phase.motions.any { it.source == null }) "drop" else "bounce")
                    AnimationTimeline.Effect.POP -> audio.play("pop")
                    AnimationTimeline.Effect.ROTATE -> audio.play("rotate")
                    AnimationTimeline.Effect.BOMB -> audio.play("bomb")
                    AnimationTimeline.Effect.LOCK -> audio.play("lock")
                    AnimationTimeline.Effect.LINE -> audio.lineCell(phase.litCells.size - 1)
                    else -> Unit
                }
            },
            modifier = boardModifier)
    }
    val controls: @Composable () -> Unit = {
        PieceControls(ui, model.initialState(), model::selectPiece, model::requestPop, model::confirmPop, model::cancelPop)
        if (ui.pendingDrop != null) Text(stringResource(R.string.local_confirm_drop, ui.pendingDrop!!.col + 1))
    }
    val bottomScore: @Composable () -> Unit = {
        if (model.config.tableMode) PlayerScore(name1, score.first, ui.state.toMove == Player.P1 && !ui.finished, player1Color)
    }
    val roundEnd: @Composable () -> Unit = {
        if (ui.finished && !ui.animating) {
            OutlinedButton(onClick = { review = model.reviewInput(Player.P1) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_action))
            }
            Button(onClick = model::nextRound, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_next)) }
            TextButton(onClick = { model.pause(); onExit() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.local_exit)) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
        if (maxWidth > maxHeight) {
            // Landscape: scores, status and controls on the left; the board as large as the height allows.
            // In table mode player 2 stays at the top edge and player 1 at the bottom edge, as in portrait.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    header()
                    topScore()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        status()
                        controls()
                        roundEnd()
                    }
                    bottomScore()
                }
                // A rotating board alternates between 7x6 and 6x7, so it gets a square frame.
                val aspect = if (ui.state.rotateEvery != null) 1f else (ui.state.width + 0.6f) / (ui.state.height + 0.6f)
                Box(Modifier.weight(0.58f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    board(Modifier.aspectRatio(aspect))
                }
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            topScore()
            status()
            board(Modifier.fillMaxWidth().weight(1f))
            Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                controls()
                bottomScore()
                roundEnd()
            }
        }
    }
    if (ui.paused && !confirmRestart && review == null) AlertDialog(onDismissRequest = model::resume,
        title = { Text(stringResource(R.string.local_pause)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.local_pause_note))
            PauseFeedbackControls(settings, onSoundChange, onVibrationChange)
        } },
        confirmButton = { TextButton(onClick = model::resume) { Text(stringResource(R.string.local_continue)) } },
        dismissButton = { Column {
            if (!ui.finished) TextButton(onClick = { confirmRestart = true }) { Text(stringResource(R.string.local_restart)) }
            TextButton(onClick = onExit) { Text(stringResource(R.string.local_exit)) }
        } })
    if (confirmRestart) AlertDialog(onDismissRequest = { confirmRestart = false },
        title = { Text(stringResource(R.string.local_restart)) }, text = { Text(stringResource(R.string.local_restart_note)) },
        confirmButton = { TextButton(onClick = { confirmRestart = false; model.restart() }) { Text(stringResource(R.string.local_restart)) } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.action_cancel)) } })
    review?.let { input ->
        LocalTacticalReviewDialog(input, settings, skin,
            onPlayerChange = { selected -> review = review?.copy(player = selected) },
            onBack = { review = null; model.resume() }, onNextRound = { review = null; model.nextRound() })
    }
}

@Composable
private fun PlayerScore(name: String, wins: Int, active: Boolean, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = color, maxLines = 1)
        Text(stringResource(R.string.local_score, wins), style = MaterialTheme.typography.titleMedium)
        if (active) Text(stringResource(R.string.local_active), style = MaterialTheme.typography.labelSmall)
    }
}
