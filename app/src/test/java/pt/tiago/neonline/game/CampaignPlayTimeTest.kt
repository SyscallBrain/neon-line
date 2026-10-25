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
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class CampaignPlayTimeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun blank() = GameState.initial("level-0001", Difficulty.EASY, GameEngine.ENGINE_VERSION,
        "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })

    @Test fun `pauses and fresh process gaps do not count while undo keeps thinking time`() = runTest(dispatcher) {
        var now = 0L
        val game = GameViewModel(blank(), LevelObjective(LevelKind.DUEL), AiProfile.EASY,
            undoAvailable = 3, animationEnabled = false, searchDispatcher = dispatcher, elapsedRealtimeMs = { now })
        now = 1000; game.pause(); val saved = game.snapshot()
        assertNotNull(MatchReplay.restore(blank(), saved, 0, 3))
        assertNull(MatchReplay.restore(blank(), saved.copy(elapsedMs = -1L), 0, 3))
        now = 90000; game.pause()
        assertEquals(1000L, game.snapshot().elapsedMs)
        val restored = GameViewModel(blank(), LevelObjective(LevelKind.DUEL), AiProfile.EASY,
            undoAvailable = 3, animationEnabled = false, searchDispatcher = dispatcher,
            savedMatch = saved, elapsedRealtimeMs = { now })
        now += 400; restored.playColumn(0); runCurrent()
        restored.undo()
        assertEquals(1400L, restored.snapshot().elapsedMs)
        assertTrue(restored.snapshot().steps.isEmpty())
        restored.pause(); now += 3000; restored.resume(); restored.resume(); now += 200
        assertEquals(1600L, restored.snapshot().elapsedMs)
        restored.restart(); assertEquals(0L, restored.snapshot().elapsedMs)
        now += 50; assertEquals(50L, restored.snapshot().elapsedMs)
        restored.pause()
    }

    @Test fun `puzzle move limit freezes time before result animation and repeated resume`() = runTest(dispatcher) {
        var now = 0L
        var terminal: SavedMatch? = null
        val game = GameViewModel(blank(), LevelObjective(LevelKind.PUZZLE, winInMoves = 1), AiProfile.EASY,
            animationEnabled = true, searchDispatcher = dispatcher, elapsedRealtimeMs = { now },
            onCheckpoint = { saved, outcome, _ -> if (outcome != null) terminal = saved })
        now = 1234; game.playColumn(0)
        assertNotNull(game.ui.value.outcome)
        assertEquals(1234L, terminal!!.elapsedMs)
        now = 5000; game.pause(); game.resume()
        game.completeAnimation(game.ui.value.animation!!.id)
        assertEquals(1234L, game.snapshot().elapsedMs)
    }

    @Test fun `boss duration excludes round dialogs survives resume and counts across duels`() = runTest(dispatcher) {
        var now = 0L
        val start = blank().copy(pieces = blank().pieces.toMutableList().apply {
            for (column in 0..2) this[5 * 7 + column] = Piece(Player.P1, PieceKind.NORMAL, 0)
        })
        val starts = List(3) { start }
        fun model(saved: SavedMatch? = null) = GameViewModel(start, LevelObjective(LevelKind.BOSS), AiProfile.EASY,
            animationEnabled = false, bossRoundStates = starts, savedMatch = saved,
            searchDispatcher = dispatcher, elapsedRealtimeMs = { now })
        val game = model()
        now = 500; game.playColumn(3)
        assertEquals(1, game.ui.value.bossSeries!!.wins)
        now = 10000; game.resume()
        assertEquals(500L, game.snapshot().elapsedMs)
        val journal = game.snapshot(); game.pause()
        val restored = model(journal)
        now = 20000
        assertEquals(500L, restored.snapshot().elapsedMs)
        restored.nextBossRound(); now += 700; restored.playColumn(3)
        assertTrue(restored.ui.value.outcome is LevelOutcome.Won)
        assertEquals(1200L, restored.snapshot().elapsedMs)
        now += 10000; restored.resume()
        assertEquals(1200L, restored.snapshot().elapsedMs)
    }
}
