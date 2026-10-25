package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.solver.SolverValue
import pt.tiago.neonline.data.*

class EndgameMistakeReviewTest {
    private fun start(): GameState {
        val pieces = listOf("...221.", "2.11122", "1.22211", "2221121", "2112121", "1211212").flatMap { row ->
            row.map { if (it == '.') null else Piece(if (it == '1') Player.P1 else Player.P2, PieceKind.NORMAL, 0) }
        }
        return GameState.initial("endgame-window", Difficulty.EASY, 2, "0123456789abcdef", 7, 6, 4, List(42) { true }, pieces)
    }
    private fun request(start: GameState = start(), columns: List<Int>): TacticalReviewInput {
        var state = start
        val steps = columns.map { col ->
            val before = state
            val move = Move.Drop(col)
            state = GameEngine.apply(state, move).state
            MatchReplay.step(before, move, state)
        }
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(state), steps = steps), 0, 0)
    }
    private fun oracle(state: GameState, subject: Player): Int = when (val s = state.status) {
        is Status.Won -> if (s.player == subject) 1 else -1
        is Status.Draw -> 0
        Status.InProgress -> GameEngine.validMoves(state).map { oracle(GameEngine.apply(state, it).state, subject) }
            .let { if (it.isEmpty()) 0 else if (state.toMove == subject) it.max() else it.min() }
    }
    private fun keepingPath(start: GameState): List<Int> {
        var state = start
        val path = mutableListOf<Int>()
        while (state.status == Status.InProgress) {
            val subject = state.toMove
            val v = oracle(state, subject)
            val action = GameEngine.validMoves(state).first { oracle(GameEngine.apply(state, it).state, subject) == v } as Move.Drop
            path += action.col
            state = GameEngine.apply(state, action).state
        }
        return path
    }
    @Test fun `first deterioration is found before a harmless last own move`() {
        val input = request(columns = listOf(0, 2, 1))
        val encoded = Json.encodeToString(SavedMatch.serializer(), input.journal)
        assertEquals(SolverValue.DRAW, EndgameReviewAnalyzer.analyze(input).comparison!!.afterValue)
        val result = EndgameReviewAnalyzer.analyzeMistake(input)
        assertEquals(EndgameReviewStatus.EXACT, result.status)
        val c = result.comparison!!
        assertEquals(1, c.number)
        assertEquals(SolverValue.DRAW, c.beforeValue)
        assertEquals(SolverValue.LOSS, c.afterValue)
        assertEquals(1, result.firstNumber)
        assertEquals(2, result.lastNumber)
        assertEquals(1, result.reviewedMoves)
        assertEquals(input.start, c.before)
        assertEquals(0, oracle(c.before, Player.P1))
        assertEquals(-1, oracle(c.playedAfter, Player.P1))
        assertEquals(0, oracle(c.alternativeAfter, Player.P1))
        assertEquals(encoded, Json.encodeToString(SavedMatch.serializer(), input.journal))
    }
    @Test fun `no worsening requires completing every eligible move`() {
        val path = keepingPath(start())
        val input = request(columns = path)
        val result = EndgameReviewAnalyzer.analyzeMistake(input)
        assertEquals(EndgameReviewStatus.NO_WORSENING, result.status)
        assertNull(result.comparison)
        assertEquals(3, result.reviewedMoves)
        assertEquals(1, result.firstNumber)
        assertEquals(3, result.lastNumber)
        assertTrue(result.nodes > 0)
    }
    @Test fun `budget is shared across turns and cannot skip an unproved earlier move`() {
        val path = keepingPath(start())
        val first = EndgameReviewAnalyzer.analyze(request(columns = path.take(1))).comparison!!
        val result = EndgameReviewAnalyzer.analyzeMistake(request(columns = path), maxNodes = first.nodes)
        assertEquals(EndgameReviewStatus.LIMIT_REACHED, result.status)
        assertNull(result.comparison)
        assertEquals(1, result.reviewedMoves)
        assertEquals(first.nodes, result.nodes)
        val laterMistake = EndgameReviewAnalyzer.analyzeMistake(request(columns = listOf(0,2,1)), maxNodes = 1)
        assertEquals(EndgameReviewStatus.LIMIT_REACHED, laterMistake.status)
        assertNull(laterMistake.comparison)
    }
    @Test fun `outcome changes match exhaustive oracle and distance alone is not a mistake`() {
        val cases = listOf(start(), start().copy(pieces = start().pieces.map { p -> p?.copy(owner = p.owner.opponent()) }))
        var sawStableWin = false
        var sawWorse = false
        for (before in cases) for (move in GameEngine.validMoves(before).filterIsInstance<Move.Drop>()) {
            val after = GameEngine.apply(before, move).state
            val a = oracle(before, Player.P1)
            val b = oracle(after, Player.P1)
            val result = EndgameReviewAnalyzer.analyzeMistake(request(before, listOf(move.col)))
            assertEquals(if (b < a) EndgameReviewStatus.EXACT else EndgameReviewStatus.NO_WORSENING, result.status)
            if (a == 1 && b == 1) sawStableWin = true
            if (b < a) sawWorse = true
        }
        assertTrue(sawStableWin)
        assertTrue(sawWorse)
    }
    @Test fun `full suffix must validate even if first turn already worsens`() {
        val input = request(columns = listOf(0,2,1))
        val result = EndgameReviewAnalyzer.analyzeMistake(input.copy(journal = input.journal.copy(stateHash = "bad")))
        assertEquals(EndgameReviewStatus.INVALID_JOURNAL, result.status)
        assertNull(result.comparison)
    }
    @Test fun `early unsupported and no-own inputs carry no conclusions`() {
        assertEquals(EndgameReviewStatus.TOO_EARLY, EndgameReviewAnalyzer.analyzeMistake(request(LocalConfig().initial(Player.P1), listOf(0))).status)
        assertEquals(EndgameReviewStatus.UNSUPPORTED, EndgameReviewAnalyzer.analyzeMistake(request(start().copy(popOut = true), listOf(0))).status)
        assertEquals(EndgameReviewStatus.UNSUPPORTED, EndgameReviewAnalyzer.analyzeMistake(request(columns = listOf(0)).copy(puzzleLimit = 2)).status)
        assertEquals(EndgameReviewStatus.NO_MOVES, EndgameReviewAnalyzer.analyzeMistake(request(start().copy(toMove = Player.P2), listOf(0))).status)
    }
    @Test fun `cancellation inside a multi-turn search escapes without partial result`() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EndgameReviewAnalyzer.analyzeMistake(request(columns = keepingPath(start()))) { if (++checks == 20) throw CancellationException() }
        }
        assertEquals(20, checks)
    }
    @Test fun `local P2 coverage and first loss agree with independent engine oracle`() {
        val config = LocalConfig(player1 = "Ana", player2 = "Bia")
        var state = config.initial(Player.P2)
        val random = java.util.Random(20261016L)
        val steps = mutableListOf<SavedPly>()
        repeat(39) {
            val moves = GameEngine.validMoves(state).filter { GameEngine.apply(state, it).state.status == Status.InProgress }
            val move = moves[if (it == 38) moves.lastIndex else random.nextInt(moves.size)]
            val next = GameEngine.apply(state, move).state
            steps += MatchReplay.step(state, move, next); state = next
        }
        val saved = SavedLocalSession(config, starter = Player.P2, steps = steps, stateHash = StateHasher.hash(state))
        val encoded = Json.encodeToString(SavedLocalSession.serializer(), saved)
        val own = LocalSessionReplay.trace(saved)!!.turns.filter { it.before.toMove == Player.P2 }
        val eligible = own.withIndex().filter { it.value.before.pieces.count { p -> p == null } <= 10 }
        val first = eligible.firstOrNull { oracle(it.value.before, Player.P2) > oracle(GameEngine.apply(it.value.before, it.value.move).state, Player.P2) }
        val result = EndgameReviewAnalyzer.analyzeMistake(LocalTacticalReviewInput(saved, Player.P2))
        assertEquals(17, result.firstNumber)
        assertEquals(20, result.lastNumber)
        if (first == null) {
            assertEquals(EndgameReviewStatus.NO_WORSENING, result.status)
            assertEquals(4, result.reviewedMoves)
        } else {
            assertEquals(EndgameReviewStatus.EXACT, result.status)
            assertEquals(first.index + 1, result.comparison!!.number)
            assertEquals(Player.P2, result.comparison!!.player)
            assertEquals(first.value.before, result.comparison!!.before)
        }
        assertEquals(encoded, Json.encodeToString(SavedLocalSession.serializer(), saved))
    }
}
