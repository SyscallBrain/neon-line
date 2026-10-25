package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto

class RotationPreviewsTest {
    private fun blank() = GameState.initial("rotation-preview", Difficulty.EASY,
        GameEngine.SOLID_FLOOR_ENGINE_VERSION, "0123456789abcdef", 7, 6, 4,
        List(42) { true }, List(42) { null }, rotateEvery = 6, popOut = true)
    private fun due(): GameUiState {
        var state = blank()
        for (col in listOf(0, 1, 2, 3, 0)) state = GameEngine.apply(state, Move.Drop(col)).state
        return GameUiState(state, localPlayers = true)
    }

    @Test fun `warning follows total plies and current board geometry across rotations`() {
        assertFalse(RotationPreviews.due(blank()))
        val ui = due()
        assertTrue(RotationPreviews.due(ui.state))
        val rotated = GameEngine.apply(ui.state, Move.Drop(4)).state
        assertEquals(6, rotated.width)
        assertEquals(7, rotated.height)
        assertFalse(RotationPreviews.due(rotated))
        assertTrue(RotationPreviews.due(rotated.copy(ply = 11)))
        assertFalse(RotationPreviews.due(ui.state.copy(rotateEvery = null)))
        assertFalse(RotationPreviews.due(ui.state.copy(status = Status.Draw("full"))))
    }

    @Test fun `drop preview equals engine result and leaves source hash streams and inventory intact`() {
        val ui = due()
        val before = StateHasher.hash(ui.state)
        val move = Move.Drop(4)
        val result = requireNotNull(RotationPreviews.project(ui, move))
        assertEquals(GameEngine.apply(ui.state, move), result)
        assertTrue(result.events.any { it is GameEvent.Rotated })
        assertEquals(1, result.state.orientation)
        // Physical locations from the current engine's rotation, independent of a second apply.
        assertEquals(Player.P1, result.state.pieceAt(6, 4)?.owner)
        assertEquals(listOf(Player.P2, Player.P2, Player.P1, Player.P2, Player.P1),
            (2..6).map { result.state.pieceAt(it, 5)?.owner })
        assertEquals(before, StateHasher.hash(ui.state))
        assertEquals(ui.state.auxStateHex, result.state.auxStateHex)
        assertEquals(ui.state.aiStateHex, result.state.aiStateHex)
        assertEquals(result, RotationPreviews.project(ui, move))
    }

    @Test fun `pop previews removal and rotated settlement with no timeout option`() {
        val ui = due()
        val pop = Move.Pop(1)
        assertTrue(pop in RotationPreviews.moves(ui))
        val result = requireNotNull(RotationPreviews.project(ui, pop))
        assertEquals(GameEngine.apply(ui.state, pop), result)
        assertEquals(4, result.state.pieces.count { it != null })
        assertFalse(Move.TimeUp in RotationPreviews.moves(ui))
        assertNull(RotationPreviews.project(ui, Move.TimeUp))
        assertNull(RotationPreviews.project(ui, Move.Pop(0)))
    }

    @Test fun `special previews use chosen inventory and actual explosion before rotation`() {
        val initial = due()
        val ui = initial.copy(state = initial.state.copy(inventory = mapOf(Player.P2 to mapOf(PieceKind.BOMB to 1))),
            selectedPiece = PieceKind.BOMB)
        val before = StateHasher.hash(ui.state)
        assertTrue(RotationPreviews.moves(ui).filterIsInstance<Move.Drop>().all { it.kind == PieceKind.BOMB })
        val result = requireNotNull(RotationPreviews.project(ui, Move.Drop(1, PieceKind.BOMB)))
        assertEquals(GameEngine.apply(ui.state, Move.Drop(1, PieceKind.BOMB)), result)
        assertEquals(before, StateHasher.hash(ui.state))
        assertEquals(1, ui.state.inventory[Player.P2]?.get(PieceKind.BOMB))
        assertNull(RotationPreviews.project(initial.copy(selectedPiece = PieceKind.BOMB), Move.Drop(1, PieceKind.BOMB)))
        assertNull(RotationPreviews.project(ui, Move.Drop(1)))
    }

    @Test fun `tutorial locks and invalid columns filter actions without bypassing board input rules`() {
        val local = due()
        val ui = local.copy(state = local.state.copy(toMove = Player.P1),
            tutorial = TutorialDto(listOf(TutorialStepDto("guide", forcedCol = 4))))
        assertEquals(listOf(Move.Drop(4)), RotationPreviews.moves(ui))
        assertNull(RotationPreviews.project(ui, Move.Drop(2)))
        assertNull(RotationPreviews.project(ui, Move.Pop(0)))
        assertNull(RotationPreviews.project(ui.copy(selectedPiece = PieceKind.BOMB), Move.Drop(4, PieceKind.BOMB)))
        val locked = local.copy(state = local.state.copy(lockedCol = 4, lockedFor = Player.P2))
        assertNull(RotationPreviews.project(locked, Move.Drop(4)))
        assertNull(RotationPreviews.project(local, Move.Drop(-1)))
        assertNull(RotationPreviews.project(local, Move.Drop(7)))
    }

    @Test fun `paused animation rival turns and finished levels cannot expose projections`() {
        val ui = due()
        for (blocked in listOf(ui.copy(paused = true), ui.copy(animating = true), ui.copy(rivalThinking = true),
            ui.copy(localPlayers = false), ui.copy(outcome = pt.tiago.neonline.core.objectives.LevelOutcome.Won(3)),
            ui.copy(state = ui.state.copy(status = Status.Won(Player.P1))))) {
            assertTrue(RotationPreviews.moves(blocked).isEmpty())
            assertNull(RotationPreviews.project(blocked, Move.Drop(4)))
        }
        assertTrue(RotationPreviews.moves(GameUiState(blank(), localPlayers = true)).isEmpty())
    }

    @Test fun `hidden pieces suppress projection even during peeks or after earlier observations`() {
        val original = due()
        val hidden = original.copy(state = original.state.copy(fadeAfter = 2))
        for (ui in listOf(hidden, hidden.copy(peekColumn = 0, peekedColumns = (0..6).toSet()))) {
            assertTrue(RotationPreviews.due(ui.state))
            assertTrue(RotationPreviews.moves(ui).isEmpty())
            assertNull(RotationPreviews.project(ui, Move.Drop(4)))
        }
        assertFalse(RotationPreviews.moves(original.copy(state = original.state.copy(fadeAfter = 20))).isEmpty())
    }

    @Test fun `a piece fading on the projected turn stays concealed in static frame and column reading`() {
        val base = due()
        val ui = base.copy(state = base.state.copy(fadeAfter = 6))
        assertFalse(RotationPreviews.hasHiddenPieces(ui.state))
        val after = requireNotNull(RotationPreviews.project(ui, Move.Drop(4))).state
        assertTrue(RotationPreviews.hasHiddenPieces(after))
        val cells = BoardAccessibility.columns(after, PieceKind.NORMAL).flatMap { it.cells }
        assertTrue(cells.any { it.hidden })
        assertTrue(cells.filter { it.hidden }.all { it.piece == null })
        val frame = AnimationTimeline.stillFrame(after, emptyList())
        assertTrue(frame.pieces.filter { PeekVisibility.isHidden(after, it.piece) }.all { it.alpha == 0f && !it.lit })
    }
}
