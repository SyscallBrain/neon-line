package pt.tiago.neonline.data

import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

@Serializable
data class LocalConfig(
    val player1: String = "", val player2: String = "",
    val width: Int = 7, val height: Int = 6, val connect: Int = 4,
    val popOut: Boolean = false, val rotation: Boolean = false, val specials: Boolean = false,
    val clockSeconds: Int = 0, val tableMode: Boolean = false,
    val version: Int = 1,
) {
    fun isValid(): Boolean = version in 1..2 && player1.length <= 32 && player2.length <= 32 &&
        (width to height) in setOf(7 to 6, 9 to 7) && connect in 4..5 && clockSeconds in setOf(0, 10, 20) && (!rotation || width == 7)

    fun initial(starter: Player): GameState {
        require(isValid())
        return GameState.initial("local-duel", Difficulty.EASY, engineVersion(), "0123456789abcdef",
            width, height, connect, List(width * height) { true }, List(width * height) { null },
            popOut = popOut, rotateEvery = if (rotation) 6 else null,
            moveClockSec = clockSeconds.takeIf { it > 0 }, firstToMove = starter,
            inventory = if (specials) Player.entries.associateWith { mapOf(PieceKind.WILD to 1, PieceKind.BOMB to 1, PieceKind.LOCK to 1) }
                else GameState.emptyInventory())
    }

    /** Omitted version fields decode to1, retaining the engine used by existing journals. */
    fun engineVersion(): Int = if (version >= 2) GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION else GameEngine.ENGINE_VERSION
}

@Serializable
data class SavedLocalSession(
    val config: LocalConfig = LocalConfig(),
    val formatVersion: Int = 1, val engineVersion: Int = config.engineVersion(),
    val starter: Player = Player.P1,
    val wins1: Int = 0, val wins2: Int = 0, val draws: Int = 0,
    val steps: List<SavedPly> = emptyList(), val stateHash: String = "",
    val clockMs: Long? = null,
) {
    fun isValid(): Boolean = config.isValid() && formatVersion == 1 && engineVersion == config.engineVersion() &&
        wins1 in 0..Int.MAX_VALUE - 1 && wins2 in 0..Int.MAX_VALUE - 1 && draws in 0..Int.MAX_VALUE - 1 && steps.size <= 4096
}

/** Validated local actions retain their actual owners, including P2 timeouts. */
data class LocalReplayTurn(val before: GameState, val move: Move)
data class LocalReplay(val state: GameState, val turns: List<LocalReplayTurn>)

/** Replays both players and timeouts through the same engine. No AI or campaign objective is applied. */
object LocalSessionReplay {
    fun restore(saved: SavedLocalSession): GameState? = restoreInternal(saved, {}, { _, _ -> })

    @JvmOverloads
    fun trace(saved: SavedLocalSession, checkCancelled: () -> Unit = {}): LocalReplay? {
        val turns = mutableListOf<LocalReplayTurn>()
        val state = restoreInternal(saved, checkCancelled) { before, move -> turns += LocalReplayTurn(before, move) }
            ?: return null
        return LocalReplay(state, turns.toList())
    }

    private fun restoreInternal(saved: SavedLocalSession, checkCancelled: () -> Unit,
        onTurn: (GameState, Move) -> Unit): GameState? = runCatching {
        checkCancelled()
        require(saved.isValid())
        var state = saved.config.initial(saved.starter)
        for (step in saved.steps) {
            checkCancelled()
            require(state.status == Status.InProgress && step.aiStateHex == state.aiStateHex)
            val move = when (step.action) {
                "drop" -> Move.Drop(requireNotNull(step.column), step.kind)
                "pop" -> { require(step.kind == PieceKind.NORMAL); Move.Pop(requireNotNull(step.column)) }
                "timeout" -> { require(step.column == null && step.kind == PieceKind.NORMAL && state.moveClockSec != null); Move.TimeUp }
                else -> error("Unknown local action")
            }
            val before = state
            val result = GameEngine.apply(state, move)
            require(result.events.none { it is GameEvent.MoveRejected })
            state = result.state
            require(StateHasher.hash(state) == step.afterHash)
            onTurn(before, move)
        }
        require(StateHasher.hash(state) == saved.stateHash)
        val clock = state.moveClockSec
        if (clock == null || state.status != Status.InProgress) require(saved.clockMs == null)
        else require(saved.clockMs != null && saved.clockMs in 0L..clock * 1000L)
        when (val status = state.status) {
            is Status.Won -> require(if (status.player == Player.P1) saved.wins1 > 0 else saved.wins2 > 0)
            is Status.Draw -> require(saved.draws > 0)
            Status.InProgress -> Unit
        }
        state
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }
}
