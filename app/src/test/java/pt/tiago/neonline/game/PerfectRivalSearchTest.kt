package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.core.solver.SolverValue

class PerfectRivalSearchTest {
    @Test
    fun `opening book exact hit bypasses solver budget and maps the winning move`() {
        val state = tacticalPosition()
        val book = PerfectOpeningBook.create(6, listOf(PerfectOpeningBook.Seed(state, 3, 18)))

        val result = PerfectRivalSearch.search(state, AiProfile.HARD, book, maxExactNodes = 1L)

        assertTrue(result.exact)
        assertTrue(result.usedBook)
        assertEquals(Move.Drop(3), result.choice?.move)
        assertEquals(0, result.nodesExplored)
    }

    @Test
    fun `solver returns exact tactical win`() {
        val state = tacticalPosition()

        val result = PerfectRivalSearch.search(state, AiProfile.HARD, maxExactNodes = Long.MAX_VALUE)

        assertTrue(result.exact)
        assertFalse(result.usedBook)
        assertEquals(Move.Drop(3), result.choice?.move)
    }

    @Test
    fun `budget failure returns a legal deterministic iterative fallback`() {
        val state = empty()

        val first = PerfectRivalSearch.search(state, AiProfile.HARD, maxExactNodes = 1L)
        val second = PerfectRivalSearch.search(state, AiProfile.HARD, maxExactNodes = 1L)
        val fallbackSearch = AiSearch(AiProfile.HARD)
        fallbackSearch.chooseMove(state)

        assertFalse(first.exact)
        assertNotNull(first.choice)
        assertTrue(first.choice!!.move in GameEngine.validMoves(state))
        assertEquals(first.choice, second.choice)
        assertEquals("includes two exact-search nodes and the fallback", fallbackSearch.nodesExplored + 2,
            first.nodesExplored)
    }

    @Test
    fun `cancellation is not converted into a fallback move`() {
        assertThrows(CancellationException::class.java) {
            PerfectRivalSearch.search(tacticalPosition(), AiProfile.HARD, checkCancelled = { throw CancellationException() })
        }
    }

    @Test
    fun `reports exact nodes visited before cancellation`() {
        var checks = 0
        var exactNodes = 0L
        val cancellation = CancellationException("stop after the first exact node")

        val thrown = assertThrows(CancellationException::class.java) {
            PerfectRivalSearch.search(
                empty(),
                AiProfile.HARD,
                onExactNodes = { exactNodes = it },
                checkCancelled = { if (++checks == 4) throw cancellation },
            )
        }

        assertEquals(cancellation, thrown)
        assertEquals(1L, exactNodes)
    }

    @Test
    fun `perfect rival preserves exact value in two hundred reachable late positions`() {
        val positions = reachableLatePositions(200)
        val oracle = PerfectSolver(nodeLimit = Long.MAX_VALUE)

        positions.forEachIndexed { index, state ->
            val before = requireNotNull(oracle.solve(state))
            val selected = requireNotNull(PerfectRivalSearch.search(state, AiProfile.HARD).choice).move
            val child = GameEngine.apply(state, selected).state
            val childValue = when (child.status) {
                pt.tiago.neonline.core.model.Status.InProgress -> requireNotNull(oracle.solve(child)).value
                is pt.tiago.neonline.core.model.Status.Won -> SolverValue.LOSS
                is pt.tiago.neonline.core.model.Status.Draw -> SolverValue.DRAW
            }
            val invertedAfter = when (childValue) {
                SolverValue.WIN -> SolverValue.LOSS
                SolverValue.DRAW -> SolverValue.DRAW
                SolverValue.LOSS -> SolverValue.WIN
            }
            assertEquals("position $index at ply ${state.ply}", before.value, invertedAfter)
        }
    }

    private fun tacticalPosition(): GameState = position(0, 4, 1, 5, 2, 6)

    private fun empty() = GameState.initial(
        levelId = "perfect-search-test", difficulty = Difficulty.HARD,
        engineVersion = GameEngine.ENGINE_VERSION, seedHex = "0000000000000001",
        width = 7, height = 6, connect = 4, playable = List(42) { true }, pieces = List(42) { null },
    )

    private fun position(vararg columns: Int): GameState {
        var state = empty()
        for (column in columns) state = GameEngine.apply(state, Move.Drop(column, PieceKind.NORMAL)).state
        return state.copy(toMove = Player.P1)
    }

    private fun reachableLatePositions(count: Int): List<GameState> {
        val positions = LinkedHashMap<String, GameState>()
        var seed = 0
        while (positions.size < count) {
            var state = empty()
            val random = Random(seed++)
            while (state.status == pt.tiago.neonline.core.model.Status.InProgress && state.ply < 36) {
                val moves = GameEngine.validMoves(state).filterIsInstance<Move.Drop>()
                if (moves.isEmpty()) break
                state = GameEngine.apply(state, moves[random.nextInt(moves.size)]).state
            }
            if (state.status == pt.tiago.neonline.core.model.Status.InProgress && state.ply == 36) {
                val key = state.toMove.name + state.pieces.joinToString("") { it?.owner?.name?.firstOrNull()?.toString() ?: "." }
                positions.putIfAbsent(key, state)
            }
        }
        return positions.values.toList()
    }
}
