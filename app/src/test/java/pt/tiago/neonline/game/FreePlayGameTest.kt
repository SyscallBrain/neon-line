package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class FreePlayGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(config: FreePlayConfig, saved: SavedMatch? = null, checkpoint: (SavedMatch, LevelOutcome?) -> Unit) =
        GameViewModel(config.initial(), LevelObjective(LevelKind.DUEL), config.ai()!!,
            hintsAvailable = config.hints(), undoAvailable = config.undos(), animationEnabled = false,
            savedMatch = saved, searchDispatcher = dispatcher, hintProfile = config.hintAi()!!, onCheckpoint = { match, outcome, _ -> checkpoint(match, outcome) })

    @Test fun `AI first survives pause before its opening and resume after its opening without duplicate moves`() = runTest(dispatcher) {
        val config = FreePlayConfig(starter = Player.P2)
        var profile = Profile()
        val checkpoint: (SavedMatch, LevelOutcome?) -> Unit = { match, outcome -> profile = FreePlay.checkpoint(profile, config, match, outcome) }
        val first = model(config, checkpoint = checkpoint)
        first.pause()
        assertEquals(0, profile.savedFreePlay!!.match.steps.size)
        assertNotNull(FreePlay.restore(profile.savedFreePlay!!))
        val opening = model(config, profile.savedFreePlay!!.match, checkpoint); runCurrent()
        assertEquals(Player.P1, opening.ui.value.state.toMove)
        assertEquals(1, opening.ui.value.state.pieces.count { it != null })
        opening.pause()
        val hash = opening.snapshot().stateHash
        val resumed = model(config, profile.savedFreePlay!!.match, checkpoint); runCurrent()
        assertEquals(hash, resumed.snapshot().stateHash)
        assertEquals(1, resumed.snapshot().steps.size)
        assertTrue(profile.freePlayStats.isEmpty())
        resumed.pause()
    }
    @Test fun `opening hint is legal saved and does not consume a turn or result`() = runTest(dispatcher) {
        val config = FreePlayConfig()
        var profile = Profile()
        val game = model(config) { match, outcome -> profile = FreePlay.checkpoint(profile, config, match, outcome) }
        runCurrent()
        game.hint(); runCurrent()
        val column = game.ui.value.hintColumn
        assertNotNull(column)
        assertTrue(Move.Drop(column!!) in GameEngine.validMoves(config.initial()))
        assertEquals(config.hints() - 1, profile.savedFreePlay!!.match.hintsLeft)
        assertTrue(game.snapshot().steps.isEmpty())
        assertTrue(profile.freePlayStats.isEmpty())
        assertNotNull(FreePlay.restore(profile.savedFreePlay!!))
        game.pause()
    }
    @Test fun `complete game records once and retry preserves other modes and stats`() = runTest(dispatcher) {
        val config = FreePlayConfig()
        val baseline = Profile(savedMatch = SavedMatch("level-0002", Difficulty.HARD), localSession = SavedLocalSession(wins1 = 7))
        var profile = baseline
        val game = model(config) { match, outcome -> profile = FreePlay.checkpoint(profile, config, match, outcome) }
        runCurrent()
        var turns = 0
        while (!game.ui.value.finished && turns++ < 42) {
            val move = GameEngine.validMoves(game.ui.value.state).filterIsInstance<Move.Drop>().first()
            game.playColumn(move.col); runCurrent()
        }
        assertTrue(game.ui.value.finished)
        assertNull(profile.savedFreePlay)
        val stats = profile.freePlayStats.getValue(Difficulty.EASY)
        assertEquals(1L, stats.wins + stats.draws + stats.losses)
        game.pause(); game.resume(); runCurrent()
        assertEquals(stats, profile.freePlayStats.getValue(Difficulty.EASY))
        assertEquals(baseline.savedMatch, profile.savedMatch)
        assertEquals(baseline.localSession, profile.localSession)
        assertEquals(baseline.progressByDifficulty, profile.progressByDifficulty)
        assertTrue(profile.rivalRecords.isEmpty()); assertTrue(profile.achievements.isEmpty())
        game.restart(); runCurrent()
        assertFalse(game.ui.value.finished)
        assertEquals("free-play", game.ui.value.state.levelId)
        assertNotNull(profile.savedFreePlay)
        assertEquals(stats, profile.freePlayStats.getValue(Difficulty.EASY))
        game.pause()
    }
}
