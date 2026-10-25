package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.SavedMatch

@OptIn(ExperimentalCoroutinesApi::class)
class TutorialAnimationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun start() = GameState.initial("t", Difficulty.EASY, 1, "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })
    private val tutorial = TutorialDto(listOf(TutorialStepDto("tut_01_step_1", listOf(3), 3)))
    private fun model(state: GameState = start(), tutorial: TutorialDto? = null, animation: Boolean = true, saved: SavedMatch? = null) = GameViewModel(
        state, LevelObjective(LevelKind.DUEL), AiProfile.EASY.copy(maxDepth = 2, nodeBudget = 100),
        hintsAvailable = 3, undoAvailable = 3, animationEnabled = animation, tutorial = tutorial,
        searchDispatcher = dispatcher, savedMatch = saved,
    )

    @Test fun `tutorial rejects other columns without consuming a move and can be skipped`() = runTest(dispatcher) {
        val model = model(tutorial = tutorial)
        assertEquals(3, model.ui.value.tutorialStep!!.forcedCol)
        model.play(Move.Drop(2))
        assertEquals(start(), model.ui.value.state)
        model.hint(); runCurrent()
        assertEquals(3, model.ui.value.hintsLeft)
        model.dismissTutorial()
        model.play(Move.Drop(2))
        assertEquals(1, model.ui.value.humanMoveCount)
        model.pause()
    }

    @Test fun `tutorial dismissal survives resume and restart offers it again`() = runTest(dispatcher) {
        val first = model(tutorial = tutorial)
        first.dismissTutorial()
        val restored = model(tutorial = tutorial, saved = first.snapshot())
        assertNull(restored.ui.value.tutorialStep)
        restored.restart()
        assertNotNull(restored.ui.value.tutorialStep)
        first.pause(); restored.pause()
    }

    @Test fun `AI waits for human animation and further input is blocked`() = runTest(dispatcher) {
        val model = model()
        model.play(Move.Drop(3))
        val id = model.ui.value.animation!!.id
        model.play(Move.Drop(2)); model.undo(); model.hint(); model.timeUp()
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, model.ui.value.state.ply)
        assertEquals(3, model.ui.value.undosLeft)
        assertEquals(3, model.ui.value.hintsLeft)
        assertFalse(model.ui.value.acceptsInput)
        model.completeAnimation(id)
        advanceTimeBy(450); runCurrent()
        assertEquals(2, model.ui.value.state.ply)
        assertTrue(model.ui.value.animating)
        model.completeAnimation(id) // stale callback for the previous move
        assertTrue(model.ui.value.animating)
        model.completeAnimation(model.ui.value.animation!!.id)
        assertTrue(model.ui.value.acceptsInput)
        model.pause()
    }

    @Test fun `pause freezes animation boundary and clock waits until landing`() = runTest(dispatcher) {
        val state = start().copy(moveClockSec = 10)
        val model = model(state)
        model.play(Move.Drop(3))
        val firstId = model.ui.value.animation!!.id
        model.pause()
        model.completeAnimation(firstId)
        assertTrue(model.ui.value.animating)
        advanceTimeBy(1000); runCurrent()
        assertEquals(10000L, model.ui.value.clockMs)
        model.resume(); model.completeAnimation(firstId)
        advanceTimeBy(450); runCurrent()
        val rivalId = model.ui.value.animation!!.id
        advanceTimeBy(1000); runCurrent()
        assertEquals(10000L, model.ui.value.clockMs)
        model.completeAnimation(rivalId)
        advanceTimeBy(500); runCurrent()
        assertEquals(9500L, model.ui.value.clockMs)
        model.pause()
    }

    @Test fun `reopening during animation restores logical state and continues the turn`() = runTest(dispatcher) {
        val first = model(tutorial = tutorial)
        first.play(Move.Drop(3))
        first.pause()
        val restored = model(tutorial = tutorial, saved = first.snapshot())
        assertEquals(first.ui.value.state, restored.ui.value.state)
        assertFalse(restored.ui.value.animating)
        assertNull(restored.ui.value.tutorialStep)
        advanceTimeBy(450); runCurrent()
        assertEquals(2, restored.ui.value.state.ply)
        first.pause(); restored.pause()
    }

    @Test fun `winning outcome is committed before animation and can finish after pause`() = runTest(dispatcher) {
        val state = start().copy(pieces = start().pieces.toMutableList().also { pieces ->
            (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
        })
        val model = model(state)
        model.play(Move.Drop(3))
        assertNotNull(model.ui.value.outcome)
        assertTrue(model.ui.value.animating)
        model.pause(); model.resume()
        assertFalse(model.ui.value.paused)
        model.completeAnimation(model.ui.value.animation!!.id)
        assertFalse(model.ui.value.animating)
        assertFalse(model.ui.value.acceptsInput)
    }

    @Test fun `reduced motion advances without waiting for a renderer callback`() = runTest(dispatcher) {
        val model = model(animation = false)
        model.play(Move.Drop(3)); runCurrent()
        assertEquals(2, model.ui.value.state.ply)
        assertNull(model.ui.value.animation)
        assertTrue(model.ui.value.acceptsInput)
        model.pause()
    }
}
