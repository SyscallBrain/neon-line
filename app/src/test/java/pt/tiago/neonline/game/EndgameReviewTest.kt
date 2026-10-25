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

class EndgameReviewTest {
    private fun late(swapped: Boolean = false): GameState {
        val rows = listOf("1.2221.", "2.11122", "1.22211", "2221121", "2112121", "1211212")
        val pieces = rows.flatMap { row -> row.map { char ->
            if (char == '.') null else Piece(if ((char == '1') != swapped) Player.P1 else Player.P2, PieceKind.NORMAL, 0)
        } }
        return GameState.initial("endgame", Difficulty.EASY, 2, "0123456789abcdef", 7, 6, 4,
            List(42) { true }, pieces)
    }
    private fun input(start: GameState = late(), column: Int = 6): TacticalReviewInput {
        val move = Move.Drop(column)
        val after = GameEngine.apply(start, move).state
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(after),
            steps = listOf(MatchReplay.step(start, move, after)),
            clockMs = start.moveClockSec?.takeIf { after.status == Status.InProgress }?.times(1000L)), 0, 0)
    }

    @Test fun `late draw becomes loss from subjects perspective and optimal move preserves draw`() {
        val request = input()
        val encoded = Json.encodeToString(SavedMatch.serializer(), request.journal)
        val result = EndgameReviewAnalyzer.analyze(request)
        assertEquals(EndgameReviewStatus.EXACT, result.status)
        val c = result.comparison!!
        assertEquals(Player.P1, c.player)
        assertEquals(1, c.number)
        assertEquals(SolverValue.DRAW, c.beforeValue)
        assertEquals(SolverValue.LOSS, c.afterValue)
        assertEquals(Move.Drop(6), c.played)
        assertEquals(Move.Drop(1), c.alternative)
        assertEquals(request.start, c.before)
        assertEquals(request.journal.stateHash, StateHasher.hash(c.playedAfter))
        assertEquals(GameEngine.apply(c.before, c.alternative).state, c.alternativeAfter)
        assertEquals(Player.P2, c.playedAfter.toMove)
        assertEquals(encoded, Json.encodeToString(SavedMatch.serializer(), request.journal))
    }

    @Test fun `optimal actual move is retained without invented different recommendation`() {
        val c = EndgameReviewAnalyzer.analyze(input(column = 1)).comparison!!
        assertEquals(SolverValue.DRAW, c.beforeValue)
        assertEquals(SolverValue.DRAW, c.afterValue)
        assertEquals(c.played, c.alternative)
        assertEquals(c.playedAfter, c.alternativeAfter)
    }

    @Test fun `terminal win needs no extra budget and remains from selected players perspective`() {
        val result = EndgameReviewAnalyzer.analyze(input(late(swapped = true), 1), maxNodes = 1)
        assertEquals(EndgameReviewStatus.EXACT, result.status)
        val c = result.comparison!!
        assertEquals(SolverValue.WIN, c.beforeValue)
        assertEquals(SolverValue.WIN, c.afterValue)
        assertEquals(Status.Won(Player.P1), c.playedAfter.status)
        assertEquals(1L, c.nodes)
    }

    @Test fun `two searches share node budget and never expose a partial comparison`() {
        val result = EndgameReviewAnalyzer.analyze(input(late(swapped = true), 6), maxNodes = 1)
        assertEquals(EndgameReviewStatus.LIMIT_REACHED, result.status)
        assertNull(result.comparison)
        assertEquals(EndgameReviewStatus.LIMIT_REACHED, EndgameReviewAnalyzer.analyze(input(), maxNodes = 1).status)
    }

    @Test fun `validation precedes early cutoff and unsupported rules never leak a board`() {
        val early = input(LocalConfig().initial(Player.P1), 0)
        assertEquals(EndgameReviewStatus.TOO_EARLY, EndgameReviewAnalyzer.analyze(early).status)
        assertEquals(EndgameReviewStatus.INVALID_JOURNAL,
            EndgameReviewAnalyzer.analyze(early.copy(journal = early.journal.copy(stateHash = "bad"))).status)
        for (start in listOf(late().copy(fadeAfter = 3), late().copy(moveClockSec = 10), late().copy(popOut = true),
            late().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1))))) {
            val result = EndgameReviewAnalyzer.analyze(input(start))
            assertEquals(EndgameReviewStatus.UNSUPPORTED, result.status)
            assertNull(result.comparison)
        }
        assertEquals(EndgameReviewStatus.UNSUPPORTED, EndgameReviewAnalyzer.analyze(input().copy(puzzleLimit = 3)).status)
    }

    @Test fun `ten empty cells are eligible and eleven are not searched`() {
        fun removeTop(count: Int): GameState {
            val base = late()
            val pieces = base.pieces.toMutableList()
            repeat(count) { col ->
                val index = (0 until base.height).map { base.index(it, col % base.width) }.first { pieces[it] != null }
                pieces[index] = null
            }
            return base.copy(pieces = pieces)
        }
        assertEquals(10, removeTop(6).pieces.count { it == null })
        assertEquals(EndgameReviewStatus.LIMIT_REACHED, EndgameReviewAnalyzer.analyze(input(removeTop(6)), maxNodes = 1).status)
        assertEquals(EndgameReviewStatus.TOO_EARLY, EndgameReviewAnalyzer.analyze(input(removeTop(7)), maxNodes = 1).status)
    }

    @Test fun `no subject moves and cancellation return no misleading value`() {
        assertEquals(EndgameReviewStatus.NO_MOVES,
            EndgameReviewAnalyzer.analyze(input(late().copy(toMove = Player.P2), 1)).status)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EndgameReviewAnalyzer.analyze(input()) { if (++checks == 8) throw CancellationException() }
        }
        assertEquals(8, checks)
    }

    private fun oracle(state: GameState, player: Player): Int = when (val status = state.status) {
        is Status.Won -> if (status.player == player) 1 else -1
        is Status.Draw -> 0
        Status.InProgress -> {
            val values = GameEngine.validMoves(state).map { oracle(GameEngine.apply(state, it).state, player) }
            if (values.isEmpty()) 0 else if (state.toMove == player) values.max() else values.min()
        }
    }
    private fun value(n: Int) = if (n > 0) SolverValue.WIN else if (n < 0) SolverValue.LOSS else SolverValue.DRAW

    @Test fun `P2 local last move keeps real owners own number and exact inverted value`() {
        val config = LocalConfig(player1 = "Ana", player2 = "Bia")
        val random = java.util.Random(20261016L)
        var state = config.initial(Player.P2)
        val steps = mutableListOf<SavedPly>()
        repeat(38) {
            val moves = GameEngine.validMoves(state).filter { GameEngine.apply(state, it).state.status == Status.InProgress }
            assertTrue(moves.isNotEmpty())
            val move = moves[random.nextInt(moves.size)]
            val next = GameEngine.apply(state, move).state
            steps += MatchReplay.step(state, move, next); state = next
        }
        val before = state
        val move = GameEngine.validMoves(state).last { GameEngine.apply(state, it).state.status == Status.InProgress }
        state = GameEngine.apply(state, move).state
        steps += MatchReplay.step(before, move, state)
        val saved = SavedLocalSession(config, starter = Player.P2, steps = steps, stateHash = StateHasher.hash(state))
        val encoded = Json.encodeToString(SavedLocalSession.serializer(), saved)
        val c = EndgameReviewAnalyzer.analyze(LocalTacticalReviewInput(saved, Player.P2)).comparison!!
        assertEquals(Player.P2, c.player)
        assertEquals(20, c.number)
        assertEquals(before, c.before)
        assertEquals(state, c.playedAfter)
        assertEquals(Player.P1, c.playedAfter.toMove)
        assertEquals(value(oracle(before, Player.P2)), c.beforeValue)
        assertEquals(value(oracle(state, Player.P2)), c.afterValue)
        assertEquals(SolverValue.WIN, c.afterValue)
        assertEquals(encoded, Json.encodeToString(SavedLocalSession.serializer(), saved))
    }
}
