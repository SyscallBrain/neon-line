package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

/** A legal rival action that wins in the current position; cell is where its board cue belongs. */
data class RivalThreatCue(val move: Move, val cell: Int)

/** Passive Easy assistance. No search, prediction of the human move, or changes to the match. */
object RivalThreatCues {
    @JvmOverloads
    fun snapshot(ui: GameUiState, enabled: Boolean = true): List<RivalThreatCue> {
        val state = ui.state
        if (!enabled || state.difficulty != Difficulty.EASY || ui.localPlayers || !ui.acceptsInput ||
            ui.tutorialStep != null || state.pieces.any { it != null && PeekVisibility.isHidden(state, it) }) return emptyList()
        state.rotateEvery?.let { every ->
            if (every <= 0 || state.ply % every >= every - 2) return emptyList()
        }
        val probe = state.copy(toMove = Player.P2)
        return GameEngine.validMoves(probe).mapNotNull { move ->
            if (move == Move.TimeUp) return@mapNotNull null
            val result = GameEngine.apply(probe, move)
            if (result.state.status != Status.Won(Player.P2) || result.events.any { it is GameEvent.MoveRejected }) return@mapNotNull null
            val cell = when (move) {
                is Move.Drop -> result.events.filterIsInstance<GameEvent.Dropped>().firstOrNull()?.let { state.index(it.row, it.col) }
                is Move.Pop -> state.pieces.indices.lastOrNull { it % state.width == move.col && state.pieces[it] != null }
                Move.TimeUp -> null
            } ?: return@mapNotNull null
            RivalThreatCue(move, cell)
        }
    }
}
