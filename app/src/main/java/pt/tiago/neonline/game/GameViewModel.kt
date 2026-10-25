package pt.tiago.neonline.game

import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto
import pt.tiago.neonline.data.SavedMatch
import pt.tiago.neonline.data.SavedBossRound
import pt.tiago.neonline.core.objectives.BossSeries
import pt.tiago.neonline.data.SavedPly
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.tiago.neonline.core.ai.AiChoice
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.solver.BoundedWinSolver
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LossReasons
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.MoveResult
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.objectives.LevelObjective
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.objectives.LevelEvaluator
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

/** What the game screen renders. */
data class GameUiState(
    val state: GameState,
    val localPlayers: Boolean = false,
    val events: List<GameEvent> = emptyList(),
    val rivalThinking: Boolean = false,
    val paused: Boolean = false,
    val hintsLeft: Int = 0,
    val undosLeft: Int = 0,
    val hintColumn: Int? = null,
    val hintMove: Move? = null,
    val hintUnavailable: Boolean = false,
    val selectedPiece: PieceKind = PieceKind.NORMAL,
    val pendingPopColumn: Int? = null,
    val pendingDrop: Move.Drop? = null,
    val clockMs: Long? = null,
    val outcome: LevelOutcome? = null,
    val humanMoveCount: Int = 0,
    val bossSeries: BossSeries? = null,
    val bossRoundIndex: Int = 0,
    val roundOutcome: MatchOutcome? = null,
    val lastAiNodes: Int = 0,
    val animation: BoardAnimation? = null,
    val animating: Boolean = false,
    val motionEnabled: Boolean = true,
    val tutorial: TutorialDto? = null,
    val tutorialDismissed: Boolean = false,
    val peekColumn: Int? = null,
    val peekCount: Int = 0,
    val peekedColumns: Set<Int> = emptySet(),
    val rivalMoveCount: Int = 0,
) {
    val tutorialStep: TutorialStepDto? get() = if (tutorialDismissed || finished || animating || state.toMove != pt.tiago.neonline.core.model.Player.P1) null
        else tutorial?.steps?.getOrNull(humanMoveCount)
    val acceptsInput: Boolean get() = !finished && !paused && !animating && !rivalThinking && (localPlayers || state.toMove == pt.tiago.neonline.core.model.Player.P1)
    val finished: Boolean get() = outcome != null || state.status != Status.InProgress
}

/**
 * Drives one match: the player's moves, the rival's deterministic answers (with a minimum "thinking"
 * delay so the move is readable), the hint, the undo of Easy and the per-move clock.
 *
 * The clock lives here, not in the engine: the engine only ever receives [Move.TimeUp].
 */
