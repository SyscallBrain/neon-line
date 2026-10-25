package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto

class BoardAccessibilityTest {
    private fun blank() = GameState.initial("accessible", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef", 7, 6, 4,
        List(42) { true }, List(42) { null })
    @Test fun `empty board has one column per engine column and correct row capacity`() {
        val columns = BoardAccessibility.columns(blank(), PieceKind.NORMAL)
        assertEquals(7, columns.size)
        assertTrue(columns.all { it.occupied == 0 && it.free == 6 && it.landingRow == 5 && it.legalDrop && !it.legalPop })
        assertTrue(columns.all { it.cells.map { cell -> cell.row } == (0..5).toList() })
    }
    @Test fun `blocked cells and pieces contribute separately to capacity`() {
        val start = blank()
        val state = start.copy(playable = start.playable.toMutableList().apply { this[35] = false },
            pieces = start.pieces.toMutableList().apply { this[28] = Piece(Player.P1, PieceKind.NORMAL, 0) })
        val column = BoardAccessibility.columns(state, PieceKind.NORMAL)[0]
        assertEquals(1, column.occupied); assertEquals(4, column.free); assertEquals(3, column.landingRow)
        assertTrue(column.cells.last().blocked)
    }
    @Test fun `locked column follows current player rather than piece owner`() {
        val state = blank().copy(lockedCol = 3, lockedFor = Player.P1)
        val column = BoardAccessibility.columns(state, PieceKind.NORMAL)[3]
        assertTrue(column.locked); assertFalse(column.legalDrop); assertNull(column.landingRow)
        assertFalse(BoardAccessibility.canDrop(GameUiState(state), 3))
        assertTrue(BoardAccessibility.columns(state.copy(toMove = Player.P2), PieceKind.NORMAL)[3].legalDrop)
    }
    @Test fun `full column exposes read data and only pop can remain legal`() {
        val start = blank().copy(popOut = true)
        val state = start.copy(pieces = start.pieces.toMutableList().apply { for (row in 0..5) this[row * 7] = Piece(Player.P1, PieceKind.NORMAL, row) })
        val column = BoardAccessibility.columns(state, PieceKind.NORMAL)[0]
        assertEquals(6, column.occupied); assertEquals(0, column.free)
        assertFalse(column.legalDrop); assertTrue(column.legalPop)
        assertTrue(BoardAccessibility.canPop(GameUiState(state), 0))
    }
    @Test fun `special selection respects the engine inventory`() {
        assertFalse(BoardAccessibility.columns(blank(), PieceKind.BOMB)[0].legalDrop)
        val state = blank().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        assertTrue(BoardAccessibility.columns(state, PieceKind.BOMB)[0].legalDrop)
        assertTrue(BoardAccessibility.canDrop(GameUiState(state, selectedPiece = PieceKind.BOMB), 0))
    }
    @Test fun `gates reject paused animating finished rival turn and invalid columns`() {
        val ui = GameUiState(blank())
        for (blocked in listOf(ui.copy(paused = true), ui.copy(animating = true), ui.copy(rivalThinking = true),
            ui.copy(state = ui.state.copy(status = Status.Draw("full"))), ui.copy(state = ui.state.copy(toMove = Player.P2)))) {
            assertFalse(BoardAccessibility.canDrop(blocked, 0)); assertFalse(BoardAccessibility.canPop(blocked, 0))
        }
        assertFalse(BoardAccessibility.canDrop(ui, -1)); assertFalse(BoardAccessibility.canDrop(ui, 7))
        assertTrue(BoardAccessibility.canDrop(ui, 0))
    }
    @Test fun `tutorial allows only its required normal drop and prevents pops`() {
        val ui = GameUiState(blank().copy(popOut = true), tutorial = TutorialDto(listOf(TutorialStepDto("guide", forcedCol = 3))))
        assertTrue(BoardAccessibility.canDrop(ui, 3))
        assertFalse(BoardAccessibility.canDrop(ui, 2))
        assertFalse(BoardAccessibility.canDrop(ui.copy(selectedPiece = PieceKind.WILD), 3))
        assertFalse(BoardAccessibility.canPop(ui, 3))
    }
    @Test fun `both players can activate columns in local mode`() {
        val ui = GameUiState(blank().copy(toMove = Player.P2), localPlayers = true)
        assertTrue(BoardAccessibility.canDrop(ui, 1))
        assertFalse(BoardAccessibility.canDrop(ui.copy(localPlayers = false), 1))
    }
    @Test fun `winning normal drop is checked by engine without changing original state`() {
        var state = blank()
        for (column in listOf(0, 6, 1, 6, 2, 5)) state = GameEngine.apply(state, Move.Drop(column)).state
        val hash = StateHasher.hash(state)
        assertTrue(Player.P1 in BoardAccessibility.columns(state, PieceKind.NORMAL)[3].winningPlayers)
        assertEquals(hash, StateHasher.hash(state))
        assertEquals(Status.Won(Player.P1), GameEngine.apply(state, Move.Drop(3)).state.status)
    }
    @Test fun `hidden pieces conceal ownership and kind and suppress threat information`() {
        var state = blank()
        for (column in listOf(0, 6, 1, 6, 2, 5)) state = GameEngine.apply(state, Move.Drop(column)).state
        state = state.copy(fadeAfter = 2)
        val columns = BoardAccessibility.columns(state, PieceKind.NORMAL)
        assertTrue(columns.flatMap { it.cells }.any { it.hidden })
        assertTrue(columns.flatMap { it.cells }.filter { it.hidden }.all { it.piece == null })
        assertTrue(columns.all { it.winningPlayers.isEmpty() })
        assertEquals(6, columns.sumOf { it.occupied })
    }
    @Test fun `rotation uses current dimensions and reads cells top to bottom`() {
        var state = blank().copy(rotateEvery = 6)
        for (column in listOf(0, 6, 1, 5, 2, 4)) state = GameEngine.apply(state, Move.Drop(column)).state
        val columns = BoardAccessibility.columns(state, PieceKind.NORMAL)
        assertEquals(6, columns.size)
        assertTrue(columns.all { it.cells.size == 7 && it.cells.first().row == 0 && it.cells.last().row == 6 })
        assertEquals(state.pieces.count { it != null }, columns.sumOf { it.occupied })
    }
    @Test fun `ended match cannot expose an actionable drop or pop`() {
        val state = blank().copy(popOut = true, status = Status.Won(Player.P1))
        assertTrue(BoardAccessibility.columns(state, PieceKind.NORMAL).all { !it.legalDrop && !it.legalPop && it.winningPlayers.isEmpty() })
    }
}
