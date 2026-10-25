package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.core.rules.GameEngine

@OptIn(ExperimentalCoroutinesApi::class)
class TacticalReviewGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `review only exposes finished input and cannot change live state or checkpoint`() = runTest(dispatcher) {
        var start = GameState.initial("review", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef",
            7, 6, 4, List(42) { true }, List(42) { null })
        for (column in listOf(0, 6, 1, 6, 2, 5)) start = GameEngine.apply(start, Move.Drop(column)).state
        var checkpoints = 0
        val game = GameViewModel(start, LevelObjective(LevelKind.PUZZLE, winInMoves = 1), AiProfile.EASY,
            animationEnabled = false, searchDispatcher = dispatcher, elapsedRealtimeMs = { 100L },
            onCheckpoint = { _, _, _ -> checkpoints++ })
        assertNull(game.reviewInput())
        game.playColumn(4)
        val input = game.reviewInput()!!
        assertEquals(1, input.puzzleLimit)
        val ui = game.ui.value
        val journal = game.snapshot()
        val count = checkpoints
        assertEquals(TacticalReviewStatus.FOUND, TacticalReviewAnalyzer.analyze(input).status)
        assertEquals(ui, game.ui.value)
        assertEquals(journal, game.snapshot())
        assertEquals(count, checkpoints)
        game.restart()
        assertNull(game.reviewInput())
        game.pause()
    }
    @Test fun `boss review captures the active duel after switching round starts`() = runTest(dispatcher) {
        var start = GameState.initial("review", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef",
            7, 6, 4, List(42) { true }, List(42) { null })
        for (column in listOf(0, 6, 1, 6, 2, 5)) start = GameEngine.apply(start, Move.Drop(column)).state
        val mirrored = start.copy(pieces = (0 until start.height).flatMap { row ->
            (0 until start.width).map { column -> start.pieces[row * start.width + start.width - 1 - column] }
        })
        val game = GameViewModel(start, LevelObjective(LevelKind.BOSS), AiProfile.EASY,
            animationEnabled = false, searchDispatcher = dispatcher, bossRoundStates = listOf(start, mirrored, start))
        game.playColumn(3)
        assertNotNull(game.ui.value.roundOutcome)
        assertNull(game.ui.value.outcome)
        assertEquals(start, game.reviewInput()!!.start)
        game.nextBossRound()
        assertNull(game.reviewInput())
        game.playColumn(3)
        val input = game.reviewInput()!!
        assertEquals(mirrored, input.start)
        assertEquals(1, input.journal.bossHistory.size)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalReviewAnalyzer.analyze(input).status)
        game.pause()
    }

}
