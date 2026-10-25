package pt.tiago.neonline.core.solver

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.BitBoard
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine

class PerfectSolverEndgameTest {
    private fun value(state: GameState, player: Player): Int = when (val status = state.status) {
        is Status.Won -> if (status.player == player) 1 else -1
        is Status.Draw -> 0
        Status.InProgress -> {
            val children = GameEngine.validMoves(state).map { value(GameEngine.apply(state, it).state, player) }
            if (children.isEmpty()) 0 else if (state.toMove == player) children.max() else children.min()
        }
    }
    private fun distanceScore(state: GameState, player: Player): Int = when (val status = state.status) {
        is Status.Won -> (if (status.player == player) 1 else -1) * (state.pieces.count { it == null } + 2) / 2
        is Status.Draw -> 0
        Status.InProgress -> {
            val children = GameEngine.validMoves(state).map { distanceScore(GameEngine.apply(state, it).state, player) }
            if (children.isEmpty()) 0 else if (state.toMove == player) children.max() else children.min()
        }
    }
    private fun assertDistanceAndCompleteMoves(state: GameState, result: SolverResult) {
        val expected = distanceScore(state, state.toMove)
        assertEquals("distance score\n${board(state)}", expected, result.score)
        val optimal = GameEngine.validMoves(state).filter { distanceScore(GameEngine.apply(state, it).state, state.toMove) == expected }.toSet()
        assertEquals("complete distance-optimal moves\n${board(state)}", optimal, result.bestMoves.toSet())
    }
    private fun outcome(v: Int) = when { v > 0 -> SolverValue.WIN; v < 0 -> SolverValue.LOSS; else -> SolverValue.DRAW }
    private fun board(state: GameState) = (0 until state.height).joinToString("\n") { row ->
        (0 until state.width).joinToString("") { col -> state.pieceAt(row, col)?.owner?.let { if (it == Player.P1) "1" else "2" } ?: "." }
    }
    private fun positions(count: Int = 200, width: Int = 7, height: Int = 6, connect: Int = 4): List<GameState> {
        val random = java.util.Random(20261016L)
        val found = linkedMapOf<String, GameState>()
        for (attempt in 0 until 3000) {
            var state = Boards.blank(width, height, connect)
            val target = width * height - 4 + attempt % 3
            repeat(target) {
                val next = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }.filter { it.status == Status.InProgress }
                if (next.isNotEmpty()) state = next[random.nextInt(next.size)]
            }
            if (state.ply == target) found[board(state)] = state
            if (found.size == count) break
        }
        assertEquals("not enough reachable endgames", count, found.size)
        return found.values.toList()
    }

    @Test fun `opponent drop advances shared occupancy without adding an owned piece`() {
        val start = Boards.state(".......", ".......", ".......", "2......", "2......", "2......")
        val opponent = BitBoard.of(start, Player.P2)
        assertTrue(opponent.wouldWin(0))
        val blocked = opponent.afterOpponentDrop(0)
        val actual = GameEngine.apply(start, Move.Drop(0)).state
        assertFalse(blocked.wouldWin(0))
        assertEquals(BitBoard.of(actual, Player.P2).wouldWin(0), blocked.wouldWin(0))
        assertFalse(blocked.hasLine())
    }

    @Test fun `200 reachable endgames agree with exhaustive engine outcomes and every selected move`() {
        for (state in positions()) {
            val expected = value(state, state.toMove)
            val result = PerfectSolver().solve(state)!!
            assertEquals("ply=${state.ply}\n${board(state)}", outcome(expected), result.value)
            assertDistanceAndCompleteMoves(state, result)
            assertTrue("no best move\n${board(state)}", result.bestMoves.isNotEmpty())
            for (move in result.bestMoves) {
                assertTrue(move in GameEngine.validMoves(state))
                assertEquals("bad best move $move\n${board(state)}", expected, value(GameEngine.apply(state, move).state, state.toMove))
            }
        }
    }

    @Test fun `large boards and connect five preserve exact endgame values`() {
        for ((width, height, connect) in listOf(Triple(9, 7, 4), Triple(7, 6, 5), Triple(9, 7, 5))) {
            for (state in positions(20, width, height, connect)) {
                val expected = value(state, state.toMove)
                val result = PerfectSolver().solve(state)!!
                assertEquals("${width}x$height connect$connect\n${board(state)}", outcome(expected), result.value)
                assertDistanceAndCompleteMoves(state, result)
                assertTrue(result.bestMoves.isNotEmpty())
                result.bestMoves.forEach { move -> assertEquals(expected, value(GameEngine.apply(state, move).state, state.toMove)) }
            }
        }
    }

    @Test fun `loaded opening chronology does not change the endgame value`() {
        for (state in positions().take(20)) {
            val loaded = state.copy(ply = 0)
            assertEquals("loaded opening\n${board(state)}", outcome(value(state, state.toMove)), PerfectSolver().solve(loaded)!!.value)
        }
    }

    @Test fun `unsupported rules cannot be advertised as classic exact results`() {
        val normal = Boards.state(".......", ".......", ".......", ".......", ".......", "111....")
        val cases = listOf(
            normal.copy(shapeRule = ShapeRule.VERTICAL_ONLY),
            normal.copy(targets = setOf(normal.index(5, 3))),
            normal.copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1))),
            normal.copy(lockedCol = 3, lockedFor = Player.P1),
            normal.copy(fadeAfter = 3), normal.copy(moveClockSec = 10),
            Boards.state(".......", ".......", ".......", ".......", "1......", "......."),
        )
        cases.forEach { assertNull(PerfectSolver().solve(it)) }
    }

    @Test fun `budget exhaustion during best moves cannot return an empty partial answer`() {
        val state = Boards.state(".......", ".......", ".......", ".......", ".......", "22.22..")
        assertThrows(SolverBudgetExceeded::class.java) { PerfectSolver(1).solve(state) }
        val endgame = Boards.state("1.2221.", "2.11122", "1.22211", "2221121", "2112121", "1211212")
        val complete = PerfectSolver().solve(endgame)!!
        assertTrue(complete.nodesExplored > 5)
        // Every smaller budget covers all incomplete stages, including enumeration after the root value.
        for (budget in 1L until complete.nodesExplored) {
            assertThrows("partial result at budget $budget", SolverBudgetExceeded::class.java) { PerfectSolver(budget).solve(endgame) }
        }
    }
}
