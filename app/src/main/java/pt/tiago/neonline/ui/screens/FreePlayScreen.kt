package pt.tiago.neonline.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.neonline.R
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*
import pt.tiago.neonline.game.GameViewModel
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.ui.art.BoardSkin
import java.text.NumberFormat

@Composable
fun FreePlaySetupScreen(profile: Profile, levels: List<RivalLevel>, initialRival: String?, resumable: SavedFreePlay?,
    onStart: (FreePlayConfig) -> Unit, onResume: () -> Unit, onCampaign: () -> Unit, onBack: () -> Unit, initialConfig: FreePlayConfig? = null) {
    BackHandler(onBack = onBack)
    val available = remember(profile, levels) { RivalGallery.summarize(profile, levels, Difficulty.EASY)
        .filter { it.encountered && it.definition.id in FreePlay.supportedRivals && levels.any { level -> level.rival == it.definition.id } }.map { it.definition } }
    val previous = initialConfig ?: profile.savedFreePlay?.config
    var difficulty by remember { mutableStateOf(previous?.difficulty ?: profile.settings.lastDifficulty) }
    var rival by remember(initialRival) { mutableStateOf(initialRival ?: previous?.rival ?: available.firstOrNull()?.id ?: "sparky") }
    var starter by remember { mutableStateOf(previous?.starter ?: Player.P1) }
    var rules by remember { mutableStateOf(previous?.rules ?: FreePlayRules()) }
    var pending by remember { mutableStateOf<FreePlayConfig?>(null) }
    val selected = FreePlayConfig(version = 8, rival = rival, difficulty = difficulty, starter = starter, rules = rules)
    val canStart = FreePlay.canStart(profile, levels, selected)
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val record = profile.freePlayStats[difficulty] ?: RivalRecord()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        ScreenHeader(stringResource(R.string.free_title), onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (selected.isPerfectMode()) R.string.free_perfect_note else R.string.free_note))
            Text(stringResource(R.string.nav_rivals), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(rival == FreePlayConfig.PERFECT_RIVAL, {
                    rival = FreePlayConfig.PERFECT_RIVAL
                    difficulty = Difficulty.HARD
                }, enabled = rules.isClassic(), label = { Text(stringResource(R.string.free_perfect_mode)) })
                available.forEach { opponent -> FilterChip(rival == opponent.id, { rival = opponent.id }, label = { Text(rivalName(opponent.id)) }) }
            }
            if (selected.isPerfectMode()) {
                if (!rules.isClassic()) Text(stringResource(R.string.free_perfect_classic_required), color = MaterialTheme.colorScheme.error)
            }
            if (available.isEmpty() && !selected.isPerfectMode()) {
                Text(stringResource(R.string.free_locked))
                OutlinedButton(onClick = onCampaign) { Text(stringResource(R.string.campaign_choose)) }
            }
            if (!selected.isPerfectMode()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Difficulty.entries.forEach { choice -> FilterChip(difficulty == choice, { difficulty = choice }, label = { Text(stringResource(choice.labelResource())) }) }
                }
            }
            Text(stringResource(R.string.free_starter), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(starter == Player.P1, { starter = Player.P1 }, label = { Text(stringResource(R.string.boss_starter_you)) })
                FilterChip(starter == Player.P2, { starter = Player.P2 }, label = { Text(stringResource(R.string.boss_starter_rival)) })
            }
            FreePlayRulesPicker(rules) { rules = it }
            if (selected.exactEndgameRival()) Text(stringResource(R.string.free_exact_endgame_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.free_move_limit, FreePlay.MAX_PLIES), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.free_stats, numbers.format(record.wins), numbers.format(record.draws), numbers.format(record.losses)))
            Text(stringResource(R.string.free_peeks, numbers.format(profile.freePlayPeeks[difficulty] ?: 0L)))
            Button(onClick = {
                val fresh = selected.copy(seedHex = kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0'))
                if (profile.savedFreePlay != null) pending = fresh else onStart(fresh)
            }, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.free_start)) }
            resumable?.let { saved ->
                val name = rivalName(saved.config.rival)
                OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.free_resume, name, stringResource(saved.config.difficulty.labelResource()))) }
            }
            if (profile.savedFreePlay != null && resumable == null) Text(stringResource(R.string.free_unavailable))
            Spacer(Modifier.height(16.dp))
        }
    }
    pending?.let { config -> AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.free_replace_title)) },
        text = { Text(stringResource(R.string.free_replace_note)) },
        confirmButton = { TextButton(onClick = { pending = null; onStart(config) }) { Text(stringResource(R.string.free_start)) } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

@Composable
fun FreePlayGameScreen(config: FreePlayConfig, settings: Settings, skin: BoardSkin, saved: SavedMatch?,
    onCheckpoint: (SavedMatch, LevelOutcome?) -> Unit, onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit, onExit: () -> Unit,
    rivalProfileOverride: AiProfile? = null, hintsOverride: Int? = null, undoOverride: Int? = null,
    allowRetry: Boolean = true, onNext: (() -> Unit)? = null, levelTitleOverride: String? = null) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val snapshot = remember { saved }
    val context = LocalContext.current
    val openingBookLoader = remember(context) {{
        try { context.assets.open("book/book-7x6.bin").use(PerfectOpeningBook::read) }
        catch (_: Exception) { null }
    }}
    val start = remember(config) { config.initial() }
    val objective = remember { LevelObjective(LevelKind.DUEL) }
    val ai = remember(config, rivalProfileOverride) { rivalProfileOverride ?: requireNotNull(config.ai()) }
    val hints = hintsOverride ?: config.hints()
    val model: GameViewModel = viewModel(viewModelStoreOwner = owner, factory = GameViewModel.factory(start, objective, ai,
        config.difficulty, hints, animationEnabled = !settings.reducedMotion && android.animation.ValueAnimator.areAnimatorsEnabled(),
        confirmMove = settings.confirmMove, aiDelayMs = if (settings.aiSpeed == "fast") 0L else pt.tiago.neonline.game.AnimationTimeline.AI_MIN_THINK_MS,
        savedMatch = snapshot, maxPlies = config.maxPlies(), hintProfile = requireNotNull(config.hintAi(rivalProfileOverride)),
        exactEndgameRival = config.exactEndgameRival(), perfectRival = config.isPerfectMode(), openingBookLoader = openingBookLoader,
        perfectSearchLogger = { line ->
            android.util.Log.i("NeonLine", line)
            try {
                withContext(Dispatchers.IO) { PerfectSearchDiagnostics.append(context.filesDir, line) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("NeonLine", "Could not persist Perfect Mode timing", error)
            }
        },
        onCheckpoint = { match, outcome, _ -> onCheckpoint(match, outcome) }, undoAvailable = undoOverride))
    GameScreen(start = start, settings = settings, skin = skin, objective = objective, rivalProfile = ai, difficulty = config.difficulty,
        hints = hints, levelTitle = levelTitleOverride ?: stringResource(R.string.free_game_title, stringResource(config.difficulty.labelResource())),
        rivalName = if (config.isPerfectMode()) stringResource(R.string.free_perfect_mode)
            else rivalName(config.rival),
        ruleSummary = freePlayRulesSummary(config.rules, config.difficulty), showStars = false,
        onSoundChange = onSoundChange, onVibrationChange = onVibrationChange,
        onExit = onExit, onNext = onNext, allowRestart = allowRetry, allowRetry = allowRetry,
        allowReview = allowRetry, viewModel = model)
}
