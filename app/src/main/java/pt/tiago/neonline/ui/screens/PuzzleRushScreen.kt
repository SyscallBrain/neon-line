package pt.tiago.neonline.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pt.tiago.neonline.R
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*
import pt.tiago.neonline.game.AudioController
import pt.tiago.neonline.game.GameViewModel
import pt.tiago.neonline.ui.art.BoardSkin

data class RushRoundSource(val start: GameState, val objective: LevelObjective, val rival: AiProfile, val rivalName: String)

@Composable
fun PuzzleRushScreen(profile: Profile, settings: Settings, skin: BoardSkin, pool: List<RushPuzzle>,
    load: (RushPuzzle) -> RushRoundSource?, onRecord: (Difficulty, Int) -> Profile,
    onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit, onBack: () -> Unit) {
    var difficulty by remember { mutableStateOf(settings.lastDifficulty) }
    var run by remember { mutableStateOf<RushRun?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var between by remember { mutableStateOf(false) }
    var lastWon by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(profile.rushBest) }
    var earnedBadges by remember { mutableStateOf(profile.achievements.map { it.id }.toSet()) }
    var newAchievement by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audio = remember { AudioController(context) }
    SideEffect { audio.configure(settings) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    LaunchedEffect(run?.startedAtMs) {
        while(run?.end == null && run != null) {
            now = SystemClock.elapsedRealtime()
            run = run?.let { PuzzleRush.tick(it,now) }
            delay(100)
        }
    }
    LaunchedEffect(run?.attempt,between) { if(between) { delay(650); between=false } }
    val current = run
    BackHandler {
        when {
            current == null -> onBack()
            current.end != null -> run=null
            else -> run=PuzzleRush.stop(current)
        }
    }
    if(current != null && current.end == null && !between) {
        val remaining = (PuzzleRush.remainingMs(current,now)+999L)/1000L
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(pluralStringResource(R.plurals.rush_hud,current.solved,current.solved,current.errors,PuzzleRush.errorLimit(current.difficulty),remaining/60,remaining%60),
                modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.titleMedium)
            val puzzle = PuzzleRush.choose(pool,current)
            key(current.startedAtMs,current.attempt) {
                val source = remember(puzzle) { puzzle?.let(load) }
                if(source == null || puzzle == null) {
                    LaunchedEffect(Unit) { unavailable=true;run=run?.let(PuzzleRush::stop) }
                } else RushRoundContent(source,puzzle,current.difficulty,settings,skin,
                    onSoundChange,onVibrationChange,onExit={run=run?.let(PuzzleRush::stop)},onFinished={outcome ->
                        val previous=run
                        if(previous!=null) {
                            val updated=PuzzleRush.complete(previous,current.attempt,outcome is LevelOutcome.Won,SystemClock.elapsedRealtime())
                            if(updated.attempt!=previous.attempt) {
                                lastWon=outcome is LevelOutcome.Won
                                audio.playResult(outcome,false)
                                if(updated.solved>previous.solved) {
                                    val recorded = onRecord(updated.difficulty,updated.solved)
                                    val badge = RushAchievements.progress(recorded, updated.difficulty)
                                    if (badge.unlocked && badge.id !in earnedBadges) newAchievement = true
                                    earnedBadges = recorded.achievements.map { it.id }.toSet()
                                    records = recorded.rushBest
                                }
                                between=true
                            }
                            run=updated
                        }
                    })
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(current == null) {
            ScreenHeader(stringResource(R.string.rush_title),onBack)
            Text(stringResource(R.string.rush_note,pool.size))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Difficulty.entries.forEach { choice ->
                    FilterChip(difficulty==choice,{difficulty=choice},label={Text(stringResource(choice.labelResource()))})
                }
            }
            Text(stringResource(R.string.rush_rules,PuzzleRush.durationMs(difficulty)/60000,PuzzleRush.errorLimit(difficulty)))
            Text(stringResource(R.string.rush_best,records[difficulty] ?: 0))
            if(!PuzzleRush.ready(pool)) Text(stringResource(R.string.rush_unavailable))
            Button(enabled=PuzzleRush.ready(pool),onClick={
                now=SystemClock.elapsedRealtime();between=false;unavailable=false;newAchievement=false
                run=RushRun(difficulty,kotlin.random.Random.nextLong().toULong().toString(16).padStart(16,'0'),now)
            }) { Text(stringResource(R.string.rush_start)) }
        } else if(current.end == null) {
            Text(stringResource(if(lastWon) R.string.rush_correct else R.string.rush_error),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.rush_score,current.solved))
        } else {
            Text(stringResource(R.string.rush_finished),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(when(current.end) { RushEnd.TIME -> R.string.rush_time_up; RushEnd.ERRORS -> R.string.rush_errors_up; else -> R.string.rush_stopped }))
            if(unavailable) Text(stringResource(R.string.rush_unavailable))
            Text(stringResource(R.string.rush_score,current.solved))
            Text(stringResource(R.string.rush_best,records[current.difficulty] ?: 0))
            if (newAchievement) {
                Text(stringResource(R.string.rush_achievement_unlocked), style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick={run=null}) { Text(stringResource(R.string.rush_again)) }
            TextButton(onClick=onBack) { Text(stringResource(R.string.action_exit)) }
        }
    }
}

@Composable
private fun RushRoundContent(source: RushRoundSource, puzzle: RushPuzzle, rushDifficulty: Difficulty,
    settings: Settings, skin: BoardSkin, onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit,
    onExit: () -> Unit, onFinished: (LevelOutcome) -> Unit) {
    val owner=remember { object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val currentFinished by rememberUpdatedState(onFinished)
    val model:GameViewModel=viewModel(viewModelStoreOwner=owner,factory=object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>):T = GameViewModel(source.start,source.objective,source.rival,
            hintsAvailable=0,undoAvailable=0,animationEnabled=false,confirmMove=settings.confirmMove,aiDelayMs=0L,
            onCheckpoint={_,outcome,_->if(outcome!=null) currentFinished(outcome)}) as T
    })
    GameScreen(source.start,settings,source.objective,source.rival,rushDifficulty,0,
        stringResource(R.string.rush_goal,puzzle.moves),source.rivalName,onExit,onSoundChange,onVibrationChange,
        showStars=false,allowRestart=false,showResult=false,showRivalThreats=false,skin=skin,viewModel=model)
}
