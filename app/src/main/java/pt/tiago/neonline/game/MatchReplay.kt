package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.SavedMatch
import pt.tiago.neonline.data.SavedPly

/** A human turn boundary, reconstructed from the same engine used during play. */
data class TurnCheckpoint(val state: GameState, val humanMoves: Int, val stepCount: Int)
data class RestoredMatch(val state: GameState, val humanMoves: Int, val undoPoints: List<TurnCheckpoint>)

data class ReplayTurn(val before: GameState, val move: Move, val after: GameState)
data class MatchTrace(val state: GameState, val turns: List<ReplayTurn>)

/** Versioned replay of drops, specials, pops and timeouts, including the rival's random stream. */
object MatchReplay {
    /** A live engine position may already have completed its defense objective. */
    fun restoreDefense(start: GameState, saved: SavedMatch, hints: Int, undos: Int, surviveAiMoves: Int): RestoredMatch? =
        restore(start, saved, hints, undos)?.takeIf {
            surviveAiMoves > 0 && saved.steps.size - it.humanMoves < surviveAiMoves
        }

    fun step(before: GameState, move: Move, after: GameState): SavedPly = SavedPly(
        action = when (move) { is Move.Drop -> "drop"; is Move.Pop -> "pop"; Move.TimeUp -> "timeout" },
        column = when (move) { is Move.Drop -> move.col; is Move.Pop -> move.col; Move.TimeUp -> null },
        kind = (move as? Move.Drop)?.kind ?: PieceKind.NORMAL,
        aiStateHex = before.aiStateHex,
        afterHash = StateHasher.hash(after),
    )

    @JvmOverloads
    fun restore(start: GameState, saved: SavedMatch, hints: Int, undos: Int, allowTerminal: Boolean = false,
        checkCancelled: () -> Unit = {}): RestoredMatch? =
        restoreInternal(start, saved, hints, undos, allowTerminal, checkCancelled) { _, _, _ -> }

    @JvmOverloads
    fun trace(start: GameState, saved: SavedMatch, hints: Int, undos: Int,
        checkCancelled: () -> Unit = {}): MatchTrace? {
        val turns = mutableListOf<ReplayTurn>()
        val restored = restoreInternal(start, saved, hints, undos, true, checkCancelled) { before, move, after ->
            turns += ReplayTurn(before, move, after)
        } ?: return null
        return MatchTrace(restored.state, turns.toList())
    }

    private fun restoreInternal(start: GameState, saved: SavedMatch, hints: Int, undos: Int,
        allowTerminal: Boolean, checkCancelled: () -> Unit,
        onTurn: (GameState, Move, GameState) -> Unit): RestoredMatch? = runCatching {
        checkCancelled()
        require(saved.elapsedMs >= 0L)
        require(saved.peekCount in 0..PeekVisibility.MAX_COUNT)
        require(saved.formatVersion == 1 && saved.moves.isEmpty() && saved.steps.size <= 4096)
        require(saved.levelId == start.levelId && saved.difficulty == start.difficulty)
        require(saved.initialHash == StateHasher.hash(start))
        require(saved.hintsLeft in 0..hints && saved.undosLeft in 0..undos)
        if (start.moveClockSec == null) require(saved.clockMs == null)
        else require((allowTerminal && saved.clockMs == null) || (saved.clockMs != null && saved.clockMs in 0L..start.moveClockSec!! * 1000L))
        var state = start
        var humanMoves = 0
        val undoPoints = mutableListOf<TurnCheckpoint>()
        saved.steps.forEachIndexed { index, step ->
            checkCancelled()
            require(state.status == Status.InProgress)
            require(step.aiStateHex.matches(Regex("[0-9a-fA-F]{16}")))
            val move = when (step.action) {
                "drop" -> Move.Drop(requireNotNull(step.column), step.kind)
                "pop" -> Move.Pop(requireNotNull(step.column))
                "timeout" -> { require(step.column == null && state.toMove == Player.P1); Move.TimeUp }
                else -> error("Unknown saved action")
            }
            if (state.toMove == Player.P1) {
                require(step.aiStateHex == state.aiStateHex)
                undoPoints += TurnCheckpoint(state, humanMoves, index)
                humanMoves++
            }
            val before = state.copy(aiStateHex = step.aiStateHex)
            if (move != Move.TimeUp) require(move in GameEngine.validMoves(before))
            val result = GameEngine.apply(before, move)
            require(result.events.none { it is GameEvent.MoveRejected })
            state = result.state
            require(StateHasher.hash(state) == step.afterHash)
            onTurn(before, move, state)
        }
        require(allowTerminal || state.status == Status.InProgress)
        if (start.moveClockSec != null && saved.clockMs == null) require(state.status != Status.InProgress)
        require(StateHasher.hash(state) == saved.stateHash)
        RestoredMatch(state, humanMoves, undoPoints)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }
}
