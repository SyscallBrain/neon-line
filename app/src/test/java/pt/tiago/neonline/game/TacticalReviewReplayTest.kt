package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class TacticalReviewReplayTest {
    private val columns = listOf(0, 6, 1, 6, 2, 5, 4, 6, 4, 6)
    private fun local(config: LocalConfig = LocalConfig(), starter: Player = Player.P1,
        moves: List<Move> = columns.map { Move.Drop(it) }): SavedLocalSession {
        var state = config.initial(starter)
        val steps = moves.map { move ->
            val after = GameEngine.apply(state, move)
            assertTrue(after.events.none { it is GameEvent.MoveRejected })
            MatchReplay.step(state, move, after.state).also { state = after.state }
        }
        return SavedLocalSession(config, starter = starter, steps = steps, stateHash = StateHasher.hash(state),
            wins1 = if (state.status == Status.Won(Player.P1)) 1 else 0,
            wins2 = if (state.status == Status.Won(Player.P2)) 1 else 0,
            clockMs = config.clockSeconds.takeIf { it > 0 && state.status == Status.InProgress }?.times(1000L))
    }
    private fun regular(saved: SavedLocalSession): TacticalReviewInput {
        val start = saved.config.initial(saved.starter)
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), steps = saved.steps, stateHash = saved.stateHash,
            clockMs = saved.clockMs), 0, 0)
    }

    @Test fun `replay shows three real moves either side without hypothetical alternative`() {
        val input = regular(local())
        val finding = TacticalReviewAnalyzer.analyze(input).finding!!
        val window = TacticalReviewReplay.build(input, finding)!!
        assertEquals(8, window.frames.size)
        assertEquals((3..10).toList(), window.frames.map { it.moveNumber })
        assertEquals(4, window.decisiveIndex)
        assertEquals(finding.before, window.frames[3].state)
        assertEquals(finding.playedAfter, window.frames[4].state)
        assertEquals(Move.Drop(4), window.frames[4].move)
        assertEquals(4, window.frames[4].column)
        assertEquals(Player.P1, window.frames[4].player)
        assertEquals(Status.Won(Player.P2), window.frames.last().state.status)
        assertEquals(input.journal.stateHash, StateHasher.hash(window.frames.last().state))
        assertNull(window.frames.first().move)
        assertNull(window.frames.first().player)
    }

    @Test fun `early and last mistake clips window to actual journal boundaries`() {
        val saved = local(moves = columns.take(7).map { Move.Drop(it) })
        val input = regular(saved)
        val finding = TacticalReviewAnalyzer.analyze(input).finding!!
        val last = TacticalReviewReplay.build(input, finding)!!
        assertEquals((3..7).toList(), last.frames.map { it.moveNumber })
        assertEquals(4, last.decisiveIndex)
        val start = finding.before
        val one = TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(finding.playedAfter),
            steps = listOf(MatchReplay.step(start, finding.played, finding.playedAfter))), 0, 0)
        val first = TacticalReviewReplay.build(one, TacticalReviewAnalyzer.analyze(one).finding!!)!!
        assertEquals(listOf(0, 1), first.frames.map { it.moveNumber })
        assertEquals(1, first.decisiveIndex)
        assertEquals(start, first.frames.first().state)
    }

    @Test fun `P2 starter uses their own decision number and actual colours`() {
        val saved = local(starter = Player.P2)
        val input = LocalTacticalReviewInput(saved, Player.P2)
        val finding = TacticalReviewAnalyzer.analyzeLocal(input).finding!!
        assertEquals(4, finding.humanMove)
        val window = TacticalReviewReplay.build(input, finding)!!
        assertEquals(7, window.frames[window.decisiveIndex].moveNumber)
        assertEquals(Player.P2, window.frames[window.decisiveIndex].player)
        assertEquals(Player.P2, window.frames[window.decisiveIndex].state.pieceAt(5, 4)!!.owner)
        assertEquals(Status.Won(Player.P1), window.frames.last().state.status)
    }

    @Test fun `timeout column is resolved by engine and auxiliary stream unchanged`() {
        val saved = local(LocalConfig(clockSeconds = 10), Player.P2,
            listOf(6, 0, 6, 0, 5, 0).map { Move.Drop(it) } + Move.TimeUp)
        val input = LocalTacticalReviewInput(saved, Player.P2)
        val finding = TacticalReviewAnalyzer.analyzeLocal(input).finding!!
        val encoded = Json.encodeToString(SavedLocalSession.serializer(), saved)
        val window = TacticalReviewReplay.build(input, finding)!!
        val frame = window.frames[window.decisiveIndex]
        assertEquals(Move.TimeUp, frame.move)
        assertEquals(finding.playedColumn, frame.column)
        assertEquals(finding.playedAfter, frame.state)
        assertEquals(encoded, Json.encodeToString(SavedLocalSession.serializer(), saved))
    }

    @Test fun `trace preserves rival injected RNG and rejects invalid journal suffix`() {
        val original = regular(local())
        var state = original.start
        val steps = original.journal.steps.mapIndexed { index, step ->
            val before = if (state.toMove == Player.P2) state.copy(aiStateHex = "%016x".format(index + 99)) else state
            val move = Move.Drop(step.column!!)
            val after = GameEngine.apply(before, move).state
            MatchReplay.step(before, move, after).also { state = after }
        }
        val input = original.copy(journal = original.journal.copy(steps = steps, stateHash = StateHasher.hash(state)))
        val trace = MatchReplay.trace(input.start, input.journal, 0, 0)!!
        assertEquals(MatchReplay.restore(input.start, input.journal, 0, 0, true)!!.state, trace.state)
        assertEquals("0000000000000066", trace.turns[3].before.aiStateHex)
        assertEquals(steps[3].afterHash, StateHasher.hash(trace.turns[3].after))
        val finding = TacticalReviewAnalyzer.analyze(input).finding!!
        assertNotNull(TacticalReviewReplay.build(input, finding))
        val corrupt = input.copy(journal = input.journal.copy(steps = steps.dropLast(1) + steps.last().copy(afterHash = "bad")))
        assertNull(TacticalReviewReplay.build(corrupt, finding))
    }

    @Test fun `replay keeps pop bomb and rotation exact`() {
        for ((config, final) in listOf(LocalConfig(popOut = true) to Move.Pop(0),
            LocalConfig(specials = true) to Move.Drop(6, PieceKind.BOMB))) {
            val saved = local(config, Player.P2, columns.take(6).map { Move.Drop(it) } + final)
            val input = LocalTacticalReviewInput(saved, Player.P2)
            val finding = TacticalReviewAnalyzer.analyzeLocal(input).finding!!
            val frame = TacticalReviewReplay.build(input, finding)!!.frames.last()
            assertEquals(final, frame.move)
            assertEquals(finding.playedAfter, frame.state)
        }
        val input = regular(local(LocalConfig(rotation = true), moves = columns.take(7).map { Move.Drop(it) }))
        val trace = MatchReplay.trace(input.start, input.journal, 0, 0)!!
        assertEquals(7, trace.turns[5].before.width)
        assertEquals(6, trace.turns[5].after.width)
        assertEquals(input.journal.stateHash, StateHasher.hash(trace.state))
    }

    @Test fun `wrong finding and fading boards never expose playback`() {
        val input = regular(local())
        val finding = TacticalReviewAnalyzer.analyze(input).finding!!
        assertNull(TacticalReviewReplay.build(input, finding.copy(humanMove = 100)))
        assertNull(TacticalReviewReplay.build(input, finding.copy(played = Move.Drop(3))))
        assertNull(TacticalReviewReplay.build(input, finding.copy(before = finding.before.copy(aiStateHex = "ffffffffffffffff"))))
        val fadingStart = finding.before.copy(fadeAfter = 3)
        val fadingAfter = GameEngine.apply(fadingStart, finding.played).state
        val fading = TacticalReviewInput(fadingStart, SavedMatch(fadingStart.levelId, fadingStart.difficulty,
            initialHash = StateHasher.hash(fadingStart), stateHash = StateHasher.hash(fadingAfter),
            steps = listOf(MatchReplay.step(fadingStart, finding.played, fadingAfter))), 0, 0)
        assertNotNull(MatchReplay.trace(fading.start, fading.journal, 0, 0))
        assertNull(TacticalReviewReplay.build(fading, finding.copy(humanMove = 1, before = fadingStart, playedAfter = fadingAfter)))
    }

    @Test fun `cancellation propagates from recorded replay`() {
        val input = regular(local())
        val finding = TacticalReviewAnalyzer.analyze(input).finding!!
        var calls = 0
        assertThrows(CancellationException::class.java) {
            TacticalReviewReplay.build(input, finding) { if (++calls == 3) throw CancellationException() }
        }
        assertEquals(3, calls)
    }
}
