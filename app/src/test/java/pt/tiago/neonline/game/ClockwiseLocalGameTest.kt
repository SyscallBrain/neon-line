package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClockwiseLocalGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `legacy and modern rounds retain their engine through moves resume restart and next round`() = runTest(dispatcher) {
        for (version in 1..2) {
            val config = LocalConfig(version = version, rotation = true)
            val game = LocalGameViewModel(config, animationEnabled = false)
            for (col in listOf(0, 1, 2, 3, 0)) {
                game.playColumn(col)
                assertEquals(game.ui.value.state, LocalSessionReplay.restore(game.snapshot()))
            }
            val preview = requireNotNull(RotationPreviews.project(game.ui.value, Move.Drop(4)))
            game.playColumn(4)
            assertEquals(preview.state, game.ui.value.state)
            val resumed = LocalGameViewModel(config, game.snapshot(), animationEnabled = false)
            assertEquals(game.ui.value.state, resumed.ui.value.state)
            resumed.restart()
            assertEquals(if (version == 2) 4 else 2, resumed.initialState().engineVersion)
            assertEquals(0, resumed.ui.value.state.ply)
            assertEquals(resumed.ui.value.state, LocalSessionReplay.restore(resumed.snapshot()))
            game.pause(); resumed.pause()

            val straight = LocalGameViewModel(LocalConfig(version = version), animationEnabled = false)
            listOf(0, 6, 1, 6, 2, 5, 3).forEach(straight::playColumn)
            assertEquals(Status.Won(Player.P1), straight.ui.value.state.status)
            straight.nextRound()
            assertEquals(Player.P2, straight.ui.value.state.toMove)
            assertEquals(if (version == 2) 4 else 2, straight.ui.value.state.engineVersion)
            assertEquals(Triple(1, 0, 0), straight.score.value)
            assertEquals(straight.ui.value.state, LocalSessionReplay.restore(straight.snapshot()))
            straight.pause()
        }
    }

    @Test fun `new engine timeout rotation persists and resumes the real auxiliary stream`() = runTest(dispatcher) {
        val config = LocalConfig(version = 2, rotation = true, clockSeconds = 10)
        val game = LocalGameViewModel(config, animationEnabled = false)
        listOf(0, 1, 2, 3, 0).forEach(game::playColumn)
        val before = game.ui.value.state
        advanceTimeBy(10_000); runCurrent()
        val after = game.ui.value.state
        assertEquals(6, after.ply)
        assertEquals(4, after.engineVersion)
        assertEquals(1, after.orientation)
        assertNotEquals(before.auxStateHex, after.auxStateHex)
        assertEquals("timeout", game.snapshot().steps.last().action)
        assertEquals(after, LocalSessionReplay.restore(game.snapshot()))
        game.pause()
    }
}
