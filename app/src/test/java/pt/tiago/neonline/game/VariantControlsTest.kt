package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*

@OptIn(ExperimentalCoroutinesApi::class)
class VariantControlsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun start() = GameState.initial("controls", Difficulty.EASY, 1, "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })
    private fun model(state: GameState, objective: LevelObjective = LevelObjective(LevelKind.DUEL)) = GameViewModel(
        state, objective, AiProfile.EASY.copy(maxDepth = 1, nodeBudget = 100), hintsAvailable = 3, undoAvailable = 3,
        animationEnabled = false, searchDispatcher = dispatcher,
    )

    @Test fun `selecting a special consumes it only when the drop is played and undo restores it`() = runTest(dispatcher) {
        val state = start().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.WILD to 1)))
        val model = model(state)
        model.selectPiece(PieceKind.WILD)
        assertEquals(1, model.ui.value.state.inventory[Player.P1]!![PieceKind.WILD])
        model.playColumn(0); runCurrent()
        assertTrue(model.ui.value.state.pieces.any { it?.kind == PieceKind.WILD })
        assertEquals(0, model.ui.value.state.inventory[Player.P1]!![PieceKind.WILD])
        assertEquals(PieceKind.NORMAL, model.ui.value.selectedPiece)
        model.undo()
        assertEquals(state, model.ui.value.state)
        model.pause()
    }

    @Test fun `depleted inventory and invalid columns do not consume pieces or history`() = runTest(dispatcher) {
        val model = model(start())
        model.selectPiece(PieceKind.BOMB)
        assertEquals(PieceKind.NORMAL, model.ui.value.selectedPiece)
        model.playColumn(99)
        assertEquals(start(), model.ui.value.state)
        assertTrue(model.snapshot().steps.isEmpty())
        model.pause()
    }

    @Test fun `pop requires confirmation and cancelling leaves the board unchanged`() = runTest(dispatcher) {
        val state = start().copy(popOut = true, pieces = start().pieces.toMutableList().also {
            it[35] = Piece(Player.P1, PieceKind.NORMAL, 0); it[36] = Piece(Player.P2, PieceKind.NORMAL, 0)
        })
        val model = model(state)
        model.requestPop(0)
        assertEquals(0, model.ui.value.pendingPopColumn)
        assertEquals(state, model.ui.value.state)
        model.cancelPop(); model.confirmPop()
        assertEquals(state, model.ui.value.state)
        model.requestPop(1)
        assertNull(model.ui.value.pendingPopColumn)
        model.requestPop(0); model.confirmPop(); runCurrent()
        assertEquals("pop", model.snapshot().steps.first().action)
        model.undo()
        assertEquals(state, model.ui.value.state)
        model.pause()
    }

    @Test fun `locked and special bottom pieces are not offered for pop`() = runTest(dispatcher) {
        val base = start().copy(popOut = true, pieces = start().pieces.toMutableList().also { it[35] = Piece(Player.P1, PieceKind.NORMAL, 0) })
        val locked = base.copy(lockedCol = 0, lockedFor = Player.P1)
        assertTrue(BoardActions.popColumns(locked).isEmpty())
        val model = model(locked)
        model.requestPop(0); model.play(Move.Pop(0))
        assertEquals(locked, model.ui.value.state)
        assertTrue(model.snapshot().steps.isEmpty())
        assertTrue(BoardActions.popColumns(base.copy(pieces = base.pieces.toMutableList().also { it[35] = Piece(Player.P1, PieceKind.WILD, 0) })).isEmpty())
        model.pause()
    }

    @Test fun `preview comes from the selected special engine drop`() {
        val state = start().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        assertEquals(5, BoardActions.landingRow(state, 3, PieceKind.BOMB))
        assertNull(BoardActions.landingRow(state, 3, PieceKind.LOCK))
    }

    @Test fun `puzzle fails at its own move limit without another rival turn`() = runTest(dispatcher) {
        val model = model(start(), LevelObjective(LevelKind.PUZZLE, winInMoves = 1))
        model.playColumn(0); runCurrent()
        assertEquals(LevelOutcome.Lost(LossReasons.OUT_OF_MOVES), model.ui.value.outcome)
        assertEquals(1, model.ui.value.state.ply)
        assertEquals(1, model.ui.value.humanMoveCount)
        model.playColumn(1)
        assertEquals(1, model.ui.value.state.ply)
        model.pause()
    }

    @Test fun `puzzle hint finds a forced move within the remaining own turns`() = runTest(dispatcher) {
        val state = start().copy(pieces = start().pieces.toMutableList().also {
            it[37] = Piece(Player.P1, PieceKind.NORMAL, 0); it[38] = Piece(Player.P1, PieceKind.NORMAL, 0)
        })
        val model = model(state, LevelObjective(LevelKind.PUZZLE, winInMoves = 2))
        model.hint(); runCurrent()
        assertTrue(model.ui.value.hintMove in setOf(Move.Drop(1), Move.Drop(4)))
        assertEquals(2, model.ui.value.hintsLeft)
        model.pause()
    }
    @Test fun `unavailable puzzle hint is reported without consuming an allowance`() = runTest(dispatcher) {
        val model = model(start(), LevelObjective(LevelKind.PUZZLE, winInMoves = 1))
        model.hint(); runCurrent()
        assertTrue(model.ui.value.hintUnavailable)
        assertEquals(3, model.ui.value.hintsLeft)
        model.pause()
    }
}
