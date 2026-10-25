package pt.tiago.neonline.game

import java.nio.file.Files
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
class SavedMatchTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val rival = AiProfile.EASY.copy(maxDepth = 2, nodeBudget = 100)
    private fun start() = GameState.initial(
        levelId = "level-0001", difficulty = Difficulty.EASY, engineVersion = 1,
        seedHex = "0123456789abcdef", width = 7, height = 6, connect = 4,
        playable = List(42) { true }, pieces = List(42) { null },
    )
    private fun model(start: GameState = start(), saved: SavedMatch? = null,
                      checkpoint: (SavedMatch, LevelOutcome?, Int) -> Unit = { _, _, _ -> }) = GameViewModel(
        start, LevelObjective(LevelKind.DUEL), rival, hintsAvailable = 3, undoAvailable = 3,
        animationEnabled = false, searchDispatcher = dispatcher, savedMatch = saved, onCheckpoint = checkpoint,
    )

    @Test fun `new process restores disk checkpoint and subsequent rival answers`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("neonline-resume").toFile()
        try {
            val store = ProfileStore(dir)
            val first = model(checkpoint = { saved, outcome, moves ->
                store.save(ProfileReducer.checkpoint(store.load(), 1, saved, outcome, moves, 3 - saved.hintsLeft))
            })
            first.play(Move.Drop(3)); runCurrent()
            first.play(Move.Drop(4)); runCurrent()
            first.pause()
            val expected = first.ui.value.state
            val disk = ProfileStore(dir).load().savedMatch!!
            val second = model(saved = disk)
            assertEquals(expected, second.ui.value.state)
            assertEquals(2, second.ui.value.humanMoveCount)
            first.resume()
            first.play(Move.Drop(2)); second.play(Move.Drop(2)); runCurrent()
            assertEquals(first.ui.value.state, second.ui.value.state)
            first.pause(); second.pause()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `resume between human move and AI answer preserves its random stream`() = runTest(dispatcher) {
        val first = model()
        first.play(Move.Drop(3))
        val pending = first.snapshot()
        assertEquals(1, pending.steps.size)
        val second = model(saved = pending)
        runCurrent()
        assertEquals(first.ui.value.state, second.ui.value.state)
        assertEquals(2, second.ui.value.state.ply)
        first.pause(); second.pause()
    }

    @Test fun `restored undo removes both moves and persists a truncated history`() = runTest(dispatcher) {
        val first = model()
        first.play(Move.Drop(3)); runCurrent()
        val second = model(saved = first.snapshot())
        second.undo()
        assertEquals(start(), second.ui.value.state)
        assertTrue(second.snapshot().steps.isEmpty())
        assertEquals(2, second.ui.value.undosLeft)
        val third = model(saved = second.snapshot())
        assertEquals(start(), third.ui.value.state)
        assertEquals(2, third.ui.value.undosLeft)
        first.pause(); second.pause(); third.pause()
    }

    @Test fun `hints and remaining clock survive closing and reopening`() = runTest(dispatcher) {
        val state = start().copy(moveClockSec = 10)
        val first = model(state)
        first.hint(); runCurrent()
        advanceTimeBy(1500); runCurrent()
        first.pause()
        val second = model(state, first.snapshot())
        assertEquals(2, second.ui.value.hintsLeft)
        assertEquals(first.ui.value.clockMs, second.ui.value.clockMs)
        first.pause(); second.pause()
    }

    @Test fun `timeout replays auxiliary stream and can be undone after reopening`() = runTest(dispatcher) {
        val state = start().copy(moveClockSec = 10)
        val first = model(state)
        first.timeUp(); runCurrent()
        val second = model(state, first.snapshot())
        assertEquals(first.ui.value.state, second.ui.value.state)
        second.undo()
        assertEquals(state, second.ui.value.state)
        first.pause(); second.pause()
    }

    @Test fun `damaged hashes moves budgets and mismatched content are refused`() = runTest(dispatcher) {
        val first = model()
        first.play(Move.Drop(3)); runCurrent()
        val saved = first.snapshot()
        assertNull(MatchReplay.restore(start(), saved.copy(stateHash = "broken"), 3, 3))
        assertNull(MatchReplay.restore(start(), saved.copy(hintsLeft = 4), 3, 3))
        assertNull(MatchReplay.restore(start(), saved.copy(undosLeft = -1), 3, 3))
        assertNull(MatchReplay.restore(start(), saved.copy(formatVersion = 2), 3, 3))
        assertNull(MatchReplay.restore(start().copy(engineVersion = 2), saved, 3, 3))
        val damaged = saved.steps.toMutableList()
        damaged[0] = damaged[0].copy(column = 99)
        assertNull(MatchReplay.restore(start(), saved.copy(steps = damaged), 3, 3))
        damaged[0] = saved.steps[0].copy(afterHash = "broken")
        assertNull(MatchReplay.restore(start(), saved.copy(steps = damaged), 3, 3))
        first.pause()
    }

    @Test fun `special pieces pops and rotation replay through the shared engine`() {
        val starts = listOf(
            start().copy(popOut = true),
            start().copy(rotateEvery = 2),
            start().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1))),
        )
        val sequences = listOf(
            listOf(Move.Drop(0), Move.Drop(1), Move.Pop(0)),
            listOf(Move.Drop(0), Move.Drop(1)),
            listOf(Move.Drop(0, PieceKind.WILD), Move.Drop(1)),
        )
        starts.zip(sequences).forEach { (start, moves) ->
            var state = start
            val steps = moves.map { move ->
                val after = GameEngine.apply(state, move).state
                MatchReplay.step(state, move, after).also { state = after }
            }
            val saved = SavedMatch(start.levelId, start.difficulty,
                initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(state),
                steps = steps, hintsLeft = 3, undosLeft = 3,
            )
            assertEquals(state, MatchReplay.restore(start, saved, 3, 3)!!.state)
        }
    }

    @Test fun `completion records result and clears saved match in one disk write`() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("neonline-finish").toFile()
        try {
            val store = ProfileStore(dir)
            val state = start().copy(pieces = start().pieces.toMutableList().also {
                (35..37).forEach { cell -> it[cell] = Piece(Player.P1, PieceKind.NORMAL, 0) }
            })
            val model = model(state, checkpoint = { saved, outcome, moves ->
                store.save(ProfileReducer.checkpoint(store.load(), 1, saved, outcome, moves, 3 - saved.hintsLeft))
            })
            assertNotNull(store.load().savedMatch)
            model.play(Move.Drop(3))
            val profile = ProfileStore(dir).load()
            assertNull(profile.savedMatch)
            assertEquals(1L, profile.stats(Difficulty.EASY).wins)
            model.pause()
            assertEquals(profile, store.load())
        } finally { dir.deleteRecursively() }
    }
}
