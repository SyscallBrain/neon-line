package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class LocalGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(config: LocalConfig = LocalConfig(), saved: SavedLocalSession? = null,
                      animate: Boolean = false, confirm: Boolean = false,
                      save: (SavedLocalSession) -> Unit = {}) = LocalGameViewModel(config, saved, animate, confirm, save)
    private fun win(game: LocalGameViewModel) { listOf(0, 6, 1, 6, 2, 5, 3).forEach { game.playColumn(it) } }

    @Test fun `both players can move without an AI response`() = runTest(dispatcher) {
        val game = model()
        game.playColumn(0)
        assertEquals(Player.P2, game.ui.value.state.toMove)
        assertTrue(game.ui.value.acceptsInput)
        game.playColumn(1)
        assertEquals(Player.P1, game.ui.value.state.toMove)
        assertEquals(2, game.ui.value.state.ply)
        assertFalse(game.ui.value.rivalThinking)
        assertNotNull(LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `win is counted once across resume and next round alternates starter`() = runTest(dispatcher) {
        val game = model(); win(game)
        assertEquals(Status.Won(Player.P1), game.ui.value.state.status)
        assertEquals(Triple(1, 0, 0), game.score.value)
        val restored = model(saved = game.snapshot())
        restored.playColumn(4); restored.resume()
        assertEquals(Triple(1, 0, 0), restored.score.value)
        restored.nextRound()
        assertEquals(Player.P2, restored.ui.value.state.toMove)
        assertEquals(Triple(1, 0, 0), restored.score.value)
        win(restored)
        assertEquals(Status.Won(Player.P2), restored.ui.value.state.status)
        assertEquals(Triple(1, 1, 0), restored.score.value)
        restored.nextRound()
        assertEquals(Player.P1, restored.ui.value.state.toMove)
        game.pause(); restored.pause()
    }
    @Test fun `restart resets current moves without changing score or starter`() = runTest(dispatcher) {
        val game = model(); win(game); game.nextRound(); game.playColumn(0); game.pause(); game.restart()
        assertEquals(0, game.ui.value.state.ply)
        assertEquals(Player.P2, game.ui.value.state.toMove)
        assertEquals(Triple(1, 0, 0), game.score.value)
        assertTrue(game.snapshot().steps.isEmpty())
        game.pause()
    }
    @Test fun `clock applies a deterministic timeout for player two and pauses correctly`() = runTest(dispatcher) {
        val game = model(LocalConfig(clockSeconds = 10))
        game.playColumn(0); runCurrent(); advanceTimeBy(1200); runCurrent()
        val remaining = game.ui.value.clockMs
        game.pause(); advanceTimeBy(20000); runCurrent()
        assertEquals(1, game.ui.value.state.ply)
        assertEquals(remaining, game.ui.value.clockMs)
        game.resume(); advanceTimeBy(remaining!!); runCurrent()
        assertEquals(2, game.ui.value.state.ply)
        assertEquals("timeout", game.snapshot().steps.last().action)
        assertEquals(Player.P1, game.ui.value.state.toMove)
        assertNotNull(LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `animations gate both players and do not consume clock`() = runTest(dispatcher) {
        val game = model(LocalConfig(clockSeconds = 10), animate = true)
        game.playColumn(0); val id = game.ui.value.animation!!.id
        game.playColumn(1); advanceTimeBy(15000); runCurrent()
        assertEquals(1, game.ui.value.state.ply)
        assertEquals(10000L, game.ui.value.clockMs)
        game.completeAnimation(id + 1); assertTrue(game.ui.value.animating)
        game.pause(); game.completeAnimation(id); assertTrue(game.ui.value.animating)
        game.resume(); game.completeAnimation(id); assertTrue(game.ui.value.acceptsInput)
        game.playColumn(1); assertEquals(2, game.ui.value.state.ply)
        game.pause()
    }
    @Test fun `confirmation is required for both players and pending choices are not journalled`() = runTest(dispatcher) {
        val game = model(confirm = true)
        game.playColumn(0); assertEquals(0, game.snapshot().steps.size)
        game.playColumn(0); assertEquals(1, game.snapshot().steps.size)
        game.playColumn(1); assertEquals(1, game.snapshot().steps.size)
        game.playColumn(1); assertEquals(2, game.snapshot().steps.size)
        game.playColumn(2); game.pause(); game.resume(); game.playColumn(2)
        assertEquals(2, game.snapshot().steps.size)
        game.pause()
    }
    @Test fun `special inventories belong to current player and replay unchanged`() = runTest(dispatcher) {
        val game = model(LocalConfig(specials = true))
        game.selectPiece(PieceKind.LOCK); game.playColumn(0)
        game.selectPiece(PieceKind.BOMB); game.playColumn(2)
        val state = game.ui.value.state
        assertEquals(0, state.inventory[Player.P1]!![PieceKind.LOCK])
        assertEquals(1, state.inventory[Player.P2]!![PieceKind.LOCK])
        assertEquals(0, state.inventory[Player.P2]!![PieceKind.BOMB])
        assertEquals(state, LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `player two can pop own bottom piece after confirmation`() = runTest(dispatcher) {
        val game = model(LocalConfig(popOut = true))
        game.playColumn(0); game.playColumn(1); game.playColumn(2)
        game.requestPop(1); assertEquals(3, game.ui.value.state.ply)
        game.confirmPop(); assertEquals(4, game.ui.value.state.ply)
        assertEquals("pop", game.snapshot().steps.last().action)
        assertEquals(2, game.ui.value.state.pieces.count { it != null })
        assertEquals(game.ui.value.state, LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `rotation and five in a row use engine dimensions and inventory`() = runTest(dispatcher) {
        val game = model(LocalConfig(connect = 5, rotation = true, specials = true, popOut = true))
        listOf(0, 6, 1, 5, 2, 4).forEach { game.playColumn(it) }
        assertEquals(6, game.ui.value.state.width)
        assertEquals(7, game.ui.value.state.height)
        assertEquals(1, game.ui.value.state.orientation)
        assertEquals(game.ui.value.state, LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `pop repetition draws once and survives restoration`() = runTest(dispatcher) {
        val game = model(LocalConfig(popOut = true))
        repeat(3) { game.playColumn(0); game.playColumn(1); game.play(Move.Pop(0)); game.play(Move.Pop(1)) }
        assertTrue(game.ui.value.state.status is Status.Draw)
        assertEquals(Triple(0, 0, 1), game.score.value)
        val restored = model(game.config, game.snapshot())
        assertEquals(Triple(0, 0, 1), restored.score.value)
        restored.nextRound(); assertEquals(Player.P2, restored.ui.value.state.toMove)
        game.pause(); restored.pause()
    }
    @Test fun `large board connects five without rotation and without campaign award`() = runTest(dispatcher) {
        val game = model(LocalConfig(width = 9, height = 7, connect = 5))
        listOf(0, 8, 1, 8, 2, 8, 3, 8, 4).forEach { game.playColumn(it) }
        assertEquals(Status.Won(Player.P1), game.ui.value.state.status)
        assertEquals(Triple(1, 0, 0), game.score.value)
        assertNull(game.ui.value.outcome)
        assertEquals(game.ui.value.state, LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
    @Test fun `local diagnostics redact names and retain replayable journal`() = runTest(dispatcher) {
        val game = model(LocalConfig(player1 = "PrivateNameOne", player2 = "PrivateNameTwo"))
        game.playColumn(1)
        val environment = DiagnosticEnvironment("0.12.0", 19, 36, "16", "Test", "Phone")
        val bytes = ProfileDiagnostics.encode(Profile(localSession = game.snapshot()), null, false, environment, null)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("PrivateNameOne")); assertFalse(text.contains("PrivateNameTwo"))
        val report = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val saved = kotlinx.serialization.json.Json.decodeFromJsonElement(SavedLocalSession.serializer(), report.getValue("localSession"))
        assertEquals(game.ui.value.state, LocalSessionReplay.restore(saved))
        game.pause()
    }
    @Test fun `clock restoration retains remaining time for either player`() = runTest(dispatcher) {
        val game = model(LocalConfig(clockSeconds = 10)); game.playColumn(3)
        runCurrent(); advanceTimeBy(2000); runCurrent(); game.pause()
        val saved = game.snapshot(); val restored = model(game.config, saved)
        assertEquals(Player.P2, restored.ui.value.state.toMove)
        assertEquals(8000L, restored.ui.value.clockMs)
        assertEquals(game.ui.value.state, restored.ui.value.state)
        restored.pause()
    }
    @Test fun `invalid moves and paused input never update journal or score`() = runTest(dispatcher) {
        val game = model()
        game.playColumn(-1); game.playColumn(99); game.play(Move.Pop(0)); game.play(Move.TimeUp)
        assertEquals(0, game.ui.value.state.ply)
        game.pause(); game.playColumn(1); game.nextRound()
        assertEquals(0, game.ui.value.state.ply)
        assertEquals(Triple(0, 0, 0), game.score.value)
    }
    @Test fun `persistence failure is visible and retried by pause`() = runTest(dispatcher) {
        var fail = false
        val game = model(save = { if (fail) error("Disk failure") })
        fail = true; game.playColumn(1)
        assertTrue(game.saveFailed.value)
        fail = false; game.pause()
        assertFalse(game.saveFailed.value)
        assertEquals(1, game.snapshot().steps.size)
    }
    @Test fun `modified hash illegal action and incompatible engine are refused`() = runTest(dispatcher) {
        val game = model(); game.playColumn(2)
        val saved = game.snapshot()
        assertNull(LocalSessionReplay.restore(saved.copy(stateHash = "wrong")))
        assertNull(LocalSessionReplay.restore(saved.copy(engineVersion = 1)))
        assertNull(LocalSessionReplay.restore(saved.copy(steps = saved.steps.map { it.copy(column = 99) })))
        assertNull(LocalSessionReplay.restore(saved.copy(steps = saved.steps.map { it.copy(aiStateHex = "wrong") })))
        assertNull(LocalSessionReplay.restore(saved.copy(clockMs = 10)))
        game.pause()
    }
    @Test fun `expired saved clock immediately advances instead of accepting another human move`() = runTest(dispatcher) {
        val game = model(LocalConfig(clockSeconds = 10)); game.playColumn(1); game.pause()
        val restored = model(game.config, game.snapshot().copy(clockMs = 0L))
        assertEquals(2, restored.ui.value.state.ply)
        assertEquals("timeout", restored.snapshot().steps.last().action)
        assertNotNull(LocalSessionReplay.restore(restored.snapshot()))
        restored.pause()
    }
    @Test fun `old profiles default to no local session`() {
        assertNull(kotlinx.serialization.json.Json.decodeFromString(Profile.serializer(), "{}").localSession)
        assertFalse(LocalConfig(width = 12).isValid())
        assertFalse(LocalConfig(width = 9, height = 7, rotation = true).isValid())
        assertFalse(LocalConfig(clockSeconds = 3).isValid())
        assertFalse(LocalConfig(player1 = "x".repeat(33)).isValid())
    }
    @Test fun `profile persistence and backup keep both independent sessions and scores`() = runTest(dispatcher) {
        val game = model(LocalConfig(player1 = "Ana", player2 = "Tiago")); win(game); game.nextRound(); game.playColumn(4)
        val dir = Files.createTempDirectory("local-duel-test").toFile()
        try {
            val campaign = SavedMatch("level-0002", Difficulty.HARD)
            val profile = ProfileReducer.recordWin(Profile(savedMatch = campaign), 1, Difficulty.HARD, 3, 4, 0).copy(localSession = game.snapshot())
            val store = ProfileStore(dir); store.save(profile)
            val restored = ProfileBackup.decode(ProfileBackup.encode(ProfileStore(dir).load()))
            assertEquals(campaign, restored.savedMatch)
            assertEquals(profile.progressByDifficulty, restored.progressByDifficulty)
            assertEquals(game.snapshot(), restored.localSession)
            val local = model(saved = restored.localSession, config = game.config)
            assertEquals(game.ui.value.state, local.ui.value.state)
            assertEquals(game.score.value, local.score.value)
            assertEquals(game.snapshot(), store.resetProgress().localSession)
            local.pause()
        } finally { dir.deleteRecursively(); game.pause() }
    }
    @Test fun `terminal clock and score replay remain valid after reload`() = runTest(dispatcher) {
        val game = model(LocalConfig(clockSeconds = 20)); win(game)
        assertNull(game.snapshot().clockMs)
        assertNotNull(LocalSessionReplay.restore(game.snapshot()))
        assertNull(LocalSessionReplay.restore(game.snapshot().copy(wins1 = 0)))
        game.pause()
    }
}
