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
class FreePlayShapeGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `shaped games combine AI fade clock and resume for both geometries`() = runTest(dispatcher) {
        for ((width,height) in listOf(7 to 6, 9 to 7)) for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
            val config = FreePlayConfig(version = 4, difficulty = Difficulty.HARD,
                rules = FreePlayRules(width = width, height = height, shape = shape, fade = true, clockSeconds = 10))
            var profile = Profile()
            fun model(saved: SavedMatch? = null) = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
                animationEnabled = false, hintsAvailable = config.hints(), undoAvailable = config.undos(),
                savedMatch = saved, searchDispatcher = dispatcher, hintProfile = config.hintAi()!!, maxPlies = config.maxPlies(),
                onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
            val game = model(); runCurrent()
            game.playColumn(1); runCurrent()
            assertEquals(2, game.snapshot().steps.size)
            val placed = game.ui.value.state.pieces.filterNotNull().first { it.owner == Player.P1 }
            assertEquals(PieceKind.NORMAL, placed.kind)
            assertTrue(game.ui.value.state.inventory.values.all { inventory -> inventory.values.all { it == 0 } })
            assertTrue(game.ui.value.state.pieces.indices.all { game.ui.value.state.pieces[it] == null || game.ui.value.state.playable[it] })
            assertTrue(PeekVisibility.canPeek(game.ui.value, 1))
            game.peek(1); runCurrent(); advanceTimeBy(600); runCurrent()
            assertNull(game.ui.value.peekColumn); assertTrue(game.ui.value.clockMs!! < 10_000)
            assertEquals(1L, profile.freePlayPeeks[Difficulty.HARD])
            game.pause(); val saved = profile.savedFreePlay!!
            assertEquals(game.ui.value.state, FreePlay.restore(saved)!!.state)
            val resumed = model(saved.match); runCurrent()
            assertEquals(game.snapshot().stateHash, resumed.snapshot().stateHash)
            assertEquals(config.rules.playableMask(), resumed.ui.value.state.playable)
            assertEquals(1, resumed.ui.value.peekCount); assertNull(resumed.ui.value.peekColumn)
            assertTrue(profile.freePlayStats.isEmpty()); resumed.pause()
        }
    }
    @Test fun `shaped timeout and undo remain replayable without changing the floor`() = runTest(dispatcher) {
        val config = FreePlayConfig(version = 4, rules = FreePlayRules(shape = FreePlayBoardShape.PILLARS, clockSeconds = 10))
        var profile = Profile()
        val game = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
            animationEnabled = false, searchDispatcher = dispatcher, hintsAvailable = config.hints(), undoAvailable = config.undos(),
            onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals("timeout", game.snapshot().steps.first().action)
        assertEquals(config.rules.playableMask(), game.ui.value.state.playable)
        game.undo(); runCurrent()
        assertEquals(config.initial(), game.ui.value.state)
        assertEquals(2, game.ui.value.undosLeft)
        game.pause(); assertEquals(game.ui.value.state, FreePlay.restore(profile.savedFreePlay!!)!!.state)
    }
}
