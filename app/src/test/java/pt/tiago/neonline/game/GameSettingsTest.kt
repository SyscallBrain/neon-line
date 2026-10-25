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
import pt.tiago.neonline.data.Settings

@OptIn(ExperimentalCoroutinesApi::class)
class GameSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun blank() = GameState.initial("settings", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })
    private fun model(state: GameState = blank(), confirm: Boolean = true, delayMs: Long = 0L) = GameViewModel(
        state, LevelObjective(LevelKind.DUEL), AiProfile.EASY.copy(maxDepth = 1, nodeBudget = 100),
        undoAvailable = 3, animationEnabled = false, confirmMove = confirm, aiDelayMs = delayMs,
        searchDispatcher = dispatcher,
    )

    @Test fun `first tap and changing column leave board streams inventory and saved history intact`() = runTest(dispatcher) {
        val state = blank().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1)))
        val game = model(state)
        game.selectPiece(PieceKind.WILD)
        game.playColumn(0)
        game.playColumn(99)
        assertEquals(Move.Drop(0, PieceKind.WILD), game.ui.value.pendingDrop)
        game.playColumn(1)
        assertEquals(state, game.ui.value.state)
        assertEquals(0, game.ui.value.humanMoveCount)
        assertTrue(game.snapshot().steps.isEmpty())
        game.playColumn(1); runCurrent()
        assertEquals(1, game.ui.value.humanMoveCount)
        assertEquals(0, game.ui.value.state.inventory[Player.P1]!![PieceKind.WILD])
        assertNull(game.ui.value.pendingDrop)
        game.undo()
        assertEquals(state, game.ui.value.state)
        assertTrue(game.snapshot().steps.isEmpty())
        game.pause()
    }

    @Test fun `pausing or changing piece cancels a pending confirmation`() = runTest(dispatcher) {
        val state = blank().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1)))
        val game = model(state)
        game.playColumn(0)
        game.selectPiece(PieceKind.WILD)
        assertNull(game.ui.value.pendingDrop)
        game.playColumn(0)
        game.pause(); game.resume(); game.playColumn(0)
        assertEquals(state, game.ui.value.state)
        assertEquals(Move.Drop(0, PieceKind.WILD), game.ui.value.pendingDrop)
        game.pause()
    }

    @Test fun `a pending choice is not restored as a played move`() = runTest(dispatcher) {
        val game = model()
        game.playColumn(2)
        val saved = game.snapshot()
        val restored = GameViewModel(blank(), LevelObjective(LevelKind.DUEL), AiProfile.EASY,
            undoAvailable = 3, animationEnabled = false, confirmMove = true, searchDispatcher = dispatcher, savedMatch = saved)
        assertEquals(blank(), restored.ui.value.state)
        assertNull(restored.ui.value.pendingDrop)
        assertEquals(0, restored.ui.value.humanMoveCount)
        game.pause(); restored.pause()
    }

    @Test fun `fast response changes only delay and keeps identical rival moves and random streams`() = runTest(dispatcher) {
        val normal = model(confirm = false, delayMs = 450)
        val fast = model(confirm = false)
        normal.playColumn(0); fast.playColumn(0); runCurrent()
        assertEquals(1, normal.ui.value.state.ply)
        assertEquals(2, fast.ui.value.state.ply)
        advanceTimeBy(450); runCurrent()
        assertEquals(fast.ui.value.state, normal.ui.value.state)
        assertEquals(fast.snapshot().steps, normal.snapshot().steps)
        normal.pause(); fast.pause()
    }

    @Test fun `feedback is independently disabled by user preferences and the ringer mode`() {
        assertEquals(FeedbackPolicy(0.8f, true), FeedbackPolicy.resolve(Settings(), audible = true, silent = false))
        assertEquals(FeedbackPolicy(0f, false), FeedbackPolicy.resolve(Settings(), audible = false, silent = true))
        assertEquals(FeedbackPolicy(0f, true), FeedbackPolicy.resolve(Settings(), audible = false, silent = false))
        assertEquals(FeedbackPolicy(0f, true), FeedbackPolicy.resolve(Settings(sound = false), audible = true, silent = false))
        assertEquals(FeedbackPolicy(0.35f, false), FeedbackPolicy.resolve(Settings(soundVolume = 0.35f, vibration = false), audible = true, silent = false))
    }
}
