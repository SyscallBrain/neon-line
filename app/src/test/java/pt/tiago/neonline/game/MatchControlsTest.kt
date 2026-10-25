package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*

@OptIn(ExperimentalCoroutinesApi::class)
class MatchControlsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun start() = GameState.initial(
        levelId = "t", difficulty = Difficulty.EASY, engineVersion = 1,
        seedHex = "0123456789abcdef", width = 7, height = 6, connect = 4,
        playable = List(42) { true }, pieces = List(42) { null },
    )
    private fun model(state: GameState = start(), clock: Int? = null, objective: LevelObjective = LevelObjective(LevelKind.DUEL)) = GameViewModel(
        state, objective, AiProfile.EASY.copy(maxDepth = 2, nodeBudget = 100),
        hintsAvailable = 3, undoAvailable = 3, clockSeconds = clock,
        animationEnabled = false, searchDispatcher = dispatcher,
    )

    @Test fun `undo restores board streams and move count after rival reply`() = runTest(dispatcher) {
        val start = start()
        val model = model(start)
        model.play(Move.Drop(3))
        runCurrent()
        assertEquals(2, model.ui.value.state.ply)
        model.undo()
        assertEquals(start, model.ui.value.state)
        assertEquals(0, model.ui.value.humanMoveCount)
        assertEquals(2, model.ui.value.undosLeft)
        model.pause()
    }

    @Test fun `restart works after a win and replenishes controls`() = runTest(dispatcher) {
        val base = start()
        val pieces = base.pieces.toMutableList()
        (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
        val start = base.copy(pieces = pieces)
        val model = model(start)
        model.play(Move.Drop(3))
        assertTrue(model.ui.value.finished)
        model.restart()
        assertEquals(start, model.ui.value.state)
        assertNull(model.ui.value.outcome)
        assertEquals(0, model.ui.value.humanMoveCount)
        assertEquals(3, model.ui.value.undosLeft)
        model.pause()
    }

    @Test fun `clock starts on first turn pauses and resets for next turn`() = runTest(dispatcher) {
        val model = model(clock = 2)
        advanceTimeBy(500); runCurrent()
        assertEquals(1500L, model.ui.value.clockMs)
        model.pause()
        advanceTimeBy(1000); runCurrent()
        assertEquals(1500L, model.ui.value.clockMs)
        model.resume()
        model.play(Move.Drop(3)); runCurrent()
        assertEquals(2000L, model.ui.value.clockMs)
        model.pause()
    }

    @Test fun `puzzle stars include hints actually used`() = runTest(dispatcher) {
        val base = start()
        val pieces = base.pieces.toMutableList()
        (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
        val model = model(base.copy(pieces = pieces), objective = LevelObjective(LevelKind.PUZZLE, winInMoves = 1))
        model.hint(); runCurrent()
        assertEquals(2, model.ui.value.hintsLeft)
        model.play(Move.Drop(3))
        assertEquals(LevelOutcome.Won(2), model.ui.value.outcome)
        model.pause()
    }

    @Test fun `pausing pending rival search prevents a late move`() = runTest(dispatcher) {
        val model = model()
        model.play(Move.Drop(3))
        model.pause(); runCurrent()
        assertEquals(1, model.ui.value.state.ply)
        model.resume(); runCurrent()
        assertEquals(2, model.ui.value.state.ply)
        model.pause()
    }
}
