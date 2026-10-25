package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class PeekGameTest {
    private val dispatcher = StandardTestDispatcher()
    private var profile = Profile()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun config(difficulty: Difficulty = Difficulty.HARD) = FreePlayConfig(version = 3, difficulty = difficulty,
        rules = FreePlayRules(fade = true, popOut = true, clockSeconds = 10))
    private fun model(config: FreePlayConfig, saved: SavedMatch? = null) = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
        hintsAvailable = config.hints(), undoAvailable = config.undos(), animationEnabled = false,
        savedMatch = saved, searchDispatcher = dispatcher, hintProfile = config.hintAi()!!, maxPlies = config.maxPlies(),
        onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })

    @Test fun `peek expires without playing spending resources or stopping clock and permits observed pop`() = runTest(dispatcher) {
        val game = model(config()); runCurrent()
        game.playColumn(0); runCurrent()
        assertEquals(2, game.ui.value.state.ply)
        assertFalse(BoardAccessibility.canPop(game.ui.value, 0))
        val before = game.snapshot()
        game.peek(0); runCurrent()
        assertEquals(0, game.ui.value.peekColumn)
        assertEquals(1, game.ui.value.peekCount)
        game.peek(0); assertEquals(1, game.ui.value.peekCount)
        assertEquals(before.stateHash, game.snapshot().stateHash)
        assertEquals(before.steps, game.snapshot().steps)
        assertEquals(before.hintsLeft, game.snapshot().hintsLeft)
        assertEquals(before.undosLeft, game.snapshot().undosLeft)
        advanceTimeBy(600); runCurrent()
        assertNull(game.ui.value.peekColumn)
        assertEquals(setOf(0), game.ui.value.peekedColumns)
        assertTrue(game.ui.value.clockMs!! < before.clockMs!!)
        assertEquals(1L, profile.freePlayPeeks[Difficulty.HARD])
        game.requestPop(0); assertEquals(0, game.ui.value.pendingPopColumn)
        game.confirmPop(); runCurrent()
        assertTrue(game.ui.value.peekedColumns.isEmpty())
        assertEquals("pop", game.snapshot().steps[2].action)
        game.pause()
        assertEquals(game.ui.value.state, FreePlay.restore(profile.savedFreePlay!!)!!.state)
        assertTrue(profile.freePlayStats.isEmpty())
    }

    @Test fun `pause resume saves count only and never recounts a reveal`() = runTest(dispatcher) {
        val config = config(); val game = model(config); runCurrent()
        game.playColumn(0); runCurrent(); game.peek(0); runCurrent()
        game.pause()
        assertNull(game.ui.value.peekColumn); assertTrue(game.ui.value.peekedColumns.isEmpty())
        val resumed = model(config, profile.savedFreePlay!!.match); runCurrent()
        assertEquals(1, resumed.ui.value.peekCount)
        assertNull(resumed.ui.value.peekColumn); assertTrue(resumed.ui.value.peekedColumns.isEmpty())
        assertEquals(1L, profile.freePlayPeeks[Difficulty.HARD])
        resumed.peek(0); runCurrent(); resumed.pause()
        assertEquals(2L, profile.freePlayPeeks[Difficulty.HARD])
        assertEquals(2, profile.savedFreePlay!!.match.peekCount)
    }

    @Test fun `switching columns cancels the previous expiry`() = runTest(dispatcher) {
        val game = model(config()); runCurrent()
        game.playColumn(0); runCurrent(); game.playColumn(1); runCurrent()
        assertTrue(PeekVisibility.canPeek(game.ui.value, 0)); assertTrue(PeekVisibility.canPeek(game.ui.value, 1))
        game.peek(0); runCurrent(); advanceTimeBy(300); runCurrent()
        game.peek(1); runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(1, game.ui.value.peekColumn)
        advanceTimeBy(300); runCurrent(); assertNull(game.ui.value.peekColumn)
        assertEquals(2, game.ui.value.peekCount)
        assertEquals(2L, profile.freePlayPeeks[Difficulty.HARD]); game.pause()
    }

    @Test fun `undo clears observation but keeps count and restart clears attempt only`() = runTest(dispatcher) {
        val game = model(config(Difficulty.EASY)); runCurrent()
        for (column in 0..2) { game.playColumn(column); runCurrent() }
        assertTrue(game.ui.value.acceptsInput)
        game.peek(0); runCurrent(); assertEquals(1, game.ui.value.peekCount)
        game.undo(); runCurrent()
        assertNull(game.ui.value.peekColumn); assertTrue(game.ui.value.peekedColumns.isEmpty())
        assertEquals(1, game.ui.value.peekCount)
        game.restart(); runCurrent()
        assertEquals(0, game.ui.value.peekCount)
        assertEquals(1L, profile.freePlayPeeks[Difficulty.EASY]); game.pause()
    }
}
