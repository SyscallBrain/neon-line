package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*

/** Replay each finished duel and recompute the score before accepting an active boss match. */
object BossReplay {
    data class Restored(val start: GameState, val active: RestoredMatch, val series: BossSeries, val roundIndex: Int, val roundOutcome: MatchOutcome?)

    fun outcome(state: GameState): MatchOutcome? = when(val status=state.status) {
        is Status.Won -> MatchOutcome.Win(status.player,emptyList())
        is Status.Draw -> MatchOutcome.Draw(status.reason)
        Status.InProgress -> null
    }

    fun restore(starts: List<GameState>, saved: SavedMatch, hints: Int, undos: Int): Restored? = runCatching {
        require(starts.size==3 && saved.bossHistory.size<=5)
        var series=BossSeries()
        var hintsLeft=hints
        for (round in saved.bossHistory) {
            require(!series.decided)
            val start=starts[series.roundIndex]
            val journal=SavedMatch(saved.levelId,saved.difficulty,initialHash=round.initialHash,stateHash=round.stateHash,
                steps=round.steps,hintsLeft=round.hintsLeft,undosLeft=round.undosLeft)
            val replay=requireNotNull(MatchReplay.restore(start,journal,hintsLeft,undos,allowTerminal=true))
            series=series.record(requireNotNull(outcome(replay.state)),replay.humanMoves,hintsLeft-round.hintsLeft)
            hintsLeft=round.hintsLeft
        }
        require(!series.decided)
        val slot=series.roundIndex
        val start=starts[slot]
        val active=requireNotNull(MatchReplay.restore(start,saved,hintsLeft,undos,allowTerminal=true))
        val roundOutcome=outcome(active.state)
        if(roundOutcome!=null) series=series.record(roundOutcome,active.humanMoves,hintsLeft-saved.hintsLeft)
        // A decided series is committed and cleared atomically, never resumed for another award.
        require(!series.decided)
        Restored(start,active,series,slot,roundOutcome)
    }.getOrNull()
}
