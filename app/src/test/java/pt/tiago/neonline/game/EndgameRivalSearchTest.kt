package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.ai.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.data.LocalConfig

class EndgameRivalSearchTest {
    private val profile = AiProfile.HARD
    private fun late(): GameState {
        val pieces = listOf("1.2221.", "2.11122", "1.22211", "2221121", "2112121", "1211212").flatMap { r ->
            r.map { if (it == '.') null else Piece(if (it == '1') Player.P1 else Player.P2, PieceKind.NORMAL, 0) }
        }
        return GameState.initial("free-play", Difficulty.HARD, 3, "0123456789abcdef", 7, 6, 4, List(42) { true }, pieces)
    }
    private fun oracle(state: GameState, subject: Player): Int = when (val s = state.status) {
        is Status.Won -> if (s.player == subject) 1 else -1
        is Status.Draw -> 0
        Status.InProgress -> GameEngine.validMoves(state).map { oracle(GameEngine.apply(state, it).state, subject) }
            .let { if (it.isEmpty()) 0 else if (state.toMove == subject) it.max() else it.min() }
    }
    @Test fun `exact choice preserves outcome for real P1 and P2 and advances RNG once`() {
        for (player in Player.entries) {
            val state = late().copy(toMove = player)
            val original = state.copy()
            val chosen = EndgameRivalSearch.choose(state, profile, true)!!
            assertTrue(chosen.move in PerfectSolver().solve(state)!!.bestMoves)
            assertEquals(oracle(state, player), oracle(GameEngine.apply(state, chosen.move).state, player))
            val rng = state.aiRng(); rng.nextInt(1000)
            assertEquals(rng.stateHex(), chosen.aiStateHex)
            assertEquals(original, state)
            val measured = EndgameRivalSearch.search(state, profile, true)
            assertEquals(chosen, measured.choice)
            assertEquals(PerfectSolver().solve(state)!!.nodesExplored.toInt(), measured.nodesExplored)
        }
    }
    @Test fun `forty reachable endgames preserve exhaustive engine value`() {
        val random = java.util.Random(20261016L)
        var found = 0
        for (index in 0 until 1000) {
            if (found == 40) break
            var state = LocalConfig().initial(if (index % 2 == 0) Player.P1 else Player.P2)
            repeat(38) {
                val next = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }.filter { it.status == Status.InProgress }
                if (next.isNotEmpty()) state = next[random.nextInt(next.size)]
            }
            if (state.ply != 38) continue
            found++
            val chosen = EndgameRivalSearch.choose(state, profile, true)!!
            assertEquals(oracle(state, state.toMove), oracle(GameEngine.apply(state, chosen.move).state, state.toMove))
        }
        assertEquals(40, found)
    }
    @Test fun `disabled early unsupported and exhausted use identical existing heuristic`() {
        val start = LocalConfig().initial(Player.P2)
        for ((state, enabled, budget) in listOf(Triple(late(), false, 200000L), Triple(start, true, 200000L),
            Triple(late().copy(popOut = true), true, 200000L), Triple(late().copy(fadeAfter = 3), true, 200000L),
            Triple(late().copy(moveClockSec = 10), true, 200000L), Triple(late(), true, 1L))) {
            val reference = AiSearch(profile)
            val expected = reference.chooseMove(state)
            val measured = EndgameRivalSearch.search(state, profile, enabled, budget)
            assertEquals(expected, measured.choice)
            val exactBudgetNodes = if (enabled && budget == 1L && state.width == 7 && state.height == 6 &&
                state.connect == 4 && state.pieces.count { it == null } <= EndgameReviewAnalyzer.MAX_EMPTY
            ) 2 else 0
            assertEquals(reference.nodesExplored + exactBudgetNodes, measured.nodesExplored)
        }
    }
    @Test fun `cancellation in exact search escapes and does not fall back`() {
        var calls = 0
        val error = CancellationException("cancel exact")
        assertSame(error, assertThrows(CancellationException::class.java) {
            EndgameRivalSearch.choose(late(), profile, true) { if (++calls == 4) throw error }
        })
        assertEquals(4, calls)
        assertNotNull(EndgameRivalSearch.choose(late(), profile, true))
    }
    @Test fun `fallback completion checks cancellation and terminal has no move`() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            EndgameRivalSearch.choose(late(), profile, false) { if (++calls == 3) throw CancellationException() }
        }
        assertNull(EndgameRivalSearch.choose(late().copy(status = Status.Draw("board_full")), profile, true))
    }
}
