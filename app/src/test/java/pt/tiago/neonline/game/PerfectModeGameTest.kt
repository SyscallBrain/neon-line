package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LevelObjective
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectOpeningBook

@OptIn(ExperimentalCoroutinesApi::class)
class PerfectModeGameTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `perfect rival applies its exact opening book move`() = runTest(dispatcher) {
        val start = tacticalPosition()
        val book = PerfectOpeningBook.create(6, listOf(PerfectOpeningBook.Seed(start, 3, 18)))
        val diagnostics = mutableListOf<String>()
        val game = GameViewModel(
            start, LevelObjective(LevelKind.DUEL), AiProfile.HARD, hintsAvailable = 0,
            animationEnabled = false, searchDispatcher = dispatcher,
            perfectRival = true, openingBook = book, perfectSearchLogger = diagnostics::add,
        )

        runCurrent()

        assertEquals(Status.Won(Player.P2), game.ui.value.state.status)
        assertEquals(1, game.snapshot().steps.size)
        assertEquals(3, game.snapshot().steps.single().column)
        assertTrue(game.ui.value.lastAiNodes == 0)
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.single().contains("source=book"))
        assertTrue(diagnostics.single().contains("timed_out=false"))
        assertTrue(diagnostics.single().contains("nodes=0"))
    }

    @Test
    fun `pausing before the exact search runs prevents a stale rival move`() = runTest(dispatcher) {
        val start = emptyStarterP2()
        val game = GameViewModel(
            start, LevelObjective(LevelKind.DUEL), AiProfile.HARD, hintsAvailable = 0,
            animationEnabled = false, searchDispatcher = dispatcher, perfectRival = true,
        )

        game.pause()
        runCurrent()

        assertTrue(game.ui.value.paused)
        assertTrue(game.snapshot().steps.isEmpty())
        assertEquals(start, game.ui.value.state)
    }

    private fun tacticalPosition(): GameState {
        var state = emptyStarterP2()
        for (column in listOf(0, 4, 1, 5, 2, 6)) {
            state = GameEngine.apply(state, Move.Drop(column, PieceKind.NORMAL)).state
        }
        return state
    }

    private fun emptyStarterP2() = GameState.initial(
        levelId = "perfect-mode-test", difficulty = Difficulty.HARD,
        engineVersion = GameEngine.ENGINE_VERSION, seedHex = "0000000000000001",
        width = 7, height = 6, connect = 4, playable = List(42) { true },
        pieces = List(42) { null }, firstToMove = Player.P2,
    )
}
