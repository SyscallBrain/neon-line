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
class FreePlayMoveLimitTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `human and AI can reach the move cap and persist exactly one draw`() = runTest(dispatcher) {
        for (starter in Player.entries) {
            val config = FreePlayConfig(version = 2, starter = starter)
            var profile = Profile()
            val game = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
                hintsAvailable = config.hints(), undoAvailable = config.undos(), animationEnabled = false,
                searchDispatcher = dispatcher, maxPlies = 1,
                onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
            if (starter == Player.P1) game.playColumn(0)
            runCurrent()
            assertEquals(LevelOutcome.Lost("draw"), game.ui.value.outcome)
            assertEquals(1, game.snapshot().steps.size)
            assertEquals(RivalRecord(draws = 1), profile.freePlayStats.getValue(Difficulty.EASY))
            assertNull(profile.savedFreePlay)
            assertTrue(FreePlay.isValid(profile))
            game.pause(); game.resume(); runCurrent()
            assertEquals(1, game.snapshot().steps.size)
            assertEquals(1L, profile.freePlayStats.getValue(Difficulty.EASY).draws)
        }
    }
    @Test fun `a real winning move takes precedence over the move cap`() = runTest(dispatcher) {
        val config = FreePlayConfig(version = 2)
        val start = config.initial().let { state -> state.copy(pieces = state.pieces.toMutableList().also { pieces ->
            (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
        }) }
        val game = GameViewModel(start, LevelObjective(LevelKind.DUEL), config.ai()!!,
            animationEnabled = false, searchDispatcher = dispatcher, maxPlies = 1)
        game.playColumn(3); runCurrent()
        assertTrue(game.ui.value.outcome is LevelOutcome.Won)
        game.pause()
    }
}
