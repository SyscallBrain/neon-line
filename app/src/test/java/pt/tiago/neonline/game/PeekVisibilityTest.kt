package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher

class PeekVisibilityTest {
    private fun state(): GameState = GameState.initial("fade", Difficulty.HARD, 2, "0123456789abcdef", 7, 6, 4,
        List(42) { true }, MutableList<Piece?>(42) { null }.also {
            it[35] = Piece(Player.P1, PieceKind.NORMAL, 0)
            it[36] = Piece(Player.P2, PieceKind.LOCK, 0)
        }, fadeAfter = 2, popOut = true).copy(ply = 4)
    @Test fun `peek exposes only the selected column and never changes the engine hash`() {
        val state = state(); val hash = StateHasher.hash(state)
        val hidden = BoardAccessibility.columns(state, PieceKind.NORMAL)
        assertTrue(hidden[0].cells.last().hidden); assertNull(hidden[0].cells.last().piece)
        val revealed = BoardAccessibility.columns(state, PieceKind.NORMAL, 0)
        assertFalse(revealed[0].cells.last().hidden)
        assertEquals(Player.P1, revealed[0].cells.last().piece!!.owner)
        assertTrue(revealed[1].cells.last().hidden); assertNull(revealed[1].cells.last().piece)
        assertTrue(revealed.all { it.winningPlayers.isEmpty() })
        assertEquals(hash, StateHasher.hash(state))
    }
    @Test fun `peek alpha is restored only for hidden stable pieces in its active column`() {
        val state = state(); val piece = state.pieces[35]!!
        val active = GameUiState(state, peekColumn = 0, peekedColumns = setOf(0))
        assertEquals(1f, PeekVisibility.alpha(active, piece, 0, 0f), 0f)
        assertEquals(0f, PeekVisibility.alpha(active, piece, 1, 0f), 0f)
        assertEquals(0f, PeekVisibility.alpha(active.copy(peekColumn = null), piece, 0, 0f), 0f)
        assertEquals(0f, PeekVisibility.alpha(active.copy(animating = true), piece, 0, 0f), 0f)
        assertEquals(0f, PeekVisibility.alpha(active.copy(paused = true), piece, 0, 0f), 0f)
    }
    @Test fun `unseen hidden owners cannot be inferred from pop affordances`() {
        val ui = GameUiState(state())
        assertFalse(BoardAccessibility.canPop(ui, 0))
        assertFalse(BoardAccessibility.canPop(ui, 1))
        assertFalse(BoardAccessibility.columns(ui.state, PieceKind.NORMAL)[0].legalPop)
        val remembered = ui.copy(peekedColumns = setOf(0))
        assertTrue(BoardAccessibility.canPop(remembered, 0))
        assertTrue(BoardAccessibility.columns(ui.state, PieceKind.NORMAL, null, setOf(0))[0].legalPop)
        assertNull(BoardAccessibility.columns(ui.state, PieceKind.NORMAL, null, setOf(0))[0].cells.last().piece)
    }
    @Test fun `peek requires hidden pieces and accepted human input`() {
        val ui = GameUiState(state())
        assertTrue(PeekVisibility.canPeek(ui, 0))
        assertFalse(PeekVisibility.canPeek(ui, 3))
        assertFalse(PeekVisibility.canPeek(ui, -1))
        assertFalse(PeekVisibility.canPeek(ui.copy(peekColumn = 0), 0))
        assertFalse(PeekVisibility.canPeek(ui.copy(paused = true), 0))
        assertFalse(PeekVisibility.canPeek(ui.copy(rivalThinking = true), 0))
        assertFalse(PeekVisibility.canPeek(ui.copy(state = ui.state.copy(toMove = Player.P2)), 0))
        assertFalse(PeekVisibility.canPeek(ui.copy(state = ui.state.copy(fadeAfter = null)), 0))
    }
}