class GameViewModel(
    private val state: GameState,
    private val objective: LevelObjective,
    private val rival: AiProfile,
    private val difficulty: Difficulty = state.difficulty,
    private val hintsAvailable: Int = 0,
    private val undoAvailable: Int = 0,
    private val clockSeconds: Int? = state.moveClockSec,
    private val animationEnabled: Boolean = true,
    private val confirmMove: Boolean = false,
    private val aiDelayMs: Long = if (animationEnabled) AnimationTimeline.AI_MIN_THINK_MS else 0L,
    private val tutorial: TutorialDto? = null,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val savedMatch: SavedMatch? = null,
    private val bossRoundStates: List<GameState> = emptyList(),
    private val maxPlies: Int? = null,
    private val hintProfile: AiProfile = AiProfile.HARD,
    private val exactEndgameRival: Boolean = false,
    private val perfectRival: Boolean = false,
    private val openingBook: PerfectOpeningBook? = null,
    private val openingBookLoader: (() -> PerfectOpeningBook?)? = null,
    private val perfectSearchTimeoutMs: Long = 4_000L,
    private val perfectSearchLogger: suspend (String) -> Unit = { android.util.Log.i("NeonLine", it); Unit },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onCheckpoint: (SavedMatch, LevelOutcome?, Int) -> Unit = { _, _, _ -> },
) : ViewModel() {

    private val activeTime = ActivePlayTime(savedMatch?.elapsedMs ?: 0L, elapsedRealtimeMs)
    private val matchOpeningBook by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { openingBook ?: openingBookLoader?.invoke() }
    private val isBoss = objective.kind == LevelKind.BOSS
    private val roundStarts = if (isBoss) bossRoundStates.also { require(it.size == 3 && it.first() == state) } else emptyList()
    private var roundStart = state
    private var roundHintsStart = hintsAvailable
    private val bossHistory = mutableListOf<SavedBossRound>()

    private val _ui = MutableStateFlow(
        GameUiState(
            state = state, motionEnabled = animationEnabled, tutorial = tutorial, bossSeries = if (isBoss) BossSeries() else null,
            hintsLeft = hintsAvailable,
            undosLeft = undoAvailable,
            clockMs = clockSeconds?.let { it * 1000L },
        ),
    )
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    private val undoStack = mutableListOf<TurnCheckpoint>()
    private val steps = mutableListOf<SavedPly>()
    private var animationId = 0L
    private var hintJob: Job? = null
    private var clockJob: Job? = null
    private var aiJob: Job? = null
    private var peekJob: Job? = null

    init {
        require(maxPlies == null || maxPlies > 0)
        require(savedMatch == null || maxPlies == null || savedMatch.steps.size < maxPlies)
        require(!perfectRival || perfectSearchTimeoutMs > 0L)
        val bossRestore = if (isBoss && savedMatch != null) BossReplay.restore(roundStarts, savedMatch, hintsAvailable, undoAvailable) else null
        val restored = if (isBoss) bossRestore?.active else savedMatch?.takeIf { it.bossHistory.isEmpty() }?.let {
            if (objective.kind == LevelKind.DEFENSE)
                MatchReplay.restoreDefense(state, it, hintsAvailable, undoAvailable, requireNotNull(objective.surviveAiMoves))
            else MatchReplay.restore(state, it, hintsAvailable, undoAvailable)
        }
        require(savedMatch == null || restored != null) { "Cannot restore saved match" }
        if (restored != null && savedMatch != null) {
            steps += savedMatch.steps
            undoStack += restored.undoPoints
            bossHistory += savedMatch.bossHistory
            roundHintsStart = bossHistory.lastOrNull()?.hintsLeft ?: hintsAvailable
            roundStart = bossRestore?.start ?: state
            _ui.value = _ui.value.copy(
                state = restored.state, humanMoveCount = restored.humanMoves,
                rivalMoveCount = savedMatch.steps.size - restored.humanMoves,
                hintsLeft = savedMatch.hintsLeft, undosLeft = savedMatch.undosLeft,
                clockMs = savedMatch.clockMs, tutorialDismissed = savedMatch.tutorialDismissed,
                bossSeries = bossRestore?.series, bossRoundIndex = bossRestore?.roundIndex ?: 0,
                roundOutcome = bossRestore?.roundOutcome, peekCount = savedMatch.peekCount,
            )
        }
        checkpoint()
        resume()
    }

    fun snapshot(): SavedMatch = SavedMatch(
        levelId = state.levelId, difficulty = difficulty,
        initialHash = StateHasher.hash(roundStart), stateHash = StateHasher.hash(_ui.value.state),
        steps = steps.toList(), hintsLeft = _ui.value.hintsLeft,
        undosLeft = _ui.value.undosLeft, clockMs = _ui.value.clockMs,
        tutorialDismissed = _ui.value.tutorialDismissed, bossHistory = bossHistory.toList(), peekCount = _ui.value.peekCount,
        elapsedMs = activeTime.elapsedMs,
    )

    /** Result review is read-only and stays scoped to the current boss duel. */
    fun reviewInput(): TacticalReviewInput? = if (_ui.value.outcome == null && _ui.value.roundOutcome == null) null else
        TacticalReviewInput(roundStart, snapshot(), roundHintsStart, undoAvailable,
            objective.winInMoves.takeIf { objective.kind == LevelKind.PUZZLE })

    private fun checkpoint() {
        if (_ui.value.finished || _ui.value.roundOutcome != null) activeTime.pause()
        onCheckpoint(snapshot(), _ui.value.outcome, _ui.value.bossSeries?.totalMoves ?: _ui.value.humanMoveCount)
    }

    fun peek(column: Int) {
        if (!PeekVisibility.canPeek(_ui.value, column)) return
        peekJob?.cancel()
        val current = _ui.value
        _ui.value = current.copy(peekColumn = column, peekedColumns = current.peekedColumns + column,
            peekCount = (current.peekCount + 1).coerceAtMost(PeekVisibility.MAX_COUNT), pendingDrop = null, pendingPopColumn = null)
        checkpoint()
        peekJob = viewModelScope.launch {
            delay(PeekVisibility.DURATION_MS)
            _ui.value = _ui.value.copy(peekColumn = null)
        }
    }

    private fun cancelPeek() {
        peekJob?.cancel()
        _ui.value = _ui.value.copy(peekColumn = null, peekedColumns = emptySet())
    }

    fun restart() {
        activeTime.reset()
        cancelPeek()
        aiJob?.cancel()
        clockJob?.cancel()
        hintJob?.cancel()
        undoStack.clear()
        steps.clear()
        bossHistory.clear()
        roundStart = state
        roundHintsStart = hintsAvailable
        _ui.value = GameUiState(
            state = state, motionEnabled = animationEnabled, tutorial = tutorial, bossSeries = if (isBoss) BossSeries() else null, hintsLeft = hintsAvailable, undosLeft = undoAvailable,
            clockMs = clockSeconds?.let { it * 1000L },
        )
        checkpoint()
        resume()
    }

    /** Plays the player's move, then the rival's answer. */
    fun play(move: Move) {
        val current = _ui.value
        if (!current.acceptsInput) return
        val forced = current.tutorialStep?.forcedCol
        if (forced != null && move != Move.Drop(forced)) return
        val legal = GameEngine.validMoves(current.state)
        if (move !in legal) return
        val result = GameEngine.apply(current.state, move)
        if (result.events.any { it is GameEvent.MoveRejected }) return
        undoStack += TurnCheckpoint(current.state, current.humanMoveCount, steps.size)
        applyMove(move, result)
    }

    fun selectPiece(kind: PieceKind) {
        val current = _ui.value
        if (!current.acceptsInput || current.tutorialStep?.forcedCol != null) return
        if (kind != PieceKind.NORMAL && (current.state.inventory[current.state.toMove]?.get(kind) ?: 0) <= 0) return
        _ui.value = current.copy(selectedPiece = kind, pendingPopColumn = null, pendingDrop = null, hintColumn = null, hintMove = null, hintUnavailable = false)
    }

    /** A confirmation is transient; only the second matching tap becomes a saved move. */
    fun playColumn(column: Int) {
        val current = _ui.value
        if (!current.acceptsInput) return
        val move = Move.Drop(column, current.selectedPiece)
        if (current.tutorialStep?.forcedCol?.let { it != column } == true) return
        if (move !in GameEngine.validMoves(current.state)) return
        if (GameEngine.apply(current.state, move).events.any { it is GameEvent.MoveRejected }) return
        if (!confirmMove || current.pendingDrop == move) play(move)
        else _ui.value = current.copy(pendingDrop = move, pendingPopColumn = null, hintColumn = null, hintMove = null)
    }

    fun requestPop(column: Int) {
        val current = _ui.value
        if (!current.acceptsInput || current.tutorialStep?.forcedCol != null) return
        if (!BoardAccessibility.canPop(current, column)) return
        _ui.value = current.copy(pendingPopColumn = column, pendingDrop = null, selectedPiece = PieceKind.NORMAL, hintColumn = null, hintMove = null, hintUnavailable = false)
    }

    fun cancelPop() { _ui.value = _ui.value.copy(pendingPopColumn = null) }

    fun confirmPop() {
        val current = _ui.value
        if (!current.acceptsInput) return
        val col = current.pendingPopColumn ?: return
        _ui.value = current.copy(pendingPopColumn = null)
        play(Move.Pop(col))
    }

    /** The clock ran out for the player; the engine picks the column from its stream. */
    fun timeUp() {
        val current = _ui.value
        if (current.finished || current.paused || current.animating || current.state.toMove != pt.tiago.neonline.core.model.Player.P1) return
        val result = GameEngine.apply(current.state, Move.TimeUp)
        if (result.events.any { it is GameEvent.MoveRejected }) return
        undoStack += TurnCheckpoint(current.state, current.humanMoveCount, steps.size)
        applyMove(Move.TimeUp, result)
    }

    fun pause(persist: Boolean = true) {
        activeTime.pause()
        cancelPeek()
        _ui.value = _ui.value.copy(paused = true)
        clockJob?.cancel()
        aiJob?.cancel()
        hintJob?.cancel()
        _ui.value = _ui.value.copy(rivalThinking = false, hintColumn = null, hintMove = null, pendingPopColumn = null, pendingDrop = null, hintUnavailable = false)
        if (persist && !_ui.value.finished) checkpoint()
    }

    fun resume() {
        val current = _ui.value
        _ui.value = current.copy(paused = false)
        if (current.finished && !current.animating) return
        if (!current.finished && current.roundOutcome == null) activeTime.resume()
        continueTurn()
    }

    fun dismissTutorial() {
        if (!_ui.value.acceptsInput) return
        _ui.value = _ui.value.copy(tutorialDismissed = true)
        checkpoint()
    }

    /** The renderer acknowledges one animation; stale callbacks never advance a later turn. */
    fun completeAnimation(id: Long) {
        val current = _ui.value
        if (current.paused || !current.animating || current.animation?.id != id) return
        _ui.value = current.copy(animating = false)
        continueTurn()
    }

    private fun animate(before: GameState, events: List<GameEvent>) {
        clockJob?.cancel()
        val timeline = AnimationTimeline.build(before, _ui.value.state, events)
        val animation = if (animationEnabled && timeline.totalMs > 0) BoardAnimation(++animationId, timeline) else null
        _ui.value = _ui.value.copy(animation = animation, animating = animation != null, hintColumn = null)
    }

    private fun continueTurn() {
        val current = _ui.value
        if (current.finished || current.paused || current.animating) return
        if (current.state.toMove == pt.tiago.neonline.core.model.Player.P2) startRivalTurn() else startClock()
    }

    fun togglePause() = if (_ui.value.paused) resume() else pause()

    /** Computes a hint off the UI thread without consuming one when no move is available. */
    fun hint() {
        val current = _ui.value
        if (current.hintsLeft <= 0 || !current.acceptsInput || current.tutorialStep != null || hintJob?.isActive == true) return
        hintJob = viewModelScope.launch {
            val move = withContext(searchDispatcher) { suggestedMove(current.state, objective.winInMoves?.minus(current.humanMoveCount)) }
            if (move == null) {
                if (_ui.value.state === current.state && !_ui.value.paused) {
                    _ui.value = _ui.value.copy(hintUnavailable = true)
                    delay(2_000)
                    _ui.value = _ui.value.copy(hintUnavailable = false)
                }
                return@launch
            }
            val column = when (move) { is Move.Drop -> move.col; is Move.Pop -> move.col; Move.TimeUp -> return@launch }
            if (_ui.value.state !== current.state || _ui.value.paused) return@launch
            _ui.value = _ui.value.copy(hintsLeft = current.hintsLeft - 1, hintColumn = column, hintMove = move, hintUnavailable = false,
                selectedPiece = (move as? Move.Drop)?.kind ?: PieceKind.NORMAL,
                pendingPopColumn = (move as? Move.Pop)?.col, pendingDrop = null)
            checkpoint()
            delay(1_000)
            _ui.value = _ui.value.copy(hintColumn = null, hintMove = null)
        }
    }

    /** Restores the entire state before the player's move, including the deterministic streams. */
    fun undo() {
        val current = _ui.value
        if (current.undosLeft <= 0 || !current.acceptsInput) return
        val restored = undoStack.removeLastOrNull() ?: return
        cancelPeek()
        clockJob?.cancel()
        hintJob?.cancel()
        while (steps.size > restored.stepCount) steps.removeAt(steps.lastIndex)
        _ui.value = current.copy(
            state = restored.state, humanMoveCount = restored.humanMoves,
            rivalMoveCount = restored.stepCount - restored.humanMoves, peekColumn = null, peekedColumns = emptySet(),
            outcome = null,
            hintsLeft = current.hintsLeft,
            undosLeft = current.undosLeft - 1,
            hintColumn = null, hintMove = null, hintUnavailable = false, selectedPiece = PieceKind.NORMAL, pendingPopColumn = null, pendingDrop = null, events = emptyList(), animation = null, animating = false,
            clockMs = clockSeconds?.let { it * 1000L },
        )
        checkpoint()
        startClock()
    }

    private fun applyMove(move: Move, result: MoveResult = GameEngine.apply(_ui.value.state, move)) {
        cancelPeek()
        val current = _ui.value
        steps += MatchReplay.step(current.state, move, result.state)
        _ui.value = current.copy(
            state = result.state,
            events = result.events,
            selectedPiece = PieceKind.NORMAL, pendingPopColumn = null, pendingDrop = null, hintMove = null, hintUnavailable = false,
            humanMoveCount = if (current.state.toMove == pt.tiago.neonline.core.model.Player.P1) {
                current.humanMoveCount + 1
            } else {
                current.humanMoveCount
            },
            rivalMoveCount = current.rivalMoveCount + if (current.state.toMove == Player.P2) 1 else 0,
        )
        animate(current.state, result.events)
        val outcome = outcomeAfterMove(result)
        if (outcome != null) {
            finish(result.state, outcome)
            return
        }
        val puzzleLimit = objective.winInMoves
        if (objective.kind == LevelKind.PUZZLE && puzzleLimit != null && _ui.value.humanMoveCount >= puzzleLimit) {
            clockJob?.cancel()
            _ui.value = _ui.value.copy(outcome = LevelOutcome.Lost(LossReasons.OUT_OF_MOVES), clockMs = null)
            checkpoint()
            return
        }
        checkpoint()
        continueTurn()
    }

    private fun startRivalTurn() {
        cancelPeek()
        aiJob?.cancel()
        _ui.value = _ui.value.copy(rivalThinking = true)
        clockJob?.cancel()
        aiJob = viewModelScope.launch {
            val snapshot = _ui.value.state
            val started = System.currentTimeMillis()
            val perfectSearchStartedNanos = System.nanoTime()
            var perfectSearchResult: PerfectRivalResult? = null
            var perfectSearchTimedOut = false
            val searched = withContext(searchDispatcher) {
                if (perfectRival) {
                    var exactNodesBeforeTimeout = 0L
                    val exact = withTimeoutOrNull(perfectSearchTimeoutMs) {
                        val context = currentCoroutineContext()
                        PerfectRivalSearch.search(snapshot, rival, matchOpeningBook,
                            checkCancelled = { context.ensureActive() },
                            onExactNodes = { exactNodesBeforeTimeout = it })
                    }
                    perfectSearchTimedOut = exact == null
                    val context = currentCoroutineContext()
                    val result = exact ?: PerfectRivalSearch.search(snapshot, rival, maxExactNodes = 1L,
                        checkCancelled = { context.ensureActive() }).let { fallback ->
                        fallback.copy(nodesExplored = (exactNodesBeforeTimeout + fallback.nodesExplored)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    perfectSearchResult = result
                    EndgameRivalResult(result.choice, result.nodesExplored)
                } else if (exactEndgameRival) {
                    val context = currentCoroutineContext()
                    EndgameRivalSearch.search(snapshot, rival, true, checkCancelled = { context.ensureActive() })
                } else {
                    val search = AiSearch(rival)
                    EndgameRivalResult(search.chooseMove(snapshot), search.nodesExplored)
                }
            }
            if (perfectRival) {
                val result = requireNotNull(perfectSearchResult)
                val source = when {
                    result.usedBook -> "book"
                    result.exact -> "exact_solver"
                    else -> "heuristic_fallback"
                }
                val elapsedMs = (System.nanoTime() - perfectSearchStartedNanos) / 1_000_000L
                perfectSearchLogger("perfect_search ply=${snapshot.ply} elapsed_ms=$elapsedMs source=$source exact=${result.exact} timed_out=$perfectSearchTimedOut nodes=${searched.nodesExplored}")
            }
            val choice = searched.choice
            if (choice == null) {
                _ui.value = _ui.value.copy(rivalThinking = false)
                return@launch
            }
            // The rival "thinks" for at least 450 ms even when it answers at once.
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < aiDelayMs) {
                delay(aiDelayMs - elapsed)
            }
            val current = _ui.value
            if (current.paused || current.state !== snapshot) {
                _ui.value = _ui.value.copy(rivalThinking = false)
                return@launch
            }
            val before = snapshot.copy(aiStateHex = choice.aiStateHex)
            val result = GameEngine.apply(before, choice.move)
            steps += MatchReplay.step(before, choice.move, result.state)
            _ui.value = current.copy(
                state = result.state,
                events = result.events,
                rivalThinking = false,
                rivalMoveCount = current.rivalMoveCount + 1,
                lastAiNodes = searched.nodesExplored,
                clockMs = clockSeconds?.let { it * 1000L },
            )
            animate(snapshot, result.events)
            val rivalOutcome = outcomeAfterMove(result)
            if (rivalOutcome != null) finish(result.state, rivalOutcome) else { checkpoint(); continueTurn() }
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        if (clockSeconds == null) return
        if (_ui.value.clockMs == 0L) { timeUp(); return }
        clockJob = viewModelScope.launch {
            while (_ui.value.clockMs != null && _ui.value.clockMs!! > 0 && !_ui.value.finished) {
                delay(100)
                val current = _ui.value
                if (current.paused) continue
                if (current.state.toMove != pt.tiago.neonline.core.model.Player.P1) continue
                val left = (current.clockMs ?: 0) - 100
                _ui.value = current.copy(clockMs = left.coerceAtLeast(0))
                if (left <= 0) timeUp()
                else if (left % 1000L == 0L) checkpoint()
            }
        }
    }

    /** Optional mode-specific cap; real engine outcomes on the final move take precedence. */
    private fun outcomeAfterMove(result: MoveResult): MatchOutcome? = result.outcome ?: when {
        objective.kind == LevelKind.DEFENSE && objective.surviveAiMoves?.let { _ui.value.rivalMoveCount >= it } == true -> MatchOutcome.Draw("defense_survived")
        maxPlies != null && steps.size >= maxPlies -> MatchOutcome.Draw("move_limit")
        else -> null
    }

    private fun finish(state: GameState, matchOutcome: MatchOutcome) {
        clockJob?.cancel()
        val current = _ui.value
        if (isBoss) {
            val series = requireNotNull(current.bossSeries).record(matchOutcome, current.humanMoveCount, roundHintsStart - current.hintsLeft)
            _ui.value = current.copy(bossSeries = series, roundOutcome = matchOutcome, outcome = series.result(objective), rivalThinking = false, clockMs = null)
        } else {
            val threat = objective.kind == LevelKind.DEFENSE && state.status == Status.InProgress &&
                GameEngine.validMoves(state.copy(toMove = Player.P1)).any { GameEngine.apply(state.copy(toMove = Player.P1), it).state.status == Status.Won(Player.P1) }
            val outcome = LevelEvaluator.evaluate(objective, matchOutcome, current.humanMoveCount, hintsAvailable - current.hintsLeft, threatAtEnd = threat)
            _ui.value = current.copy(outcome = outcome, rivalThinking = false, clockMs = null)
        }
        checkpoint()
    }

    fun nextBossRound() {
        val current = _ui.value
        if (!isBoss || current.roundOutcome == null || current.outcome != null || current.animating || current.paused) return
        val saved = snapshot()
        bossHistory += SavedBossRound(saved.initialHash, saved.stateHash, saved.steps, saved.hintsLeft, saved.undosLeft)
        cancelPeek()
        aiJob?.cancel(); clockJob?.cancel(); hintJob?.cancel()
        steps.clear(); undoStack.clear()
        val slot = requireNotNull(current.bossSeries).roundIndex
        roundStart = roundStarts[slot]
        roundHintsStart = current.hintsLeft
        _ui.value = GameUiState(state = roundStart, motionEnabled = animationEnabled, bossSeries = current.bossSeries, bossRoundIndex = slot,
            hintsLeft = current.hintsLeft, peekCount = current.peekCount, undosLeft = undoAvailable, clockMs = clockSeconds?.let { it * 1000L })
        checkpoint()
        resume()
    }

    /** Preserve the whole action: a pop or a special-piece hint is not a normal drop. */
    private fun suggestedMove(state: GameState, movesLeft: Int?): Move? {
        if (state.status != Status.InProgress) return null
        val move = if (objective.kind == LevelKind.PUZZLE) {
            BoundedWinSolver().winningMoves(state, movesLeft ?: return null)?.firstOrNull()
        } else AiSearch(hintProfile).chooseMove(state)?.move
        if (move == null) return null
        return move.takeIf { GameEngine.apply(state, it).events.none { event -> event is GameEvent.MoveRejected } }
    }

    /** Restores a saved match: replays the moves and checks the hash, as the plan requires. */
    companion object {
        /** Restores a saved match: replays the moves and refuses anything illegal. */
        fun replay(start: GameState, moves: List<Move>): GameState? {
            var state = start
            for (move in moves) {
                if (move !in GameEngine.validMoves(state)) return null
                state = GameEngine.apply(state, move).state
            }
            return state
        }

        fun hashOf(state: GameState): String = StateHasher.hash(state)

        /** Builds a view model for one level: its state, objective, rival profile and hints. */
        fun factory(
            state: GameState,
            objective: LevelObjective,
            rival: AiProfile,
            difficulty: Difficulty,
            hints: Int,
            tutorial: TutorialDto? = null,
            animationEnabled: Boolean = true,
            confirmMove: Boolean = false,
            aiDelayMs: Long = if (animationEnabled) AnimationTimeline.AI_MIN_THINK_MS else 0L,
            savedMatch: SavedMatch? = null,
            bossRoundStates: List<GameState> = emptyList(),
            maxPlies: Int? = null,
            hintProfile: AiProfile = AiProfile.HARD,
            exactEndgameRival: Boolean = false,
            perfectRival: Boolean = false,
            openingBook: PerfectOpeningBook? = null,
            openingBookLoader: (() -> PerfectOpeningBook?)? = null,
            perfectSearchTimeoutMs: Long = 4_000L,
            perfectSearchLogger: suspend (String) -> Unit = { android.util.Log.i("NeonLine", it); Unit },
            onCheckpoint: (SavedMatch, LevelOutcome?, Int) -> Unit = { _, _, _ -> },
            undoAvailable: Int? = null,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    GameViewModel(
                        state = state,
                        objective = objective,
                        rival = rival,
                        difficulty = difficulty,
                        hintsAvailable = hints, tutorial = tutorial, animationEnabled = animationEnabled,
                        confirmMove = confirmMove, aiDelayMs = aiDelayMs,
                        undoAvailable = undoAvailable ?: if (difficulty == Difficulty.EASY) 3 else 0,
                        savedMatch = savedMatch, bossRoundStates = bossRoundStates, maxPlies = maxPlies, hintProfile = hintProfile,
                        exactEndgameRival = exactEndgameRival, perfectRival = perfectRival, openingBook = openingBook,
                        openingBookLoader = openingBookLoader,
                        perfectSearchTimeoutMs = perfectSearchTimeoutMs, perfectSearchLogger = perfectSearchLogger,
                        onCheckpoint = onCheckpoint,
                    ) as T
            }
    }
}

/** Exposed for tests: the hint column comes from the rival's suggestion. */
internal fun hintColumnFor(state: GameState, rival: AiProfile): Int? {
    if (state.status != Status.InProgress) return null
    val choice: AiChoice? = AiSearch(rival).chooseMove(state)
    return when (val move = choice?.move) {
        is Move.Drop -> move.col
        is Move.Pop -> move.col
        else -> null
    }
}
