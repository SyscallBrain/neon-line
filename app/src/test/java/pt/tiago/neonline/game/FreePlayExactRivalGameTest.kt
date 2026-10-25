package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class FreePlayExactRivalGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `resumed real P2 endgame pauses safely and checkpoints one exact move`() = runTest(dispatcher) {
        for (version in 6..7) verifyExactEndgame(version)
    }

    private fun TestScope.verifyExactEndgame(version: Int) {
        val config = FreePlayConfig(version = version, difficulty = Difficulty.HARD)
        val start = config.initial()
        var state = start
        val random = java.util.Random(20261016L)
        val steps = mutableListOf<SavedPly>()
        repeat(37) {
            val moves = GameEngine.validMoves(state).filter { GameEngine.apply(state, it).state.status == Status.InProgress }
            val move = moves[random.nextInt(moves.size)]
            val next = GameEngine.apply(state, move).state
            steps += MatchReplay.step(state, move, next); state = next
        }
        assertEquals(Player.P2, state.toMove)
        val saved = SavedMatch("free-play", Difficulty.HARD, initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(state),
            steps = steps, hintsLeft = config.hints())
        var profile = Profile()
        val game = GameViewModel(start, LevelObjective(LevelKind.DUEL), config.ai()!!, hintsAvailable = config.hints(),
            animationEnabled = false, savedMatch = saved, searchDispatcher = dispatcher, exactEndgameRival = config.exactEndgameRival(),
            onCheckpoint = { match, outcome, _ -> profile = FreePlay.checkpoint(profile, config, match, outcome) })
        game.pause(); runCurrent()
        assertEquals(saved.steps, game.snapshot().steps)
        assertEquals(state, game.ui.value.state)
        assertNotNull(FreePlay.restore(profile.savedFreePlay!!))
        game.resume(); runCurrent()
        val journal = game.snapshot()
        assertEquals(38, journal.steps.size)
        val move = Move.Drop(journal.steps.last().column!!)
        assertTrue(move in PerfectSolver().solve(state)!!.bestMoves)
        val rng = state.aiRng(); rng.nextInt(1000)
        assertEquals(rng.stateHex(), journal.steps.last().aiStateHex)
        val trace = MatchReplay.trace(start, journal, config.hints(), 0)!!
        assertEquals(game.ui.value.state, trace.state)
        val hash = journal.stateHash
        game.pause(); game.resume(); runCurrent()
        assertEquals(38, game.snapshot().steps.size)
        assertEquals(hash, game.snapshot().stateHash)
        game.pause()
    }
}
