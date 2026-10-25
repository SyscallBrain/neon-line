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
class FreePlayRulesGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `special inventory and rotated timed game resume after real AI turns`() = runTest(dispatcher) {
        val config = FreePlayConfig(version = 2, rules = FreePlayRules(connect = 5, rotation = true, specials = true, popOut = true, clockSeconds = 10))
        var profile = Profile()
        fun model(saved: SavedMatch? = null) = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
            hintsAvailable = config.hints(), undoAvailable = config.undos(), animationEnabled = false,
            savedMatch = saved, searchDispatcher = dispatcher, hintProfile = config.hintAi()!!, maxPlies = config.maxPlies(),
            onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
        val game = model(); runCurrent()
        game.selectPiece(PieceKind.LOCK); game.playColumn(0); runCurrent()
        assertEquals(0, game.ui.value.state.inventory.getValue(Player.P1).getValue(PieceKind.LOCK))
        repeat(2) { game.playColumn(2); runCurrent() }
        assertEquals(6, game.snapshot().steps.size)
        assertEquals(6, game.ui.value.state.width)
        assertEquals(7, game.ui.value.state.height)
        game.pause()
        val saved = profile.savedFreePlay!!
        val replay = FreePlay.restore(saved)!!
        assertEquals(game.ui.value.state, replay.state)
        val resumed = model(saved.match); runCurrent()
        assertEquals(game.snapshot().stateHash, resumed.snapshot().stateHash)
        assertEquals(saved.match.clockMs, resumed.ui.value.clockMs)
        assertTrue(profile.freePlayStats.isEmpty())
        resumed.pause()
    }
    @Test fun `move clock timeout is saved as a real replayable human move`() = runTest(dispatcher) {
        val config = FreePlayConfig(version = 2, rules = FreePlayRules(clockSeconds = 10))
        var profile = Profile()
        val game = GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
            animationEnabled = false, searchDispatcher = dispatcher, maxPlies = config.maxPlies(),
            hintsAvailable = config.hints(), undoAvailable = config.undos(),
            onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals("timeout", game.snapshot().steps.first().action)
        assertEquals(2, game.snapshot().steps.size)
        game.pause()
        assertEquals(game.ui.value.state, FreePlay.restore(profile.savedFreePlay!!)!!.state)
        assertTrue(profile.freePlayStats.isEmpty())
    }
}
