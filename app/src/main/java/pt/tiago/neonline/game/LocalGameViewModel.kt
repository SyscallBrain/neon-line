package pt.tiago.neonline.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class LocalGameViewModel(
    val config: LocalConfig,
    saved: SavedLocalSession? = null,
    private val animationEnabled: Boolean = true,
    private val confirmMove: Boolean = false,
    private val onCheckpoint: (SavedLocalSession) -> Unit = {},
) : ViewModel() {
    private var starter = saved?.starter ?: Player.P1
    private val steps = saved?.steps?.toMutableList() ?: mutableListOf()
    private var wins1 = saved?.wins1 ?: 0
    private var wins2 = saved?.wins2 ?: 0
    private var draws = saved?.draws ?: 0
    private var animationId = 0L
    private var clockJob: Job? = null
    private val initial = saved?.let { requireNotNull(LocalSessionReplay.restore(it)) } ?: config.initial(starter)
    private val _ui = MutableStateFlow(GameUiState(initial, localPlayers = true, motionEnabled = animationEnabled,
        clockMs = if (initial.status == Status.InProgress) saved?.clockMs ?: fullClock() else null))
    val ui = _ui.asStateFlow()
    private val _score = MutableStateFlow(Triple(wins1, wins2, draws))
    val score = _score.asStateFlow()
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed = _saveFailed.asStateFlow()

    init { require(saved == null || saved.config == config); persist(); continueClock() }
    private fun fullClock(): Long? = config.clockSeconds.takeIf { it > 0 }?.times(1000L)
    fun initialState(): GameState = config.initial(starter)
    fun snapshot() = SavedLocalSession(config = config, starter = starter, wins1 = wins1, wins2 = wins2, draws = draws,
        steps = steps.toList(), stateHash = StateHasher.hash(_ui.value.state), clockMs = _ui.value.clockMs)
    /** Only finished rounds can be reviewed; reading a snapshot never persists or awards a score. */
    fun reviewInput(player: Player): LocalTacticalReviewInput? =
        if (_ui.value.finished && !_ui.value.animating) LocalTacticalReviewInput(snapshot(), player) else null

    private fun persist() { _saveFailed.value = runCatching { onCheckpoint(snapshot()) }.isFailure }

    fun selectPiece(kind: PieceKind) {
        val current = _ui.value
        if (!current.acceptsInput || (kind != PieceKind.NORMAL && (current.state.inventory[current.state.toMove]?.get(kind) ?: 0) <= 0)) return
        _ui.value = current.copy(selectedPiece = kind, pendingDrop = null, pendingPopColumn = null)
    }
    fun playColumn(column: Int) {
        val current = _ui.value
        if (!current.acceptsInput) return
        val move = Move.Drop(column, current.selectedPiece)
        if (BoardActions.landingRow(current.state, column, current.selectedPiece) == null) return
        if (confirmMove && current.pendingDrop != move) { _ui.value = current.copy(pendingDrop = move, pendingPopColumn = null); return }
        play(move)
    }
    fun requestPop(column: Int) {
        val current = _ui.value
        if (current.acceptsInput && column in BoardActions.popColumns(current.state))
            _ui.value = current.copy(pendingPopColumn = column, pendingDrop = null)
    }
    fun cancelPop() { _ui.value = _ui.value.copy(pendingPopColumn = null) }
    fun confirmPop() { _ui.value.pendingPopColumn?.let { play(Move.Pop(it)) } }

    fun play(move: Move) {
        val current = _ui.value
        if (!current.acceptsInput || (move == Move.TimeUp && config.clockSeconds == 0)) return
        val result = GameEngine.apply(current.state, move)
        if (result.events.any { it is GameEvent.MoveRejected }) return
        clockJob?.cancel()
        steps += MatchReplay.step(current.state, move, result.state)
        when (val status = result.state.status) {
            is Status.Won -> if (status.player == Player.P1) wins1++ else wins2++
            is Status.Draw -> draws++
            Status.InProgress -> Unit
        }
        _score.value = Triple(wins1, wins2, draws)
        val timeline = AnimationTimeline.build(current.state, result.state, result.events)
        val animation = if (animationEnabled && timeline.totalMs > 0) BoardAnimation(++animationId, timeline) else null
        _ui.value = GameUiState(result.state, localPlayers = true, events = result.events,
            motionEnabled = animationEnabled, animation = animation, animating = animation != null,
            clockMs = if (result.state.status == Status.InProgress) fullClock() else null)
        persist(); continueClock()
    }
    fun completeAnimation(id: Long) {
        val current = _ui.value
        if (!current.paused && current.animating && current.animation?.id == id) {
            _ui.value = current.copy(animating = false); continueClock()
        }
    }
    fun pause() {
        clockJob?.cancel()
        _ui.value = _ui.value.copy(paused = true, pendingDrop = null, pendingPopColumn = null)
        persist()
    }
    fun resume() { _ui.value = _ui.value.copy(paused = false); continueClock() }
    fun restart() { if (!_ui.value.finished) newRound(alternate = false) }
    fun nextRound() { if (_ui.value.finished && !_ui.value.animating) newRound(alternate = true) }
    private fun newRound(alternate: Boolean) {
        clockJob?.cancel()
        if (alternate) starter = if (starter == Player.P1) Player.P2 else Player.P1
        steps.clear()
        _ui.value = GameUiState(config.initial(starter), localPlayers = true, motionEnabled = animationEnabled, clockMs = fullClock())
        persist(); continueClock()
    }
    private fun continueClock() {
        clockJob?.cancel()
        val current = _ui.value
        if (!current.acceptsInput || current.clockMs == null) return
        if (current.clockMs == 0L) { play(Move.TimeUp); return }
        clockJob = viewModelScope.launch {
            while (_ui.value.acceptsInput && _ui.value.clockMs != null) {
                delay(100)
                val remaining = ((_ui.value.clockMs ?: break) - 100).coerceAtLeast(0)
                _ui.value = _ui.value.copy(clockMs = remaining)
                if (remaining > 0 && remaining % 1000 == 0L) persist()
                if (remaining == 0L) { play(Move.TimeUp); break }
            }
        }
    }
    override fun onCleared() { clockJob?.cancel(); super.onCleared() }
    companion object {
        fun factory(config: LocalConfig, saved: SavedLocalSession?, animationEnabled: Boolean, confirmMove: Boolean,
                    checkpoint: (SavedLocalSession) -> Unit) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LocalGameViewModel(config, saved, animationEnabled, confirmMove, checkpoint) as T
        }
    }
}
