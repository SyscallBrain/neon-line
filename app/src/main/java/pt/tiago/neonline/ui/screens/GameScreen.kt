package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.neonline.R
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LevelObjective
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.game.AnimationTimeline
import pt.tiago.neonline.game.GameViewModel
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.board.BoardCanvas
import pt.tiago.neonline.ui.theme.Amber
import pt.tiago.neonline.ui.theme.Danger
import pt.tiago.neonline.ui.theme.Rail
import pt.tiago.neonline.ui.theme.Shoulder
import pt.tiago.neonline.ui.theme.TextDim
import pt.tiago.neonline.ui.theme.Win

/**
 * The game screen: HUD, the board, the rival card and the bottom bar (hint, undo, restart). The
 * animations are driven by the engine's events, so the frame rate never changes the result.
 */
@Composable
fun GameScreen(
    start: GameState,
    settings: pt.tiago.neonline.data.Settings = pt.tiago.neonline.data.Settings(),
    objective: LevelObjective,
    rivalProfile: AiProfile,
    difficulty: Difficulty,
    hints: Int,
    levelTitle: String,
    rivalName: String,
    onExit: () -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onNext: (() -> Unit)? = null,
    ruleSummary: String? = null,
    showStars: Boolean = true,
    allowRestart: Boolean = true,
    allowRetry: Boolean = true,
    allowReview: Boolean = true,
    showResult: Boolean = true,
    showRivalThreats: Boolean = true,
    newAchievements: List<String> = emptyList(),
    newSkins: List<BoardSkin> = emptyList(),
    skin: BoardSkin = BoardSkin.NEON_ROAD,
    viewModel: GameViewModel = viewModel(
        factory = GameViewModel.factory(start, objective, rivalProfile, difficulty, hints),
    ),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val rivalThreats = remember(ui.state, ui.acceptsInput, ui.localPlayers, ui.tutorialStep, showRivalThreats) {
        pt.tiago.neonline.game.RivalThreatCues.snapshot(ui, showRivalThreats)
    }
    var previewColumn by remember { mutableStateOf<Int?>(null) }
    var showPause by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<pt.tiago.neonline.game.TacticalReviewInput?>(null) }
    val context = LocalContext.current
    val audio = remember { pt.tiago.neonline.game.AudioController(context) }
    androidx.compose.runtime.SideEffect { audio.configure(settings) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun openPause() { showPause = true; viewModel.pause() }
    BackHandler { openPause() }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { showPause = true; viewModel.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.pause(persist = false) }
    }
    LaunchedEffect(ui.events, ui.animation?.id) {
        if (ui.animation == null) audio.playEvents(ui.events.filterNot { it is pt.tiago.neonline.core.model.GameEvent.MatchOver })
    }
    LaunchedEffect(ui.outcome, ui.animating) {
        ui.outcome?.takeIf { !ui.animating }?.let { audio.playResult(it, objective.kind == LevelKind.BOSS) }
    }

    LaunchedEffect(ui.roundOutcome, ui.animating) {
        if (!ui.animating && ui.outcome == null) ui.roundOutcome?.let(audio::playRound)
    }

    val moveNotice = pt.tiago.neonline.ui.board.boardMoveNoticeText(ui,
        stringResource(R.string.local_player, 1) to rivalName)
    val turnLabel = when {
        ui.animating -> stringResource(R.string.turn_animating)
        ui.finished -> stringResource(R.string.turn_over)
        ui.rivalThinking -> stringResource(R.string.turn_rival, rivalName)
        else -> stringResource(R.string.turn_you)
    }
    // A rotating board alternates between 7x6 and 6x7, so it gets a square frame.
    val boardAspect = if (ui.state.rotateEvery != null) 1f else (ui.state.width + 0.6f) / (ui.state.height + 0.6f)
    val infoSection: @Composable ColumnScope.() -> Unit = {
        // Top bar: pause, level, difficulty chip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { openPause() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_pause))
            }
            Text(
                text = levelTitle,
                style = MaterialTheme.typography.titleLarge,
                color = TextDim,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(difficulty.labelResource()),
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (ui.clockMs != null) {
                Text(
                    text = "${(ui.clockMs!! / 1000)}s",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (ui.clockMs!! <= 3_000) Danger else Amber,
                )
            }
        }

        // Objective or rival card.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Rail),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (objective.kind == LevelKind.DEFENSE) {
                        (objective.surviveAiMoves ?: 0).let { pluralStringResource(R.plurals.intro_defense, it, it) }
                    } else if (objective.kind == LevelKind.PUZZLE) {
                        (objective.winInMoves ?: 0).let { pluralStringResource(R.plurals.objective_win_in, it, it) }
                    } else {
                        stringResource(if(objective.kind == LevelKind.BOSS) R.string.objective_boss else R.string.objective_duel, rivalName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
                ruleSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextDim) }
                if (objective.kind == LevelKind.PUZZLE) {
                    Text(stringResource(R.string.moves_progress, ui.humanMoveCount, objective.winInMoves ?: 0), style = MaterialTheme.typography.bodySmall, color = TextDim)
                }
                if (objective.kind == LevelKind.DEFENSE) Text(stringResource(R.string.defense_progress,
                    ui.rivalMoveCount, objective.surviveAiMoves ?: 0), style = MaterialTheme.typography.bodySmall)
                ui.bossSeries?.let { series ->
                    Text(stringResource(R.string.boss_score, series.wins, series.losses), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.boss_round, ui.bossRoundIndex + 1), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = turnLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color(pt.tiago.neonline.ui.art.BoardStyles.palette(skin, settings.colorPalette).let { if (ui.rivalThinking || ui.state.toMove == pt.tiago.neonline.core.model.Player.P2) it.p2 else it.p1 }),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = listOf(moveNotice, turnLabel).filter { it.isNotBlank() }.joinToString(" ")
                    },
                )
            }
        }

        ui.tutorialStep?.let { step ->
            Card(colors = CardDefaults.cardColors(containerColor = Shoulder), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tutorial_title), color = pt.tiago.neonline.ui.theme.TubeBlue, style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(tutorialTextResource(step.textKey)), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { viewModel.dismissTutorial() }, enabled = ui.acceptsInput) {
                        Text(stringResource(R.string.tutorial_skip))
                    }
                }
            }
        }

        RotationPreviewControls(ui, settings, skin)
        RivalThreatDetails(ui, rivalThreats)
        if (ui.state.fadeAfter != null) Text(stringResource(R.string.peek_game_help, ui.peekCount), style = MaterialTheme.typography.bodySmall)
    }
    // The board keeps its proportions inside whatever box the layout gives it.
    val boardSection: @Composable (Modifier, Alignment) -> Unit = { boxModifier, alignment ->
        Box(boxModifier, contentAlignment = alignment) {
            BoardCanvas(
                ui = ui,
                skin = skin,
                symbolsOnPieces = settings.symbolsOnPieces,
                colorPalette = settings.colorPalette,
                showPreview = settings.showPreview,
                previewColumn = previewColumn,
                rivalThreats = rivalThreats,
                onColumnTap = { column ->
                    previewColumn = null
                    if (!ui.finished && ui.state.toMove == pt.tiago.neonline.core.model.Player.P1) {
                        viewModel.playColumn(column)
                    }
                },
                onPeek = { column -> previewColumn = null; viewModel.peek(column) },
                onColumnDrag = { column -> previewColumn = column },
                onBottomPieceTap = { column -> previewColumn = null; viewModel.requestPop(column) },
                onAnimationFinished = { id -> viewModel.completeAnimation(id) },
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
                modifier = Modifier.aspectRatio(boardAspect),
            )

        }
    }
    val controlsSection: @Composable ColumnScope.() -> Unit = {
        ui.pendingDrop?.let { move ->
            Text(stringResource(R.string.confirm_drop_prompt, move.col + 1), style = MaterialTheme.typography.bodySmall)
        }

        PieceControls(
            ui = ui, initial = start,
            onSelect = viewModel::selectPiece, onRequestPop = viewModel::requestPop,
            onConfirmPop = viewModel::confirmPop, onCancelPop = viewModel::cancelPop,
        )

        // Bottom bar: hint, undo, restart.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { viewModel.hint() }, enabled = ui.hintsLeft > 0 && ui.acceptsInput && ui.tutorialStep == null) {
                Text(stringResource(R.string.action_hint, ui.hintsLeft), color = TextDim)
            }
            TextButton(onClick = { viewModel.undo() }, enabled = ui.undosLeft > 0 && ui.acceptsInput) {
                Text(stringResource(R.string.action_undo, ui.undosLeft), color = TextDim)
            }
            TextButton(onClick = { openPause() }) {
                Text(stringResource(R.string.action_pause), color = TextDim)
            }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        if (maxWidth > maxHeight) {
            // Landscape: details and buttons on the left, the board as large as the height allows.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    infoSection()
                    controlsSection()
                }
                boardSection(Modifier.weight(0.58f).fillMaxHeight(), Alignment.Center)
            }
        } else Column(Modifier.fillMaxSize()) {
            infoSection()
            // Portrait: the board sits just above the controls, within thumb reach; spare height stays above it.
            boardSection(Modifier.fillMaxWidth().weight(1f), Alignment.BottomCenter)
            controlsSection()
        }
    }

    if (showPause && review == null) {
        PauseDialog(
            settings = settings, onSoundChange = onSoundChange, onVibrationChange = onVibrationChange,
            allowRestart = allowRestart, restartLabel = if(objective.kind == LevelKind.BOSS) R.string.boss_restart_series else R.string.action_restart,
            onResume = { showPause = false; viewModel.resume() },
            onRestart = { showPause = false; viewModel.restart(); },
            onExit = { showPause = false; onExit() },
        )
    }

    if (ui.roundOutcome != null && ui.outcome == null && !ui.animating && !showPause && review == null) {
        val series = requireNotNull(ui.bossSeries)
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text(stringResource(when(val result=ui.roundOutcome) {
                is pt.tiago.neonline.core.model.MatchOutcome.Win -> if(result.player == pt.tiago.neonline.core.model.Player.P1) R.string.boss_round_won else R.string.boss_round_lost
                else -> R.string.boss_round_draw
            })) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.boss_score, series.wins, series.losses))
                Text(stringResource(if(series.repeatingDraw) R.string.boss_repeat_draw else R.string.boss_next_starter,
                    stringResource(if(series.roundIndex % 2 == 0) R.string.boss_starter_you else R.string.boss_starter_rival)))
                androidx.compose.material3.OutlinedButton(onClick = { review = viewModel.reviewInput() }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.review_boss_action))
                }
            } },
            confirmButton = { TextButton(onClick = viewModel::nextBossRound) { Text(stringResource(if(series.repeatingDraw) R.string.boss_replay_round else R.string.boss_next_round)) } },
            dismissButton = { TextButton(onClick = onExit) { Text(stringResource(R.string.action_exit)) } },
        )
    }

    ui.outcome?.takeIf { showResult && !ui.animating && !showPause && review == null }?.let { outcome ->
        ResultCard(onReview = { review = viewModel.reviewInput() }, showStars = showStars, newSkins = newSkins, newAchievements = newAchievements, series = ui.bossSeries, outcome = outcome, onRetry = { viewModel.restart() }, onExit = onExit, onNext = onNext, allowRetry = allowRetry, allowReview = allowReview)
    }
    review?.let { input ->
        TacticalReviewDialog(input, settings, skin, bossDuel = objective.kind == LevelKind.BOSS,
            onBack = { review = null; showPause = false; viewModel.resume() }, onRetry = { review = null; showPause = false; viewModel.restart() })
    }
}

