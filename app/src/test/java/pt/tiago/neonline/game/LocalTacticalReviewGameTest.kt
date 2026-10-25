package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalTacticalReviewGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `finished local review never mutates score journal or checkpoints and next round alternates`() = runTest(dispatcher) {
        var checkpoints = 0
        val game = LocalGameViewModel(LocalConfig(player1 = "Ana", player2 = "Bia"), animationEnabled = false,
            onCheckpoint = { checkpoints++ })
        assertNull(game.reviewInput(Player.P1))
        for (column in listOf(0, 6, 1, 6, 2, 5, 4, 6, 4, 6)) game.playColumn(column)
        assertTrue(game.ui.value.finished)
        val journal = game.snapshot()
        val score = game.score.value
        val count = checkpoints
        for (player in Player.entries) {
            val input = game.reviewInput(player)!!
            assertEquals(player, input.player)
            assertEquals(journal, input.saved)
            assertEquals("Ana", input.saved.config.player1)
            assertEquals("Bia", input.saved.config.player2)
            TacticalReviewAnalyzer.analyzeLocal(input)
        }
        assertEquals(journal, game.snapshot())
        assertEquals(score, game.score.value)
        assertEquals(count, checkpoints)
        game.pause(); game.resume()
        assertTrue(game.ui.value.finished)
        assertEquals(score, game.score.value)
        game.nextRound()
        assertEquals(Player.P2, game.snapshot().starter)
        assertEquals(score, game.score.value)
        assertNull(game.reviewInput(Player.P2))
        game.pause()
    }

    @Test fun `review remains unavailable until the terminal animation is acknowledged`() = runTest(dispatcher) {
        val game = LocalGameViewModel(LocalConfig(), animationEnabled = true)
        val columns = listOf(0, 6, 1, 6, 2, 5, 3)
        columns.forEachIndexed { index, column ->
            game.playColumn(column)
            if (index < columns.lastIndex) game.completeAnimation(game.ui.value.animation!!.id)
        }
        assertTrue(game.ui.value.finished)
        assertTrue(game.ui.value.animating)
        assertNull(game.reviewInput(Player.P1))
        val id = game.ui.value.animation!!.id
        game.completeAnimation(id - 1)
        assertNull(game.reviewInput(Player.P2))
        game.completeAnimation(id)
        assertNotNull(game.reviewInput(Player.P1))
        assertNotNull(game.reviewInput(Player.P2))
        game.pause()
    }

    @Test fun `restored terminal round is reviewable without counting the win again`() = runTest(dispatcher) {
        val game = LocalGameViewModel(LocalConfig(), animationEnabled = false)
        for (column in listOf(0, 6, 1, 6, 2, 5, 3)) game.playColumn(column)
        val saved = game.snapshot()
        val restored = LocalGameViewModel(saved.config, saved, animationEnabled = false)
        assertEquals(saved, restored.snapshot())
        TacticalReviewAnalyzer.analyzeLocal(restored.reviewInput(Player.P1)!!)
        assertEquals(saved, restored.snapshot())
        assertEquals(Triple(1, 0, 0), restored.score.value)
        restored.pause(); game.pause()
    }
}
