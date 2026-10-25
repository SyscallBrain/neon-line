package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class LocalTacticalReviewTest {
    private fun journal(columns: List<Int>, starter: Player = Player.P1, config: LocalConfig = LocalConfig(),
        last: Move? = null): SavedLocalSession {
        var state = config.initial(starter)
        val moves = columns.map { Move.Drop(it) } + listOfNotNull(last)
        val steps = moves.map { move ->
            val after = GameEngine.apply(state, move)
            assertTrue(after.events.none { it is GameEvent.MoveRejected })
            MatchReplay.step(state, move, after.state).also { state = after.state }
        }
        return SavedLocalSession(config, starter = starter, steps = steps, stateHash = StateHasher.hash(state),
            wins1 = if (state.status == Status.Won(Player.P1)) 1 else 0,
            wins2 = if (state.status == Status.Won(Player.P2)) 1 else 0,
            draws = if (state.status is Status.Draw) 1 else 0,
            clockMs = config.clockSeconds.takeIf { it > 0 && state.status == Status.InProgress }?.times(1000L))
    }

    @Test fun `P2 starter review preserves actual owners and numbers their own moves`() {
        val saved = journal(listOf(0, 6, 1, 6, 2, 5, 4), starter = Player.P2)
        val result = TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P2))
        val found = result.finding!!
        assertEquals(TacticalReason.MISSED_WIN, found.reason)
        assertEquals(4, found.humanMove)
        assertEquals(Player.P2, found.before.toMove)
        assertEquals(Player.P2, found.before.pieceAt(5, 0)!!.owner)
        assertEquals(Status.Won(Player.P2), found.alternativeAfter.status)
        assertEquals(Move.Drop(3), found.alternative)
        val opponentReview = TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P1)).finding!!
        assertEquals(TacticalReason.ALLOWED_WIN, opponentReview.reason)
        assertEquals(3, opponentReview.humanMove)
        assertEquals(Player.P1, opponentReview.before.toMove)
    }

    @Test fun `local P1 facts are identical to the existing campaign replay path`() {
        val saved = journal(listOf(0, 6, 1, 6, 2, 5, 4))
        val start = saved.config.initial(saved.starter)
        val match = SavedMatch(start.levelId, start.difficulty, initialHash = StateHasher.hash(start),
            stateHash = saved.stateHash, steps = saved.steps)
        assertEquals(TacticalReviewAnalyzer.analyze(TacticalReviewInput(start, match, 0, 0)),
            TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P1)))
    }

    @Test fun `P2 timeout replay and review use the actual auxiliary stream`() {
        val saved = journal(listOf(6, 0, 6, 0, 5, 0), starter = Player.P2,
            config = LocalConfig(clockSeconds = 10), last = Move.TimeUp)
        assertNotNull(LocalSessionReplay.restore(saved))
        val found = TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P2)).finding!!
        assertEquals(TacticalReason.ALLOWED_WIN, found.reason)
        assertEquals(Move.TimeUp, found.played)
        assertEquals(Move.Drop(0), found.alternative)
        assertEquals(Player.P2, found.before.toMove)
        assertEquals(saved.stateHash, StateHasher.hash(found.playedAfter))
    }

    @Test fun `P2 pop and bomb retain their action kinds`() {
        val pop = journal(listOf(0, 6, 1, 6, 2, 5), Player.P2, LocalConfig(popOut = true), Move.Pop(0))
        val bomb = journal(listOf(0, 6, 1, 6, 2, 5), Player.P2, LocalConfig(specials = true), Move.Drop(6, PieceKind.BOMB))
        for ((saved, move) in listOf(pop to Move.Pop(0), bomb to Move.Drop(6, PieceKind.BOMB))) {
            val found = TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P2)).finding!!
            assertEquals(move, found.played)
            assertEquals(Status.Won(Player.P2), found.alternativeAfter.status)
        }
    }

    @Test fun `trace preserves rotation and refuses invalid hashes or terminal score`() {
        val rotated = journal(listOf(0, 6, 1, 6, 2, 5, 4), config = LocalConfig(rotation = true))
        val trace = LocalSessionReplay.trace(rotated)!!
        assertEquals(LocalSessionReplay.restore(rotated), trace.state)
        assertEquals(7, trace.turns.size)
        assertEquals(6, trace.turns.last().before.width)
        assertNull(LocalSessionReplay.trace(rotated.copy(stateHash = "tampered")))
        val won = journal(listOf(0, 6, 1, 6, 2, 5, 3))
        assertNotNull(LocalSessionReplay.trace(won))
        val invalid = won.copy(wins1 = 0)
        assertNull(LocalSessionReplay.trace(invalid))
        assertEquals(TacticalReviewStatus.INVALID_JOURNAL,
            TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(invalid, Player.P1)).status)
    }

    @Test fun `local trace cancellation propagates and analysis budget remains explicit`() {
        val saved = journal(listOf(0, 6, 1, 6, 2, 5, 4))
        var calls = 0
        var cancelled = false
        try { LocalSessionReplay.trace(saved, checkCancelled = { if (++calls == 2) throw CancellationException() }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(2, calls)
        assertEquals(TacticalReviewStatus.LIMIT_REACHED,
            TacticalReviewAnalyzer.analyzeLocal(LocalTacticalReviewInput(saved, Player.P1), maxProbes = 1).status)
    }
}
