package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class EndgameReviewReplayTest {
    private fun regular(injectRng: Boolean = false): TacticalReviewInput {
        val pieces = listOf("...221.", "2.11122", "1.22211", "2221121", "2112121", "1211212").flatMap { row ->
            row.map { if (it == '.') null else Piece(if (it == '1') Player.P1 else Player.P2, PieceKind.NORMAL, 0) }
        }
        val start = GameState.initial("endgame-replay", Difficulty.EASY, 2, "0123456789abcdef", 7, 6, 4, List(42) { true }, pieces)
        var state = start
        val steps = listOf(0,2,1).map { col ->
            val before = if (injectRng && state.toMove == Player.P2) state.copy(aiStateHex = "0000000000000099") else state
            val move = Move.Drop(col)
            state = GameEngine.apply(before, move).state
            MatchReplay.step(before, move, state)
        }
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(state), steps = steps), 0, 0)
    }
    private fun local(): SavedLocalSession {
        val config = LocalConfig(player1 = "Ana", player2 = "Bia")
        val random = java.util.Random(20261016L)
        var state = config.initial(Player.P2)
        val steps = mutableListOf<SavedPly>()
        repeat(39) { index ->
            val moves = GameEngine.validMoves(state).filter { GameEngine.apply(state, it).state.status == Status.InProgress }
            val move = moves[if (index == 38) moves.lastIndex else random.nextInt(moves.size)]
            val next = GameEngine.apply(state, move).state
            steps += MatchReplay.step(state, move, next); state = next
        }
        return SavedLocalSession(config, starter = Player.P2, steps = steps, stateHash = StateHasher.hash(state))
    }
    @Test fun `first endgame mistake replays only actual recorded continuation`() {
        val input = regular()
        val c = EndgameReviewAnalyzer.analyzeMistake(input).comparison!!
        val encoded = Json.encodeToString(SavedMatch.serializer(), input.journal)
        val replay = TacticalReviewReplay.buildEndgame(input, c)!!
        assertEquals(listOf(0,1,2,3), replay.frames.map { it.moveNumber })
        assertEquals(1, replay.decisiveIndex)
        assertEquals(c.before, replay.frames.first().state)
        assertEquals(c.playedAfter, replay.frames[1].state)
        assertEquals(listOf(null, Move.Drop(0), Move.Drop(2), Move.Drop(1)), replay.frames.map { it.move })
        assertEquals(listOf(null, Player.P1, Player.P2, Player.P1), replay.frames.map { it.player })
        assertEquals(input.journal.stateHash, StateHasher.hash(replay.frames.last().state))
        assertEquals(encoded, Json.encodeToString(SavedMatch.serializer(), input.journal))
    }
    @Test fun `last own comparison clips at final recorded action`() {
        val input = regular()
        val c = EndgameReviewAnalyzer.analyze(input).comparison!!
        val replay = TacticalReviewReplay.buildEndgame(input, c)!!
        assertEquals(2, c.number)
        assertEquals(3, replay.decisiveIndex)
        assertEquals(c.playedAfter, replay.frames.last().state)
        assertEquals(4, replay.frames.size)
    }
    @Test fun `P2 own number18 identifies global action35 with eight real frames`() {
        val saved = local()
        val trace = LocalSessionReplay.trace(saved)!!
        val prefix = saved.copy(steps = saved.steps.take(35), stateHash = saved.steps[34].afterHash)
        val c = EndgameReviewAnalyzer.analyze(LocalTacticalReviewInput(prefix, Player.P2)).comparison!!
        assertEquals(18, c.number)
        val encoded = Json.encodeToString(SavedLocalSession.serializer(), saved)
        val replay = TacticalReviewReplay.buildEndgame(LocalTacticalReviewInput(saved, Player.P2), c)!!
        assertEquals((31..38).toList(), replay.frames.map { it.moveNumber })
        assertEquals(4, replay.decisiveIndex)
        val focus = replay.frames[replay.decisiveIndex]
        assertEquals(35, focus.moveNumber)
        assertEquals(Player.P2, focus.player)
        assertEquals(c.playedAfter, focus.state)
        assertEquals(trace.turns[31].before, replay.frames.first().state)
        replay.frames.drop(1).forEach { frame ->
            val turn = trace.turns[frame.moveNumber - 1]
            assertEquals(turn.move, frame.move)
            assertEquals(turn.before.toMove, frame.player)
            assertEquals(GameEngine.apply(turn.before, turn.move).state, frame.state)
        }
        assertEquals(encoded, Json.encodeToString(SavedLocalSession.serializer(), saved))
    }
    @Test fun `real rival injected random stream is retained in later frames`() {
        val input = regular(injectRng = true)
        val c = EndgameReviewAnalyzer.analyzeMistake(input).comparison!!
        val replay = TacticalReviewReplay.buildEndgame(input, c)!!
        assertEquals("0000000000000099", replay.frames[2].state.aiStateHex)
        assertEquals(input.journal.steps[1].afterHash, StateHasher.hash(replay.frames[2].state))
        assertEquals(input.journal.stateHash, StateHasher.hash(replay.frames.last().state))
    }
    @Test fun `wrong subject number action boards and invalid suffix are refused`() {
        val input = regular()
        val c = EndgameReviewAnalyzer.analyzeMistake(input).comparison!!
        for (bad in listOf(c.copy(player = Player.P2), c.copy(number = 0), c.copy(number = 9),
            c.copy(played = Move.Drop(6)), c.copy(before = c.before.copy(aiStateHex = "0000000000000099")),
            c.copy(playedAfter = c.alternativeAfter))) {
            assertNull(TacticalReviewReplay.buildEndgame(input, bad))
        }
        val bad = input.copy(journal = input.journal.copy(steps = input.journal.steps.dropLast(1) + input.journal.steps.last().copy(afterHash = "bad")))
        assertNull(TacticalReviewReplay.buildEndgame(bad, c))
        val saved = local()
        val c2 = EndgameReviewAnalyzer.analyze(LocalTacticalReviewInput(saved, Player.P2)).comparison!!
        assertNull(TacticalReviewReplay.buildEndgame(LocalTacticalReviewInput(saved, Player.P1), c2))
    }
    @Test fun `cancellation while preparing recorded endgame frames escapes`() {
        val input = regular()
        val c = EndgameReviewAnalyzer.analyzeMistake(input).comparison!!
        var checks = 0
        assertThrows(CancellationException::class.java) {
            TacticalReviewReplay.buildEndgame(input, c) { if (++checks == 8) throw CancellationException() }
        }
        assertEquals(8, checks)
    }
}