@Composable
private fun PauseDialog(settings: pt.tiago.neonline.data.Settings, onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit, allowRestart: Boolean, restartLabel: Int, onResume: () -> Unit, onRestart: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onResume,
        title = { Text(stringResource(R.string.pause_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            PauseFeedbackControls(settings, onSoundChange, onVibrationChange)
        } },
        confirmButton = { TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) } },
        dismissButton = {
            Row {
                if (allowRestart) TextButton(onClick = onRestart) { Text(stringResource(restartLabel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onExit) { Text(stringResource(R.string.action_exit)) }
            }
        },
    )
}

@Composable
private fun ResultCard(onReview: () -> Unit, showStars: Boolean, newSkins: List<BoardSkin>, newAchievements: List<String>, series: pt.tiago.neonline.core.objectives.BossSeries?, outcome: LevelOutcome, onRetry: () -> Unit, onExit: () -> Unit, onNext: (() -> Unit)?, allowRetry: Boolean, allowReview: Boolean) {
    AlertDialog(
        onDismissRequest = onExit,
        title = {
            Text(
                text = when (outcome) {
                    is LevelOutcome.Won -> stringResource(R.string.result_win)
                    is LevelOutcome.Lost -> stringResource(if (!showStars && outcome.reason == "draw") R.string.local_draw else R.string.result_lost)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            // What happened comes first; the review is an optional follow-up below it.
            if (outcome is LevelOutcome.Won && showStars) {
                val stars = outcome.stars.coerceIn(0, 3)
                val starsLabel = pluralStringResource(R.plurals.result_stars, stars, stars)
                Text("★".repeat(stars) + "☆".repeat(3 - stars), style = MaterialTheme.typography.headlineMedium,
                    color = pt.tiago.neonline.ui.theme.Amber, modifier = Modifier.semantics { contentDescription = starsLabel })
            } else Text(
                text = when (outcome) {
                    is LevelOutcome.Won -> stringResource(R.string.free_result_saved)
                    is LevelOutcome.Lost -> stringResource(when (outcome.reason) {
                        "out_of_moves" -> R.string.loss_moves
                        "draw" -> R.string.loss_draw
                        "rival_won_best_of" -> R.string.boss_series_lost
                        else -> R.string.loss_rival
                    })
                },
            )
            if (newSkins.isNotEmpty() || newAchievements.isNotEmpty()) Spacer(Modifier.height(12.dp))
            newSkins.forEach { Text(stringResource(R.string.theme_unlocked, stringResource(it.titleResource())), color = pt.tiago.neonline.ui.theme.Amber) }
            if (newAchievements.isNotEmpty()) {
                Text(stringResource(R.string.achievements_new), color = pt.tiago.neonline.ui.theme.Amber)
                pt.tiago.neonline.data.CampaignAchievements.definitions.filter { it.id in newAchievements }.forEach {
                    Text(stringResource(it.goal.titleResource()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            series?.let { Text(stringResource(R.string.boss_score,it.wins,it.losses), modifier = Modifier.padding(top = 8.dp)) }
            if (allowReview) androidx.compose.material3.OutlinedButton(onClick = onReview, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(if (series != null) R.string.review_boss_action else R.string.review_action))
            }
            }
        },
        confirmButton = {
            Row {
                if (allowRetry) TextButton(onClick = onRetry) { Text(stringResource(if(series != null) R.string.boss_restart_series else R.string.action_retry)) }
                if (outcome is LevelOutcome.Won && onNext != null) {
                    TextButton(onClick = onNext) { Text(stringResource(R.string.action_next)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onExit) { Text(stringResource(R.string.action_exit)) } },
        containerColor = pt.tiago.neonline.ui.theme.Shoulder,
    )
}

