package pt.tiago.neonline.core.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.BitBoard
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine

class PerfectSolverTest {

    private fun safeMidgame() = Boards.blank().let { initial ->
        val rng = SplitMix64(5L)
        var state = initial
        repeat(26) {
            if (state.status == Status.InProgress) {
                val safe = GameEngine.validMoves(state).filter { move ->
                    !pt.tiago.neonline.core.ai.Evaluator.hasThreat(
                        GameEngine.apply(state, move).state,
                        state.toMove.opponent(),
                    )
                }
                val choices = safe.ifEmpty { GameEngine.validMoves(state) }
                state = GameEngine.apply(state, choices[rng.nextInt(choices.size)]).state
            }
        }
        state
    }

    @Test
    fun `finds an immediate win`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val result = PerfectSolver().solve(s)!!
        assertEquals(SolverValue.WIN, result.value)
        assertTrue(result.bestMoves.contains(Move.Drop(3)))
        assertTrue(result.score > 0)
    }

    @Test
    fun `rejects an in-progress state when the previous mover already has a line`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            "2222...",
        ).copy(ply = 7, toMove = Player.P1)

        assertNull(PerfectSolver().solve(s))
    }

    @Test
    fun `rejects an in-progress state with no legal moves`() {
        val full = Boards.state(
            "1212",
            "2121",
            "1212",
            "2121",
            connect = 5,
        )

        assertNull(PerfectSolver().solve(full))
    }

    @Test
    fun `reports an unavoidable loss`() {
        // P2 owns cols 1,2,3: distinct immediate wins in columns 0 and 4.
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".222...",
            firstToMove = Player.P1,
        )
        val result = PerfectSolver().solve(s)!!
        assertEquals(SolverValue.LOSS, result.value)
        assertTrue(result.score < 0)
    }

    @Test
    fun `forced safe moves skip threat scoring`() {
        val state = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "......1",
            "1222..1",
        )
        val mine = BitBoard.of(state, Player.P1)
        val theirs = BitBoard.of(state, Player.P2)
        assertEquals(1L shl 4, mine.nonLosingColumnsAgainst(theirs))

        val solver = PerfectSolver(nodeLimit = 1_000, nodeProgressInterval = 1)
        val failure = runCatching { solver.solveWithProgress(state, onProgress = {}) }.exceptionOrNull()

        assertTrue(failure is SolverBudgetExceeded)
        assertTrue(solver.forcedMovesThatSkippedThreatScoring > 0)
    }

    @Test
    fun `finds the double threat`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..11...",
        )
        val result = PerfectSolver().solve(s)!!
        assertEquals(SolverValue.WIN, result.value)
        // Columns 1 and 4 create the double threat; the centre column does not.
        assertTrue(result.bestMoves.contains(Move.Drop(1)))
        assertTrue(result.bestMoves.contains(Move.Drop(4)))
        assertTrue(result.bestMoves.none { it == Move.Drop(3) })
    }

    @Test
    fun `single best move query returns one of the optimal moves with the exact value`() {
        val state = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..11...",
        )
        val all = PerfectSolver().solve(state)!!
        val one = PerfectSolver().solveOneBestMove(state)!!

        assertEquals(all.value, one.value)
        assertEquals(all.score, one.score)
        assertEquals(1, one.bestMoves.size)
        assertTrue(all.bestMoves.contains(one.bestMoves.single()))
    }

    @Test
    fun `single best move query preserves exact search and progress on a general position`() {
        val state = safeMidgame()
        assertEquals(Status.InProgress, state.status)

        val all = PerfectSolver().solve(state)!!
        val progress = mutableListOf<Long>()
        val one = PerfectSolver(nodeProgressInterval = 64)
            .solveOneBestMoveWithProgress(state, onProgress = progress::add)!!
        val warmSolver = PerfectSolver(reuseTranspositionTable = true)
        warmSolver.solve(state)!!
        val oneFromCachedHint = warmSolver.solveOneBestMove(state)!!

        assertEquals(all.value, one.value)
        assertEquals(all.score, one.score)
        assertEquals(1, one.bestMoves.size)
        assertTrue(all.bestMoves.contains(one.bestMoves.single()))
        assertTrue(
            "single-move query should stop after confirming one optimum; all=${all.nodesExplored}, one=${one.nodesExplored}",
            one.nodesExplored < all.nodesExplored,
        )
        assertEquals(all.value, oneFromCachedHint.value)
        assertEquals(all.score, oneFromCachedHint.score)
        assertEquals(1, oneFromCachedHint.bestMoves.size)
        assertTrue(all.bestMoves.contains(oneFromCachedHint.bestMoves.single()))
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `single best move query maps a cached root hint across horizontal reflection`() {
        val state = safeMidgame()
        val solver = PerfectSolver(reuseTranspositionTable = true)
        val direct = solver.solve(state)!!
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })

        val mirrored = solver.solveOneBestMove(reflected)!!

        assertEquals(direct.value, mirrored.value)
        assertEquals(direct.score, mirrored.score)
        assertEquals(1, mirrored.bestMoves.size)
        assertTrue(direct.bestMoves.any { move ->
            move is Move.Drop && Move.Drop(state.width - 1 - move.col) == mirrored.bestMoves.single()
        })
    }

    @Test
    fun `the value is sound - a win stays a win through the best move`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..11...",
        )
        val solver = PerfectSolver()
        val parent = solver.solve(s)!!
        for (move in parent.bestMoves) {
            val child = GameEngine.apply(s, move).state
            val childResult = solver.solve(child)!!
            assertEquals(SolverValue.LOSS, childResult.value)
            assertEquals(-parent.score, childResult.score)
        }
    }

    @Test
    fun `classic positions replay to the same answer`() {
        val s = Boards.state(
            "1.2221.",
            "2.11122",
            "1.22211",
            "2221121",
            "2112121",
            "1211212",
        )
        val first = PerfectSolver().solve(s)
        val second = PerfectSolver().solve(s)
        assertEquals(first, second)
        assertNotNull(first)
    }

    @Test
    fun `non classic positions are not solved`() {
        val popOut = Boards.blank(popOut = true)
        assertNull(PerfectSolver().solve(popOut))
        val blocked = Boards.state(
            "#......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
        )
        assertNull(PerfectSolver().solve(blocked))
        val won = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        ).let { GameEngine.apply(it, Move.Drop(3)).state }
        assertTrue(won.status is Status.Won)
        assertNull(PerfectSolver().solve(won))
    }

    @Test
    fun `the node limit stops deep searches`() {
        val exception = org.junit.Assert.assertThrows(SolverBudgetExceeded::class.java) {
            PerfectSolver(nodeLimit = 500).solve(Boards.blank())
        }
        assertNotNull(exception)
    }

    @Test
    fun `reports periodic search progress when requested`() {
        val reports = mutableListOf<Long>()
        org.junit.Assert.assertThrows(SolverBudgetExceeded::class.java) {
            PerfectSolver(nodeLimit = 500, nodeProgressInterval = 64)
                .solveWithProgress(Boards.blank(), onProgress = reports::add)
        }

        assertTrue(reports.isNotEmpty())
        assertEquals(reports.sorted(), reports)
        assertTrue(reports.all { it % 64L == 0L })
    }

    @Test
    fun `detects a threat exposed above the move just played`() {
        // P1's move in column 3 supports P2's fourth horizontal piece on the next turn.
        val state = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            "111....",
            firstToMove = Player.P1,
        )
        val opponent = BitBoard.of(state, Player.P2)

        assertTrue(opponent.wouldWinAfterDropInSameColumn(3))
        assertTrue(!opponent.wouldWin(3))
    }

    @Test
    fun `non losing move pruning matches the full child scan`() {
        val rng = SplitMix64(4242L)
        var checked = 0
        var games = 0
        while (checked < 40 && games < 200) {
            games++
            var state = Boards.blank()
            val targetPly = 8 + rng.nextInt(30)
            repeat(targetPly) {
                if (state.status == Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != Status.InProgress) continue

            val current = BitBoard.of(state, state.toMove)
            val opponent = BitBoard.of(state, state.toMove.opponent())
            val middle = (state.width - 1) / 2
            val expected = (0 until state.width)
                .filter(current::canPlay)
                .filter { col ->
                    val afterDrop = opponent.afterOpponentDrop(col)
                    (0 until state.width).none { c -> afterDrop.canPlay(c) && afterDrop.wouldWin(c) }
                }
                .sortedBy { kotlin.math.abs(it - middle) }

            val expectedMask = expected.fold(0L) { mask, col -> mask or (1L shl col) }
            assertEquals(expectedMask, current.nonLosingColumnsAgainst(opponent))
            checked++
        }
        assertEquals(40, checked)
    }

    @Test
    fun `direct non losing scan matches child copies on two word boards`() {
        val rng = SplitMix64(20261024L)
        repeat(60) { sample ->
            val width = if (sample % 2 == 0) 7 else 9
            val height = if (sample % 2 == 0) 6 else 7
            val connect = if (sample % 2 == 0) 4 else 5
            var state = Boards.blank(width = width, height = height, connect = connect)
            repeat(8 + rng.nextInt(width * height - 8)) {
                if (state.status == Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != Status.InProgress) return@repeat

            val current = BitBoard.of(state, state.toMove)
            val opponent = BitBoard.of(state, state.toMove.opponent())
            val expectedMask = (0 until state.width)
                .filter(current::canPlay)
                .filter { col ->
                    val afterDrop = opponent.afterOpponentDrop(col)
                    (0 until state.width).none { c -> afterDrop.canPlay(c) && afterDrop.wouldWin(c) }
                }
                .fold(0L) { mask, col -> mask or (1L shl col) }

            assertEquals("non-losing mask at sample $sample", expectedMask,
                current.nonLosingColumnsAgainst(opponent))
        }
    }

    @Test
    fun `one word non losing mask handles current and newly reachable threats`() {
        fun expectedMask(state: pt.tiago.neonline.core.model.GameState): Long {
            val mine = BitBoard.of(state, state.toMove)
            val opponent = BitBoard.of(state, state.toMove.opponent())
            return (0 until state.width)
                .filter(mine::canPlay)
                .filter { col ->
                    val afterDrop = opponent.afterOpponentDrop(col)
                    (0 until state.width).none { next -> afterDrop.canPlay(next) && afterDrop.wouldWin(next) }
                }
                .fold(0L) { mask, col -> mask or (1L shl col) }
        }

        var currentThreat = Boards.blank()
        listOf(0, 1, 6, 2, 6, 3).forEach { col ->
            currentThreat = GameEngine.apply(currentThreat, Move.Drop(col)).state
        }
        val currentMine = BitBoard.of(currentThreat, currentThreat.toMove)
        val currentTheirs = BitBoard.of(currentThreat, currentThreat.toMove.opponent())
        assertEquals(1L shl 4, expectedMask(currentThreat))
        assertEquals(expectedMask(currentThreat), currentMine.nonLosingColumnsAgainst(currentTheirs))

        var newlyReachableThreat = Boards.blank()
        listOf(1, 1, 2, 2, 3, 3).forEach { col ->
            newlyReachableThreat = GameEngine.apply(newlyReachableThreat, Move.Drop(col)).state
        }
        val newlyMine = BitBoard.of(newlyReachableThreat, newlyReachableThreat.toMove)
        val newlyTheirs = BitBoard.of(newlyReachableThreat, newlyReachableThreat.toMove.opponent())
        assertEquals((1L shl 1) or (1L shl 2) or (1L shl 3) or (1L shl 5) or (1L shl 6),
            expectedMask(newlyReachableThreat))
        assertEquals(expectedMask(newlyReachableThreat), newlyMine.nonLosingColumnsAgainst(newlyTheirs))
    }

    @Test
    fun `transposition table preserves exact answer while reducing search`() {
        val rng = SplitMix64(5L)
        var state = Boards.blank()
        var ply = 0
        while (state.status == Status.InProgress && ply < 26) {
            val safe = GameEngine.validMoves(state).filter { move ->
                !pt.tiago.neonline.core.ai.Evaluator.hasThreat(
                    GameEngine.apply(state, move).state,
                    state.toMove.opponent(),
                )
            }
            val choices = safe.ifEmpty { GameEngine.validMoves(state) }
            state = GameEngine.apply(state, choices[rng.nextInt(choices.size)]).state
            ply++
        }
        assertEquals(Status.InProgress, state.status)

        val withoutCache = PerfectSolver(nodeLimit = 2_000_000L, transpositionCapacity = 0).solve(state)!!
        val withCache = PerfectSolver(nodeLimit = 2_000_000L, transpositionCapacity = 1 shl 16).solve(state)!!
        val collisionHeavy = PerfectSolver(nodeLimit = 2_000_000L, transpositionCapacity = 1).solve(state)!!

        assertEquals(withoutCache.value, withCache.value)
        assertEquals(withoutCache.score, withCache.score)
        assertEquals(withoutCache.bestMoves.toSet(), withCache.bestMoves.toSet())
        assertEquals(withoutCache.value, collisionHeavy.value)
        assertEquals(withoutCache.score, collisionHeavy.score)
        assertEquals(withoutCache.bestMoves.toSet(), collisionHeavy.bestMoves.toSet())
        assertTrue(
            "expected transposition hits; cached=${withCache.nodesExplored}, hits=${withCache.transpositionHits}",
            withCache.transpositionHits > 0,
        )
        assertTrue("cached search should visit fewer nodes", withCache.nodesExplored < withoutCache.nodesExplored)
        assertTrue("matching cached best moves should be searched first", withCache.transpositionMoveOrderingHits > 0)
        assertTrue("root value should be narrowed through multiple null-window probes", withCache.rootScoreProbes > 1)
        assertEquals(withCache.rootScoreProbes, withoutCache.rootScoreProbes)
    }

    @Test
    fun `threshold verification preserves exhaustive distance optimal ties in both query modes`() {
        fun distance(state: pt.tiago.neonline.core.model.GameState, player: Player): Int =
            when (val status = state.status) {
                is Status.Won -> (if (status.player == player) 1 else -1) *
                    (state.pieces.count { it == null } + 2) / 2
                is Status.Draw -> 0
                Status.InProgress -> {
                    val scores = GameEngine.validMoves(state).map {
                        distance(GameEngine.apply(state, it).state, player)
                    }
                    if (scores.isEmpty()) 0 else if (state.toMove == player) scores.max() else scores.min()
                }
            }

        val rng = SplitMix64(20261024L)
        val outcomes = mutableSetOf<SolverValue>()
        var searchedTies = 0
        var distanceChoices = 0
        for (connect in listOf(4, 5)) repeat(24) {
            var state = Boards.blank(width = connect, height = 4, connect = connect)
            repeat(state.width * state.height - 6) {
                val continuing = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }
                    .filter { it.status == Status.InProgress }
                if (continuing.isNotEmpty()) state = continuing[rng.nextInt(continuing.size)]
            }
            val childScores = GameEngine.validMoves(state).associateWith {
                distance(GameEngine.apply(state, it).state, state.toMove)
            }
            val expectedScore = childScores.values.max()
            val expectedMoves = childScores.filterValues { it == expectedScore }.keys
            val expectedValue = when {
                expectedScore > 0 -> SolverValue.WIN
                expectedScore < 0 -> SolverValue.LOSS
                else -> SolverValue.DRAW
            }
            outcomes += expectedValue
            if (childScores.values.any { it != expectedScore && it * expectedScore > 0 }) distanceChoices++
            // Exercise both fresh and collision-heavy tables, plus retained bounds from an all-move proof.
            for (capacity in listOf(0, 1, 1 shl 16)) {
                val solver = PerfectSolver(transpositionCapacity = capacity, reuseTranspositionTable = true)
                val all = solver.solve(state)!!
                val one = PerfectSolver(transpositionCapacity = capacity).solveOneBestMove(state)!!
                val warmOne = solver.solveOneBestMove(state)!!
                assertEquals(expectedValue, all.value)
                assertEquals(expectedScore, all.score)
                assertEquals(expectedMoves, all.bestMoves.toSet())
                for (result in listOf(one, warmOne)) {
                    assertEquals(expectedValue, result.value)
                    assertEquals(expectedScore, result.score)
                    assertEquals(1, result.bestMoves.size)
                    assertTrue(result.bestMoves.single() in expectedMoves)
                }
                if (all.rootScoreProbes > 0 && expectedMoves.size > 1) searchedTies++
            }
        }
        assertEquals(setOf(SolverValue.WIN, SolverValue.DRAW, SolverValue.LOSS), outcomes)
        assertTrue("include ties that reach root search rather than short proofs", searchedTies > 0)
        assertTrue("include different distances with the same outcome", distanceChoices > 0)
    }

    @Test
    fun `threshold move verification uses fewer nodes than full window on safe midgame`() {
        val state = safeMidgame()
        // Measured full-window baselines on this exact board with transpositions disabled.
        // Disabling the table isolates child verification from previously cached bounds.
        val fullWindowAllNodes = 3_813L
        val fullWindowOneNodes = 3_077L
        val all = PerfectSolver(transpositionCapacity = 0).solve(state)!!
        val one = PerfectSolver(transpositionCapacity = 0).solveOneBestMove(state)!!
        println("safeMidgame capacity=0: all=${all.nodesExplored}, one=${one.nodesExplored}")
        assertEquals(SolverValue.LOSS, all.value)
        assertEquals(-2, all.score)
        assertEquals(listOf(Move.Drop(3)), all.bestMoves)
        assertEquals(all.value, one.value)
        assertEquals(all.score, one.score)
        assertEquals(all.bestMoves, one.bestMoves)
        assertTrue("all-move nodes=${all.nodesExplored}, full-window baseline=$fullWindowAllNodes",
            all.nodesExplored < fullWindowAllNodes)
        assertTrue("one-move nodes=${one.nodesExplored}, full-window baseline=$fullWindowOneNodes",
            one.nodesExplored < fullWindowOneNodes)
    }

    @Test
    fun `safe score bounds fit within a tight exact search budget`() {
        val result = PerfectSolver(nodeLimit = 1_170L).solve(safeMidgame())!!

        assertTrue("solver exceeded the expected node budget: ${result.nodesExplored}", result.nodesExplored <= 1_170L)
    }

    @Test
    fun `single word compact key exactly encodes canonical position and reflection`() {
        fun stateAfter(vararg moves: Int) = moves.fold(Boards.blank()) { state, col ->
            GameEngine.apply(state, Move.Drop(col)).state
        }
        val state = stateAfter(0, 1, 3, 0, 2, 5)
        val other = stateAfter(2, 4, 1, 5, 0, 6)
        fun reflected(board: pt.tiago.neonline.core.model.GameState) = board.copy(
            pieces = List(board.pieces.size) { index ->
                val row = index / board.width
                val col = index % board.width
                board.pieceAt(row, board.width - 1 - col)
            },
        )
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())
        val table = PerfectSolver.TranspositionTable(32, wordCount = 1)
        val (mine, theirs) = boards(state)
        val (mirrorMine, mirrorTheirs) = boards(reflected(state))
        val (otherMine, otherTheirs) = boards(other)
        fun expectedKey(current: BitBoard, opponent: BitBoard): Long {
            val occupied = current.keyWord(0) or opponent.keyWord(0)
            val mirroredMine = current.mirroredKeyWord(0)
            val mirroredOccupied = mirroredMine or opponent.mirroredKeyWord(0)
            val forward = current.keyWord(0) + occupied
            val mirror = mirroredMine + mirroredOccupied
            return if (java.lang.Long.compareUnsigned(forward, mirror) <= 0) forward else mirror
        }

        assertEquals(expectedKey(mine, theirs), table.compactCanonicalKey(mine, theirs))
        assertEquals(
            table.compactCanonicalKey(mine, theirs),
            table.compactCanonicalKey(mirrorMine, mirrorTheirs),
        )
        assertNotEquals(
            table.compactCanonicalKey(mine, theirs),
            table.compactCanonicalKey(otherMine, otherTheirs),
        )
    }

    @Test
    fun `single word canonical hash mixes the compact identity and ply`() {
        val state = listOf(0, 1, 3, 0, 2, 5).fold(Boards.blank()) { board, col ->
            GameEngine.apply(board, Move.Drop(col)).state
        }
        val mine = BitBoard.of(state, state.toMove)
        val theirs = BitBoard.of(state, state.toMove.opponent())
        val table = PerfectSolver.TranspositionTable(32, wordCount = 1)
        var expected = table.compactCanonicalKey(mine, theirs) * -7046029254386353131L
        expected = (expected xor state.ply.toLong()) * -4658895280553007687L
        expected = expected xor (expected ushr 32)

        assertEquals(expected, table.canonicalHash(mine, theirs, state.ply))
    }

    @Test
    fun `optional transposition reuse preserves answers and shortens repeated proofs`() {
        val rng = SplitMix64(5L)
        var state = Boards.blank()
        repeat(26) {
            if (state.status == Status.InProgress) {
                val safe = GameEngine.validMoves(state).filter { move ->
                    !pt.tiago.neonline.core.ai.Evaluator.hasThreat(
                        GameEngine.apply(state, move).state,
                        state.toMove.opponent(),
                    )
                }
                val choices = safe.ifEmpty { GameEngine.validMoves(state) }
                state = GameEngine.apply(state, choices[rng.nextInt(choices.size)]).state
            }
        }
        assertEquals(Status.InProgress, state.status)

        val solver = PerfectSolver(reuseTranspositionTable = true)
        val cold = solver.solve(state)!!
        val warm = solver.solve(state)!!

        assertEquals(cold.value, warm.value)
        assertEquals(cold.score, warm.score)
        assertEquals(cold.bestMoves.toSet(), warm.bestMoves.toSet())
        assertTrue(
            "warm proof should use retained cache entries; hits=${warm.transpositionHits}",
            warm.transpositionHits > 0,
        )
        assertTrue(
            "warm proof should explore fewer nodes; cold=${cold.nodesExplored}, warm=${warm.nodesExplored}",
            warm.nodesExplored < cold.nodesExplored,
        )

        val child = GameEngine.apply(state, cold.bestMoves.first()).state
        val freshChild = PerfectSolver().solve(child)!!
        val retainedChild = solver.solve(child)!!
        assertEquals(freshChild.value, retainedChild.value)
        assertEquals(freshChild.score, retainedChild.score)
        assertEquals(freshChild.bestMoves.toSet(), retainedChild.bestMoves.toSet())
        assertTrue(retainedChild.transpositionHits > 0)
        assertTrue(retainedChild.nodesExplored < freshChild.nodesExplored)

        val fiveInARow = state.copy(connect = 5)
        val freshFive = PerfectSolver().solve(fiveInARow)!!
        val retainedFive = solver.solve(fiveInARow)!!
        assertEquals(freshFive.value, retainedFive.value)
        assertEquals(freshFive.score, retainedFive.score)
        assertEquals(freshFive.bestMoves.toSet(), retainedFive.bestMoves.toSet())
    }

    @Test
    fun `transposition table maps cached best moves across horizontal reflection`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())

        val table = PerfectSolver.TranspositionTable(16)
        val (mine, theirs) = boards(state)
        val (mirroredMine, mirroredTheirs) = boards(reflected)
        table.put(mine, theirs, state.ply, score = 7, bound = 1, bestMove = 1)

        val directEntry = table.get(mine, theirs, state.ply)!!
        val reflectedEntry = table.get(mirroredMine, mirroredTheirs, reflected.ply)!!
        assertEquals(7, directEntry.score)
        assertEquals(1, directEntry.bound)
        assertEquals(1, directEntry.bestMove)
        assertEquals(7, reflectedEntry.score)
        assertEquals(1, reflectedEntry.bound)
        assertEquals(state.width - 1 - 1, reflectedEntry.bestMove)
    }

    @Test
    fun `single word transposition table preserves entries across reflection`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())

        val (mine, theirs) = boards(state)
        val (mirroredMine, mirroredTheirs) = boards(reflected)
        val table = PerfectSolver.TranspositionTable(16, wordCount = 1)
        val key = table.keyFor(mine, theirs, state.ply)
        val probe = PerfectSolver.TranspositionTable.Probe()

        table.putAtKey(key, mine, theirs, state.ply, score = -3, bound = 2, bestMove = 1)

        assertTrue(table.readAtKey(key, mine, theirs, state.ply, probe))
        assertEquals(-3, probe.score)
        assertEquals(2, probe.bound)
        assertEquals(1, probe.bestMove)
        val reflectedKey = table.keyFor(mirroredMine, mirroredTheirs, reflected.ply)
        assertTrue(table.readAtKey(reflectedKey, mirroredMine, mirroredTheirs, reflected.ply, probe))
        assertEquals(-3, probe.score)
        assertEquals(2, probe.bound)
        assertEquals(state.width - 1 - 1, probe.bestMove)
    }

    @Test
    fun `transposition snapshots round trip entries and validate board metadata`() {
        val state = listOf(0, 1, 3, 0, 2, 5).fold(Boards.blank()) { board, col ->
            GameEngine.apply(board, Move.Drop(col)).state
        }
        val mine = BitBoard.of(state, state.toMove)
        val theirs = BitBoard.of(state, state.toMove.opponent())
        val table = PerfectSolver.TranspositionTable(16, wordCount = 1)
        table.put(mine, theirs, state.ply, score = -3, bound = 2, bestMove = 1)
        val bytes = ByteArrayOutputStream().also { output ->
            table.writeSnapshot(output, state.width, state.height, state.connect)
        }.toByteArray()

        val restored = PerfectSolver.TranspositionTable.readSnapshot(
            ByteArrayInputStream(bytes), state.width, state.height, state.connect,
        )

        assertEquals(16, restored.capacity)
        assertEquals(1, restored.wordCount)
        assertEquals(PerfectSolver.TranspositionTable.Entry(-3, 2, 1), restored.get(mine, theirs, state.ply))
        val expanded = PerfectSolver.TranspositionTable.readSnapshot(
            ByteArrayInputStream(bytes), state.width, state.height, state.connect, targetCapacity = 32,
        )
        assertEquals(32, expanded.capacity)
        assertEquals(PerfectSolver.TranspositionTable.Entry(-3, 2, 1), expanded.get(mine, theirs, state.ply))
        val corrupted = bytes.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
        val checksumError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PerfectSolver.TranspositionTable.readSnapshot(
                ByteArrayInputStream(corrupted), state.width, state.height, state.connect,
            )
        }
        assertEquals("transposition snapshot checksum mismatch", checksumError.message)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PerfectSolver.TranspositionTable.readSnapshot(ByteArrayInputStream(bytes), 6, 6, 4)
        }
    }

    @Test
    fun `solver restores a smaller transposition snapshot into its larger configured table`() {
        val state = safeMidgame()
        val original = PerfectSolver(transpositionCapacity = 16, reuseTranspositionTable = true)
        val expected = original.solveOneBestMove(state)!!
        val bytes = ByteArrayOutputStream().also { output ->
            assertTrue(original.writeTranspositionTableSnapshot(output, state.width, state.height, state.connect))
        }.toByteArray()
        val expanded = PerfectSolver(transpositionCapacity = 32, reuseTranspositionTable = true)

        expanded.restoreTranspositionTableSnapshot(
            ByteArrayInputStream(bytes), state.width, state.height, state.connect,
        )
        val actual = expanded.solveOneBestMove(state)!!

        assertEquals(expected.score, actual.score)
        assertTrue("the restored entries should participate in the search", actual.transpositionHits > 0)
    }

    @Test
    fun `two word transposition snapshots preserve high words and reflected moves`() {
        val state = (0..8).fold(Boards.blank(width = 9, height = 7, connect = 5)) { board, col ->
            GameEngine.apply(board, Move.Drop(col)).state
        }.let { board -> GameEngine.apply(board, Move.Drop(8)).state }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        val mine = BitBoard.of(state, state.toMove)
        val theirs = BitBoard.of(state, state.toMove.opponent())
        val reflectedMine = BitBoard.of(reflected, reflected.toMove)
        val reflectedTheirs = BitBoard.of(reflected, reflected.toMove.opponent())
        assertTrue(mine.keyWord(1) != 0L || theirs.keyWord(1) != 0L)
        val table = PerfectSolver.TranspositionTable(16, wordCount = 2)
        table.put(mine, theirs, state.ply, score = 3, bound = 0, bestMove = 8)
        val bytes = ByteArrayOutputStream().also { output ->
            table.writeSnapshot(output, state.width, state.height, state.connect)
        }.toByteArray()

        val restored = PerfectSolver.TranspositionTable.readSnapshot(
            ByteArrayInputStream(bytes), state.width, state.height, state.connect,
        )

        assertEquals(PerfectSolver.TranspositionTable.Entry(3, 0, 8), restored.get(mine, theirs, state.ply))
        assertEquals(PerfectSolver.TranspositionTable.Entry(3, 0, 0),
            restored.get(reflectedMine, reflectedTheirs, reflected.ply))
        val expanded = PerfectSolver.TranspositionTable.readSnapshot(
            ByteArrayInputStream(bytes), state.width, state.height, state.connect, targetCapacity = 32,
        )
        assertEquals(32, expanded.capacity)
        assertEquals(PerfectSolver.TranspositionTable.Entry(3, 0, 8), expanded.get(mine, theirs, state.ply))
        assertEquals(PerfectSolver.TranspositionTable.Entry(3, 0, 0),
            expanded.get(reflectedMine, reflectedTheirs, reflected.ply))
    }

    @Test
    fun `canonical transposition hash is identical for a board and its reflection`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())
        val (mine, theirs) = boards(state)
        val (mirroredMine, mirroredTheirs) = boards(reflected)
        val table = PerfectSolver.TranspositionTable(16)

        assertEquals(
            table.canonicalHash(mine, theirs, state.ply),
            table.canonicalHash(mirroredMine, mirroredTheirs, reflected.ply),
        )
    }

    @Test
    fun `explicit transposition slot access matches position lookup helpers`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val mine = BitBoard.of(state, state.toMove)
        val theirs = BitBoard.of(state, state.toMove.opponent())
        val table = PerfectSolver.TranspositionTable(16)
        val slot = table.slotFor(mine, theirs, state.ply)

        table.putAt(slot, mine, theirs, state.ply, score = -3, bound = 1, bestMove = 4)

        assertEquals(
            table.get(mine, theirs, state.ply),
            table.getAt(slot, mine, theirs, state.ply),
        )
        assertEquals(-3, table.getAt(slot, mine, theirs, state.ply)!!.score)
    }

    @Test
    fun `canonical transposition key token preserves reflected best move`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())

        val (mine, theirs) = boards(state)
        val (mirroredMine, mirroredTheirs) = boards(reflected)
        val table = PerfectSolver.TranspositionTable(16)
        val key = table.keyFor(mine, theirs, state.ply)
        val mirroredKey = table.keyFor(mirroredMine, mirroredTheirs, reflected.ply)

        assertEquals(key, mirroredKey xor table.mirrorFlag)
        table.putAtKey(key, mine, theirs, state.ply, score = 7, bound = 1, bestMove = 1)

        assertEquals(1, table.getAtKey(key, mine, theirs, state.ply)!!.bestMove)
        assertEquals(
            state.width - 1 - 1,
            table.getAtKey(mirroredKey, mirroredMine, mirroredTheirs, reflected.ply)!!.bestMove,
        )
    }

    @Test
    fun `canonical transposition key token distinguishes same-slot positions`() {
        fun stateAfter(moves: List<Int>) = moves.fold(Boards.blank()) { state, col ->
            GameEngine.apply(state, Move.Drop(col)).state
        }
        val first = stateAfter(listOf(0, 1, 3, 0, 2, 5))
        val second = stateAfter(listOf(2, 4, 1, 5, 0, 6))
        val table = PerfectSolver.TranspositionTable(1)
        fun key(state: pt.tiago.neonline.core.model.GameState): Long {
            val mine = BitBoard.of(state, state.toMove)
            val theirs = BitBoard.of(state, state.toMove.opponent())
            return table.keyFor(mine, theirs, state.ply)
        }
        val firstKey = key(first)
        val secondKey = key(second)

        assertEquals(first.ply, second.ply)
        assertEquals(firstKey and table.mirrorFlag, secondKey and table.mirrorFlag)
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun `transposition probe fills reusable result for direct and reflected positions`() {
        var state = Boards.blank()
        listOf(0, 1, 3, 0, 2, 5).forEach { col ->
            state = GameEngine.apply(state, Move.Drop(col)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })
        fun boards(board: pt.tiago.neonline.core.model.GameState) =
            BitBoard.of(board, board.toMove) to BitBoard.of(board, board.toMove.opponent())

        val (mine, theirs) = boards(state)
        val (mirroredMine, mirroredTheirs) = boards(reflected)
        val table = PerfectSolver.TranspositionTable(16)
        val result = PerfectSolver.TranspositionTable.Probe()
        val key = table.keyFor(mine, theirs, state.ply)
        table.putAtKey(key, mine, theirs, state.ply, score = -3, bound = 2, bestMove = 1)

        assertTrue(table.readAtKey(key, mine, theirs, state.ply, result))
        assertEquals(-3, result.score)
        assertEquals(2, result.bound)
        assertEquals(1, result.bestMove)
        val reflectedKey = table.keyFor(mirroredMine, mirroredTheirs, reflected.ply)
        assertTrue(table.readAtKey(reflectedKey, mirroredMine, mirroredTheirs, reflected.ply, result))
        assertEquals(-3, result.score)
        assertEquals(2, result.bound)
        assertEquals(state.width - 1 - 1, result.bestMove)
        assertTrue(!table.readAtKey(key, mine, theirs, state.ply + 1, result))
    }

    @Test
    fun `transposition table preserves exact entries over colliding bounds`() {
        fun boards(sequence: List<Int>): Triple<pt.tiago.neonline.core.model.GameState, BitBoard, BitBoard> {
            var state = Boards.blank()
            sequence.forEach { col -> state = GameEngine.apply(state, Move.Drop(col)).state }
            return Triple(
                state,
                BitBoard.of(state, state.toMove),
                BitBoard.of(state, state.toMove.opponent()),
            )
        }
        val (exactState, exactMine, exactTheirs) = boards(listOf(0, 1, 3, 0, 2, 5))
        val (boundState, boundMine, boundTheirs) = boards(listOf(2, 4, 1, 5, 0, 6))
        val table = PerfectSolver.TranspositionTable(1)

        table.put(exactMine, exactTheirs, exactState.ply, score = 7, bound = 1, bestMove = 1)
        table.put(boundMine, boundTheirs, boundState.ply, score = -3, bound = 0, bestMove = 2)

        val retained = table.get(exactMine, exactTheirs, exactState.ply)
        assertNotNull("a colliding upper bound must not evict an exact value", retained)
        assertEquals(7, retained!!.score)
        assertEquals(1, retained.bound)
        assertEquals(1, retained.bestMove)
    }

    @Test
    fun `the solver agrees with the engine on random shallow positions`() {
        val rng = SplitMix64(99L)
        val solver = PerfectSolver()
        var checked = 0
        var games = 0
        while (checked < 4 && games < 120) {
            games++
            var s = Boards.blank()
            // Play "safe" random moves so the position reaches deep plies, then stop mid-game.
            val target = 24 + rng.nextInt(12)
            var ply = 0
            while (s.status == Status.InProgress && ply < target) {
                ply++
                val safe = GameEngine.validMoves(s).filter { move ->
                    !pt.tiago.neonline.core.ai.Evaluator.hasThreat(
                        GameEngine.apply(s, move).state,
                        s.toMove.opponent(),
                    )
                }
                val moves = safe.ifEmpty { GameEngine.validMoves(s) }
                s = GameEngine.apply(s, moves[rng.nextInt(moves.size)]).state
            }
            if (s.status != Status.InProgress) continue
            if (!BitBoard.supports(s)) continue
            val result = try {
                solver.solve(s) ?: continue
            } catch (_: SolverBudgetExceeded) {
                continue
            }
            checked++
            for (move in result.bestMoves) {
                val child = GameEngine.apply(s, move).state
                val childResult = try {
                    solver.solve(child) ?: continue
                } catch (_: SolverBudgetExceeded) {
                    continue
                }
                // The value must invert through a best move; that is what Perfect mode relies on.
                when (result.value) {
                    SolverValue.WIN -> assertEquals(SolverValue.LOSS, childResult.value)
                    SolverValue.LOSS -> assertEquals(SolverValue.WIN, childResult.value)
                    SolverValue.DRAW -> assertEquals(SolverValue.DRAW, childResult.value)
                }
                // The ply score is advisory: the endgame terminal conventions can differ by one,
                // so only the sign of the difference is checked here.
                val parentSign = if (result.score > 0) 1 else if (result.score < 0) -1 else 0
                val childSign = if (childResult.score > 0) 1 else if (childResult.score < 0) -1 else 0
                assertEquals("score sign mismatch for $move", -parentSign, childSign)
            }
        }
        assertTrue("checked too few positions: $checked", checked >= 4)
    }
}
