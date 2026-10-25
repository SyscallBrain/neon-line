package pt.tiago.neonline

import android.os.Bundle
import kotlinx.coroutines.ensureActive
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.ui.screens.GameScreen
import pt.tiago.neonline.ui.screens.HomeScreen
import pt.tiago.neonline.ui.screens.CampaignScreen
import pt.tiago.neonline.data.ProfileReducer
import pt.tiago.neonline.ui.theme.NeonLineTheme

/**
 * The single activity: the home screen with the campaign card, and the game screen for the level
 * the player picked. The content comes from the packaged catalog and the progress from the profile,
 * both provided by the app container.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        setContent {
            // The catalog (600 verified levels) and the profile load off the main thread, once per process.
            val ready by androidx.compose.runtime.produceState(container.isWarm()) {
                if (!value) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { container.warmUp() }
                    value = true
                }
            }
            if (ready) {
                NeonLineApp()
                LaunchedEffect(Unit) {
                    // Once home is on screen, load the map and rival metadata so those screens open at once.
                    androidx.compose.runtime.withFrameNanos { }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { container.prefetch() }
                }
            } else NeonLineTheme { LoadingScreen() }
        }
    }

    override fun onStop() {
        super.onStop()
        // Profile writes run in the background; make sure the last one lands before the app may be killed.
        container.profileStore.flush()
    }

    @androidx.compose.runtime.Composable
    private fun LoadingScreen() {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
    }

    @androidx.compose.runtime.Composable
    private fun NeonLineApp() {
            var settings by remember { mutableStateOf(container.profile().settings) }
            val backups: pt.tiago.neonline.backup.BackupViewModel = viewModel()
            val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), backups::exportTo)
            val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), backups::importFrom)
            LaunchedEffect(backups.message) {
                backups.message?.let { showToast(getString(it)); backups.clearMessage() }
            }
            NeonLineTheme(largerText = settings.largerText) {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var session by remember { mutableStateOf(0) }
                var pendingIntroduction by remember { mutableStateOf<pt.tiago.neonline.data.LevelBriefing?>(null) }
                var pendingGame by remember { mutableStateOf<Screen.Game?>(null) }
                LaunchedEffect(backups.replacedProfile) {
                    backups.replacedProfile?.let { settings = it.settings; pendingIntroduction = null; pendingGame = null; session++; screen = Screen.Home; backups.consumeReplacedProfile() }
                }
                val packagedLevels = remember { container.catalog.load()?.levels.orEmpty() }
                fun changeFeedback(sound: Boolean? = null, vibration: Boolean? = null) {
                    val profile = container.profile()
                    val updated = ProfileReducer.withFeedbackSettings(profile, sound, vibration)
                    if (updated != profile) container.save(updated)
                    settings = updated.settings
                }
                fun startGame(ordinal: Int, difficulty: Difficulty, replaceConfirmed: Boolean = false) {
                    val profile = container.profile()
                    val action = pt.tiago.neonline.data.CampaignLaunch.action(profile, packagedLevels, ordinal, difficulty, container.resumableMatch()?.second)
                    when(action) {
                        pt.tiago.neonline.data.CampaignLaunchAction.RESUME -> { session++; screen = Screen.Game(ordinal,difficulty) }
                        pt.tiago.neonline.data.CampaignLaunchAction.INTRO -> {
                            if(!replaceConfirmed && pt.tiago.neonline.data.CampaignLaunch.needsReplacement(profile.savedMatch)) pendingGame = Screen.Game(ordinal,difficulty)
                            else { session++; screen = Screen.Game(ordinal,difficulty) }
                        }
                        else -> showToast(getString(R.string.content_unavailable))
                    }
                }
                fun openGame(ordinal: Int, difficulty: Difficulty) {
                    when(pt.tiago.neonline.data.CampaignLaunch.action(container.profile(),packagedLevels,ordinal,difficulty,container.resumableMatch()?.second)) {
                        pt.tiago.neonline.data.CampaignLaunchAction.RESUME -> startGame(ordinal,difficulty)
                        pt.tiago.neonline.data.CampaignLaunchAction.INTRO -> {
                            val level = container.levelState(ordinal,difficulty)
                            pendingIntroduction = level?.let { (info,state) -> pt.tiago.neonline.data.LevelBriefing.from(info,state) }
                            if(level==null) showToast(getString(R.string.content_unavailable))
                        }
                        else -> showToast(getString(R.string.content_unavailable))
                    }
                }
                when (val current = screen) {
                    Screen.Home -> {
                        val profile = container.profile()
                        val resumable = container.resumableMatch()
                        HomeScreen(
                            resuming = resumable != null,
                            savedUnavailable = profile.savedMatch != null && resumable == null,
                            ordinal = resumable?.first ?: profile.continueOrdinal(profile.settings.lastDifficulty).coerceAtMost(packagedLevels.lastOrNull()?.ordinal ?: 1),
                            difficulty = resumable?.second?.difficulty ?: profile.settings.lastDifficulty,
                            stars = profile.totalStars(),
                            availableLevels = packagedLevels.size,
                            onPlay = { ordinal, difficulty -> openGame(ordinal, difficulty) },
                            onCampaign = { screen = Screen.Campaign() },
                            onSettings = { screen = Screen.Settings },
                            onStatistics = { screen = Screen.Statistics },
                            onAchievements = { screen = Screen.Achievements },
                            onDaily = { screen = Screen.Daily },
                            onRush = { screen = Screen.Rush },
                            onTournament = { screen = Screen.TournamentSetup },
                            onRivals = { screen = Screen.Rivals },
                            onFreePlay = { screen = Screen.FreeSetup() },
                            onLocal = { screen = Screen.LocalSetup },
                            road = pt.tiago.neonline.data.CampaignRoad.summarize(profile, packagedLevels,
                                resumable?.second?.difficulty ?: profile.settings.lastDifficulty,
                                resumable?.first ?: profile.continueOrdinal(profile.settings.lastDifficulty)),
                            reducedMotion = settings.reducedMotion,
                            onStop = { stop ->
                                val difficulty = resumable?.second?.difficulty ?: profile.settings.lastDifficulty
                                if (pt.tiago.neonline.data.CampaignRoad.canOpen(container.profile(), packagedLevels, difficulty, stop))
                                    screen = Screen.Campaign(stop = stop, difficulty = difficulty)
                            },
                        )
                    }
                    is Screen.FreeSetup -> {
                        val profile = container.profile()
                        val levels = remember { container.catalog.rivalLevels() }
                        val resume = container.resumableFreePlay()
                        pt.tiago.neonline.ui.screens.FreePlaySetupScreen(profile, levels, current.rival, resume,
                            onStart = { config ->
                                if (pt.tiago.neonline.data.FreePlay.canStart(container.profile(), levels, config)) {
                                    container.save(pt.tiago.neonline.data.FreePlay.start(container.profile(), config))
                                    session++; screen = Screen.FreeGame(config, current.rival != null)
                                }
                            }, onResume = { resume?.let { session++; screen = Screen.FreeGame(it.config, current.rival != null) } },
                            onCampaign = { screen = Screen.Campaign() },
                            onBack = { screen = if (current.rival != null) Screen.Rivals else Screen.Home }, initialConfig = current.config)
                    }
                    is Screen.FreeGame -> key(session) {
                        val gameSession = session
                        val saved = remember { container.resumableFreePlay()?.takeIf { it.config == current.config }?.match }
                        pt.tiago.neonline.ui.screens.FreePlayGameScreen(current.config, settings,
                            pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, container.profile().totalStars()), saved,
                            onSoundChange = { changeFeedback(sound = it) }, onVibrationChange = { changeFeedback(vibration = it) },
                            onCheckpoint = { match, outcome -> container.save(pt.tiago.neonline.data.FreePlay.checkpointForCurrentSession(
                                container.profile(), current.config, match, outcome, gameSession, session)) },
                            onExit = { session++; screen = Screen.FreeSetup(if (current.fromGallery) current.config.rival else null, current.config) })
                    }
                    Screen.Rush -> {
                        val loaded by androidx.compose.runtime.produceState<Pair<Boolean,pt.tiago.neonline.data.RushPack?>>(false to null) {
                            value=true to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                val context=kotlinx.coroutines.currentCoroutineContext()
                                container.catalog.rushPack { context.ensureActive() }
                            }
                        }
                        if(!loaded.first) {
                            androidx.activity.compose.BackHandler { screen=Screen.Home }
                            androidx.compose.foundation.layout.Column {
                                TextButton(onClick={screen=Screen.Home}) { Text(stringResource(R.string.action_back)) }
                                androidx.compose.material3.LinearProgressIndicator()
                                Text(stringResource(R.string.rush_loading))
                            }
                        } else {
                            val pack=loaded.second
                            pt.tiago.neonline.ui.screens.PuzzleRushScreen(container.profile(),settings,
                                pt.tiago.neonline.data.BoardThemes.resolve(settings.skin,container.profile().totalStars()),pack?.puzzles ?: emptyList(),
                                load={ puzzle -> pack?.stateFor(puzzle)?.let { state ->
                                    pt.tiago.neonline.ui.screens.RushRoundSource(state,
                                        pt.tiago.neonline.core.objectives.LevelObjective(pt.tiago.neonline.core.objectives.LevelKind.PUZZLE,winInMoves=puzzle.moves),
                                        pt.tiago.neonline.data.RushPack.RIVAL,getString(R.string.rival_name_sparky))
                                } },onRecord={ difficulty,score ->
                                    pt.tiago.neonline.data.PuzzleRush.record(container.profile(),difficulty,score).also { container.save(it) }
                                },onSoundChange={changeFeedback(sound=it)},onVibrationChange={changeFeedback(vibration=it)},onBack={screen=Screen.Home})
                        }
                    }
                    Screen.TournamentSetup -> {
                        val profile = container.profile()
                        val rivalLevels = remember { container.catalog.rivalLevels() }
                        val saved = container.resumableTournament()
                        pt.tiago.neonline.ui.screens.TournamentScreen(
                            profile = profile,
                            levels = rivalLevels,
                            resumable = saved,
                            onStart = { selected, difficulty ->
                                val latest = container.profile()
                                val seed = kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0')
                                val tournament = runCatching {
                                    pt.tiago.neonline.data.Tournament.create(
                                        selected,
                                        pt.tiago.neonline.data.Tournament.defeatedRivals(latest, rivalLevels),
                                        difficulty,
                                        seed,
                                        pt.tiago.neonline.data.Tournament.unlockedRules(latest),
                                    )
                                }.getOrNull()
                                if (tournament == null) showToast(getString(R.string.tournament_locked))
                                else {
                                    container.save(latest.copy(revision = latest.revision + 1, savedTournament = tournament,
                                        settings = latest.settings.copy(lastDifficulty = difficulty)))
                                    settings = settings.copy(lastDifficulty = difficulty)
                                    session++
                                    screen = Screen.TournamentGame
                                }
                            },
                            onResume = { session++; screen = Screen.TournamentGame },
                            onFinish = {
                                container.profile().savedTournament?.let { terminal ->
                                    runCatching { container.save(pt.tiago.neonline.data.Tournament.finish(container.profile(), terminal)) }
                                }
                                screen = Screen.Home
                            },
                            onAbandon = {
                                val latest = container.profile()
                                container.save(latest.copy(revision = latest.revision + 1, savedTournament = null))
                                screen = Screen.Home
                            },
                            onBack = { screen = Screen.Home },
                        )
                    }
                    Screen.TournamentGame -> key(session) {
                        val savedTournament = container.resumableTournament()
                        val tournament = savedTournament?.takeIf { it.status == pt.tiago.neonline.data.TournamentStatus.ACTIVE }
                        if (tournament == null) {
                            LaunchedEffect(Unit) {
                                showToast(getString(R.string.tournament_locked))
                                screen = Screen.TournamentSetup
                            }
                        } else {
                            val config = pt.tiago.neonline.data.Tournament.roundConfig(tournament)
                            val rivalProfile = remember(config.rival, config.difficulty) {
                                val rivalLevel = container.catalog.rivalLevels().firstOrNull { it.rival == config.rival && it.difficulty == config.difficulty }
                                val rivalInfo = rivalLevel?.let { container.levelInfo(it.ordinal, config.difficulty) }
                                rivalInfo?.let { container.rivalProfile(it, config.difficulty) } ?: when (config.difficulty) {
                                    Difficulty.EASY -> pt.tiago.neonline.core.ai.AiProfile.EASY
                                    Difficulty.MEDIUM -> pt.tiago.neonline.core.ai.AiProfile.MEDIUM
                                    Difficulty.HARD -> pt.tiago.neonline.core.ai.AiProfile.HARD
                                }
                            }
                            pt.tiago.neonline.ui.screens.FreePlayGameScreen(
                                config = config,
                                settings = settings,
                                skin = pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, container.profile().totalStars()),
                                saved = tournament.currentMatch,
                                onCheckpoint = { match, outcome ->
                                    val latest = container.profile()
                                    val active = latest.savedTournament?.takeIf { it.seedHex == tournament.seedHex } ?: return@FreePlayGameScreen
                                    val updated = if (outcome == null) {
                                        pt.tiago.neonline.data.Tournament.checkpoint(latest, active.copy(currentMatch = match))
                                    } else {
                                        val result = when (outcome) {
                                            is pt.tiago.neonline.core.objectives.LevelOutcome.Won -> pt.tiago.neonline.data.TournamentOutcome.WIN
                                            is pt.tiago.neonline.core.objectives.LevelOutcome.Lost -> if (outcome.reason == "draw") pt.tiago.neonline.data.TournamentOutcome.DRAW else pt.tiago.neonline.data.TournamentOutcome.LOSS
                                        }
                                        val next = pt.tiago.neonline.data.Tournament.resolve(active.copy(currentMatch = match), result)
                                        latest.copy(revision = latest.revision + 1, savedTournament = next)
                                    }
                                    container.save(updated)
                                },
                                onSoundChange = { changeFeedback(sound = it) },
                                onVibrationChange = { changeFeedback(vibration = it) },
                                onExit = { screen = Screen.TournamentSetup },
                                rivalProfileOverride = rivalProfile,
                                hintsOverride = 0,
                                undoOverride = 0,
                                allowRetry = false,
                                levelTitleOverride = getString(R.string.tournament_round_title, tournament.roundIndex + 1, pt.tiago.neonline.data.Tournament.ROUNDS),
                                onNext = {
                                    if (container.resumableTournament()?.status == pt.tiago.neonline.data.TournamentStatus.ACTIVE) {
                                        session++
                                        screen = Screen.TournamentGame
                                    } else screen = Screen.TournamentSetup
                                },
                            )
                        }
                    }
                    Screen.Daily -> {
                        val profile = container.profile()
                        val resume = container.resumableDaily()
                        pt.tiago.neonline.ui.screens.DailyScreen(profile,
                            available = { date, difficulty -> container.dailyEntry(date, difficulty) != null }, resumable = resume,
                            onResume = { resume?.let { saved ->
                                val ordinal = packagedLevels.first { it.id == saved.match.levelId }.ordinal
                                session++; screen = Screen.DailyGame(saved.date, ordinal, saved.match.difficulty, saved.poolVersion, saved.levelSha256)
                            } },
                            onPlay = { date, difficulty ->
                                val entry = container.dailyEntry(date, difficulty)
                                if (entry != null) {
                                    val previous = container.profile().dailyHistory.firstOrNull { it.date == date && it.difficulty == difficulty }
                                    val active = container.resumableDaily()?.takeIf { it.date == date && it.match.difficulty == difficulty }
                                    val version = active?.poolVersion ?: previous?.poolVersion ?: container.catalog.dailyPool()!!.version
                                    session++; screen = Screen.DailyGame(date, entry.ordinal, difficulty, version, entry.sha256)
                                }
                            }, onBack = { screen = Screen.Home })
                    }
                    is Screen.DailyGame -> key(session) {
                        val level = remember { container.levelState(current.ordinal, current.difficulty) }
                        if (level != null) {
                            val (info, start) = level
                            val dailyRival = remember { container.rivalProfile(info, current.difficulty) }
                            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
                            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                            val saved = remember { container.resumableDaily()?.takeIf { it.date == current.date && it.match.difficulty == current.difficulty && it.match.levelId == info.id } }
                            val model: pt.tiago.neonline.game.GameViewModel = viewModel(viewModelStoreOwner = owner,
                                factory = pt.tiago.neonline.game.GameViewModel.factory(start, info.objective(), dailyRival,
                                    current.difficulty, info.hints, animationEnabled = !settings.reducedMotion && android.animation.ValueAnimator.areAnimatorsEnabled(),
                                    confirmMove = settings.confirmMove, aiDelayMs = if (settings.aiSpeed == "fast") 0L else pt.tiago.neonline.game.AnimationTimeline.AI_MIN_THINK_MS,
                                    savedMatch = saved?.match, onCheckpoint = { match, outcome, _ -> container.checkpointDaily(current.date, current.poolVersion, current.sha, match, outcome) }))
                            pt.tiago.neonline.ui.screens.GameScreen(start = start, settings = settings,
                                onSoundChange = { changeFeedback(sound = it) }, onVibrationChange = { changeFeedback(vibration = it) },
                                skin = remember { pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, container.profile().totalStars()) },
                                objective = info.objective(), rivalProfile = dailyRival, difficulty = current.difficulty,
                                hints = info.hints, levelTitle = getString(R.string.daily_game_title, java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).format(java.time.LocalDate.parse(current.date))), rivalName = pt.tiago.neonline.ui.screens.rivalName(info.rival),
                                onExit = { screen = Screen.Daily }, onNext = null, viewModel = model)
                        } else LaunchedEffect(Unit) { showToast(getString(R.string.daily_unavailable)); screen = Screen.Daily }
                    }
                    Screen.LocalSetup -> {
                        val saved = container.profile().localSession
                        val resumable = saved?.takeIf { pt.tiago.neonline.data.LocalSessionReplay.restore(it) != null }
                        pt.tiago.neonline.ui.screens.LocalSetupScreen(saved?.config, resumable != null,
                            onResume = { resumable?.let { session++; screen = Screen.LocalGame(it.config) } },
                            onStart = { config ->
                                val start = config.initial(pt.tiago.neonline.core.model.Player.P1)
                                val local = pt.tiago.neonline.data.SavedLocalSession(config = config,
                                    stateHash = pt.tiago.neonline.core.serialization.StateHasher.hash(start), clockMs = config.clockSeconds.takeIf { it > 0 }?.times(1000L))
                                val profile = container.profile()
                                runCatching { container.save(profile.copy(localSession = local, revision = profile.revision + 1)) }
                                    .onSuccess { session++; screen = Screen.LocalGame(config) }
                                    .onFailure { showToast(getString(R.string.local_save_error)) }
                            }, onBack = { screen = Screen.Home })
                    }
                    is Screen.LocalGame -> key(session) {
                        val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
                        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                        val saved = remember { container.profile().localSession }
                        val model: pt.tiago.neonline.game.LocalGameViewModel = viewModel(viewModelStoreOwner = owner,
                            factory = pt.tiago.neonline.game.LocalGameViewModel.factory(current.config, saved,
                                !settings.reducedMotion && android.animation.ValueAnimator.areAnimatorsEnabled(), settings.confirmMove) { local ->
                                val profile = container.profile()
                                container.save(profile.copy(localSession = local, revision = profile.revision + 1))
                            })
                        pt.tiago.neonline.ui.screens.LocalGameScreen(model, settings,
                            pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, container.profile().totalStars()),
                            onSoundChange = { changeFeedback(sound = it) }, onVibrationChange = { changeFeedback(vibration = it) }) { screen = Screen.LocalSetup }
                    }
                    Screen.Settings -> pt.tiago.neonline.ui.screens.SettingsScreen(
                        settings = settings,
                        stars = container.profile().totalStars(),
                        onTheme = { skin ->
                            val currentProfile = container.profile()
                            val updated = pt.tiago.neonline.data.BoardThemes.select(currentProfile, skin)
                            if (updated != currentProfile) { container.save(updated); settings = updated.settings }
                        },
                        onBack = { screen = Screen.Home },
                        backupBusy = backups.busy,
                        onExport = { backups.prepareExport {
                            runCatching { exportPicker.launch("neonline-progresso-${System.currentTimeMillis()}.json") }.onFailure { backups.pickerFailed() }
                        } },
                        onDiagnostic = { backups.prepareExport(diagnostic = true) {
                            runCatching { exportPicker.launch("neonline-diagnostico-${System.currentTimeMillis()}.json") }.onFailure { backups.pickerFailed() }
                        } },
                        onReset = backups::requestReset,
                        onImport = { runCatching { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { backups.pickerFailed() } },
                        onChange = { updated ->
                            container.save(ProfileReducer.withSettings(container.profile(), updated))
                            settings = updated
                        },
                    )
                    Screen.Rivals -> pt.tiago.neonline.ui.screens.RivalsScreen(container.profile(),
                        remember { container.catalog.rivalLevels() }, onChallenge = ::openGame, onFreePlay = { screen = Screen.FreeSetup(it) }, onBack = { screen = Screen.Home })
                    Screen.Achievements -> pt.tiago.neonline.ui.screens.AchievementsScreen(container.profile()) { screen = Screen.Home }
                    Screen.Statistics -> pt.tiago.neonline.ui.screens.StatisticsScreen(
                        profile = container.profile(), levels = packagedLevels,
                        onBack = { screen = Screen.Home },
                        onImprove = { difficulty ->
                            val profile = container.profile()
                            val updated = profile.settings.copy(lastDifficulty = difficulty)
                            container.save(ProfileReducer.withSettings(profile, updated))
                            settings = updated
                            screen = Screen.Campaign(missingStarsOnly = true)
                        },
                    )
                    is Screen.Campaign -> CampaignScreen(
                        levels = packagedLevels,
                        metadata = remember { container.catalog.campaignLevels() },
                        resumable = container.resumableMatch()?.second,
                        reducedMotion = settings.reducedMotion,
                        profile = container.profile(),
                        initialMissingStarsOnly = current.missingStarsOnly,
                        initialStop = current.stop, initialDifficulty = current.difficulty,
                        onBack = { screen = Screen.Home },
                        onDifficulty = { difficulty ->
                            val profile = container.profile()
                            val updated = profile.settings.copy(lastDifficulty = difficulty)
                            container.save(ProfileReducer.withSettings(profile, updated))
                            settings = updated
                        },
                        onPlay = { ordinal, difficulty -> openGame(ordinal, difficulty) },
                    )
                    is Screen.Game -> key(session) {
                        val level = remember { container.levelState(current.ordinal, current.difficulty) }
                        if (level == null) {
                            LaunchedEffect(Unit) {
                                showToast(getString(R.string.content_unavailable))
                                screen = Screen.Home
                            }
                        } else {
                            val (info, start) = level
                            val rival = remember { container.rivalProfile(info, current.difficulty) }
                            val owner = remember {
                                object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                            }
                            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                            val saved = remember {
                                container.resumableMatch()?.second?.takeIf {
                                    it.levelId == info.id && it.difficulty == current.difficulty
                                }
                            }
                            var newAchievements by remember { mutableStateOf<List<String>>(emptyList()) }
                            var newSkins by remember { mutableStateOf<List<pt.tiago.neonline.ui.art.BoardSkin>>(emptyList()) }
                            val model: pt.tiago.neonline.game.GameViewModel = viewModel(
                                viewModelStoreOwner = owner,
                                key = "${info.id}:${current.difficulty}:$session",
                                factory = pt.tiago.neonline.game.GameViewModel.factory(
                                    state = start,
                                    objective = info.objective(),
                                    rival = rival,
                                    difficulty = current.difficulty,
                                    hints = info.hints,
                                    tutorial = info.tutorial,
                                    animationEnabled = !container.profile().settings.reducedMotion && android.animation.ValueAnimator.areAnimatorsEnabled(),
                                    bossRoundStates = info.bossRoundStates,
                                    confirmMove = settings.confirmMove,
                                    aiDelayMs = if (settings.aiSpeed == "fast") 0L else pt.tiago.neonline.game.AnimationTimeline.AI_MIN_THINK_MS,
                                    savedMatch = saved,
                                    onCheckpoint = { match, outcome, moves ->
                                        val earned = container.checkpoint(current.ordinal, info.hints, match, outcome, moves, info.rival)
                                        if (outcome == null) { newAchievements = emptyList(); newSkins = emptyList() }
                                        else { if (earned.achievements.isNotEmpty()) newAchievements = earned.achievements
                                            if (earned.skins.isNotEmpty()) newSkins = earned.skins }
                                    },
                                ),
                            )
                            GameScreen(
                                viewModel = model,
                                settings = settings,
                                onSoundChange = { changeFeedback(sound = it) }, onVibrationChange = { changeFeedback(vibration = it) },
                                newAchievements = newAchievements,
                                newSkins = newSkins,
                                skin = remember { pt.tiago.neonline.data.BoardThemes.resolve(settings.skin, container.profile().totalStars()) },
                                start = start,
                                objective = info.objective(),
                                rivalProfile = rival,
                                difficulty = current.difficulty,
                                hints = info.hints,
                                levelTitle = if (resources.configuration.locales[0].language == "pt") info.titlePt else info.titleEn,
                                rivalName = pt.tiago.neonline.ui.screens.rivalName(info.rival),
                                onExit = { screen = Screen.Campaign() },
                                onNext = if (packagedLevels.any { it.ordinal == current.ordinal + 1 }) {
                                    { openGame(current.ordinal + 1, current.difficulty) }
                                } else null,

                            )
                        }
                    }
                }
                if (backups.resetRequested) {
                    AlertDialog(
                        onDismissRequest = backups::cancelReset,
                        title = { Text(stringResource(R.string.reset_title)) },
                        text = { Text(stringResource(R.string.reset_confirm_message)) },
                        confirmButton = { TextButton(enabled = !backups.busy, onClick = backups::confirmReset) {
                            Text(stringResource(R.string.reset_confirm), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        } },
                        dismissButton = { TextButton(enabled = !backups.busy, onClick = backups::cancelReset) { Text(stringResource(R.string.action_cancel)) } },
                    )
                }
                backups.candidate?.let { candidate ->
                    AlertDialog(
                        onDismissRequest = { if (!backups.busy) backups.cancelImport() },
                        title = { Text(stringResource(R.string.backup_confirm_title)) },
                        text = {
                            val completed = candidate.progressByDifficulty.values.sumOf { it.completedVariantIds.size }
                            Text(stringResource(R.string.backup_confirm_message, completed, candidate.totalStars()) +
                                (if (backups.savedUnavailable) "\n\n" + stringResource(R.string.backup_saved_unavailable) else "") +
                                (if (backups.localUnavailable) "\n\n" + stringResource(R.string.local_backup_unavailable) else "") +
                                (if (backups.dailyUnavailable) "\n\n" + stringResource(R.string.daily_import_unavailable) else "") +
                                (if (backups.freeUnavailable) "\n\n" + stringResource(R.string.free_import_unavailable) else "") +
                                (if (backups.tournamentUnavailable) "\n\n" + stringResource(R.string.tournament_import_unavailable) else ""))
                        },
                        confirmButton = { TextButton(enabled = !backups.busy, onClick = {
                            backups.confirmImport()
                        }) { Text(stringResource(R.string.backup_import)) } },
                        dismissButton = { TextButton(enabled = !backups.busy, onClick = backups::cancelImport) { Text(stringResource(R.string.action_cancel)) } },
                    )
                }
                pendingIntroduction?.let { brief ->
                    pt.tiago.neonline.ui.screens.LevelIntroductionSheet(brief,
                        onDismiss = { pendingIntroduction = null },
                        onPlay = { pendingIntroduction = null; startGame(brief.ordinal,brief.difficulty) })
                }
                pendingGame?.let { target ->
                    AlertDialog(
                        onDismissRequest = { pendingGame = null },
                        title = { Text(stringResource(R.string.replace_match_title)) },
                        text = { Text(stringResource(R.string.replace_match_message)) },
                        confirmButton = {
                            TextButton(onClick = { pendingGame = null; startGame(target.ordinal,target.difficulty,replaceConfirmed = true) }) {
                                Text(stringResource(R.string.game_play))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingGame = null }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class FreeSetup(val rival: String? = null, val config: pt.tiago.neonline.data.FreePlayConfig? = null) : Screen
    data class FreeGame(val config: pt.tiago.neonline.data.FreePlayConfig, val fromGallery: Boolean = false) : Screen
    data object Rush : Screen
    data object TournamentSetup : Screen
    data object TournamentGame : Screen
    data object Daily : Screen
    data class DailyGame(val date: String, val ordinal: Int, val difficulty: Difficulty, val poolVersion: Int, val sha: String) : Screen
    data object LocalSetup : Screen
    data class LocalGame(val config: pt.tiago.neonline.data.LocalConfig) : Screen
    data object Settings : Screen
    data object Statistics : Screen
    data object Rivals : Screen
    data object Achievements : Screen
    data class Campaign(val missingStarsOnly: Boolean = false, val stop: Int? = null, val difficulty: Difficulty? = null) : Screen
    data class Game(val ordinal: Int, val difficulty: Difficulty = Difficulty.EASY) : Screen
}
