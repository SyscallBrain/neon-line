package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.TutorialStepDto
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

class RivalThreatCuesTest {
    private fun blank(width: Int = 7, height: Int = 6, connect: Int = 4) = GameState.initial(
        "threat-cues", Difficulty.EASY, GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION,
        "0123456789abcdef", width, height, connect, List(width * height) { true }, List(width * height) { null })
    private fun threat(): GameUiState {
        var state = blank()
        for (col in listOf(6, 0, 6, 1, 5, 2)) state = GameEngine.apply(state, Move.Drop(col)).state
        return GameUiState(state)
    }

    @Test fun `normal rival win maps to its legal landing cell and a human block removes it`() {
        val ui = threat()
        val cues = RivalThreatCues.snapshot(ui)
        assertEquals(listOf(Move.Drop(3)), cues.map { it.move })
        assertEquals(38, cues.single().cell)
        val blocked = GameEngine.apply(ui.state, Move.Drop(3)).state.copy(toMove = Player.P1)
        assertTrue(RivalThreatCues.snapshot(GameUiState(blocked)).isEmpty())
        assertTrue(RivalThreatCues.snapshot(GameUiState(blank())).isEmpty())
    }

    @Test fun `special drops are listed only with inventory and only when the rival really wins`() {
        val base = threat()
        val ui = base.copy(state = base.state.copy(inventory = mapOf(Player.P2 to mapOf(PieceKind.WILD to 1, PieceKind.BOMB to 1, PieceKind.LOCK to 1))))
        val moves = RivalThreatCues.snapshot(ui).map { it.move }
        assertTrue(Move.Drop(3, PieceKind.NORMAL) in moves)
        assertTrue(Move.Drop(3, PieceKind.WILD) in moves)
        assertTrue(Move.Drop(3, PieceKind.LOCK) in moves)
        assertFalse(Move.Drop(3, PieceKind.BOMB) in moves)
        assertEquals(listOf(Move.Drop(3)), RivalThreatCues.snapshot(base).map { it.move })
    }

    @Test fun `pop and bomb wins identify their original action cells`() {
        val base = blank()
        val pieces = base.pieces.toMutableList().apply {
            for (col in 0..2) this[35 + col] = Piece(Player.P2, PieceKind.NORMAL, 0)
            this[38] = Piece(Player.P1, PieceKind.NORMAL, 0)
            this[31] = Piece(Player.P2, PieceKind.NORMAL, 0)
            this[24] = Piece(Player.P2, PieceKind.NORMAL, 0)
        }
        // Popping column3 creates a P1 horizontal line: it must never count as a rival win.
        val losing = base.copy(popOut = true, pieces = base.pieces.toMutableList().apply {
            for (col in 0..2) this[35 + col] = Piece(Player.P1, PieceKind.NORMAL, 0)
            this[38] = Piece(Player.P2, PieceKind.NORMAL, 0)
            this[31] = Piece(Player.P1, PieceKind.NORMAL, 0)
        })
        assertFalse(RivalThreatCues.snapshot(GameUiState(losing)).any { it.move == Move.Pop(3) })
        // Removing a P2 bottom piece in column3 drops P2 into a horizontal line on row4.
        val popState = base.copy(popOut = true, pieces = base.pieces.toMutableList().apply {
            for (col in 0..2) {
                this[35 + col] = Piece(Player.P1, PieceKind.NORMAL, 0)
                this[28 + col] = Piece(Player.P2, PieceKind.NORMAL, 0)
            }
            this[38] = Piece(Player.P2, PieceKind.NORMAL, 0)
            this[31] = Piece(Player.P1, PieceKind.NORMAL, 0)
            this[24] = Piece(Player.P2, PieceKind.NORMAL, 0)
        })
        val pop = RivalThreatCues.snapshot(GameUiState(popState)).single { it.move == Move.Pop(3) }
        assertEquals(38, pop.cell)
        // Bomb in column4 clears the blocker at(5,3), allowing P2 above it to settle into line.
        val bombState = base.copy(pieces = pieces, inventory = mapOf(Player.P2 to mapOf(PieceKind.BOMB to 1)))
        val bomb = RivalThreatCues.snapshot(GameUiState(bombState)).single { it.move == Move.Drop(4, PieceKind.BOMB) }
        assertEquals(39, bomb.cell)
    }

    @Test fun `full blocked and rival locked columns cannot manufacture a winning drop`() {
        val ui = threat()
        val locked = ui.copy(state = ui.state.copy(lockedCol = 3, lockedFor = Player.P2))
        assertTrue(RivalThreatCues.snapshot(locked).isEmpty())
        val blocked = ui.copy(state = ui.state.copy(playable = ui.state.playable.toMutableList().apply { this[38] = false }))
        assertFalse(RivalThreatCues.snapshot(blocked).any { it.move == Move.Drop(3) })
        val full = ui.copy(state = ui.state.copy(pieces = ui.state.pieces.toMutableList().apply {
            for (row in 0..5) this[row * 7 + 3] = Piece(if (row % 2 == 0) Player.P2 else Player.P1, PieceKind.NORMAL, 0)
        }))
        assertFalse(RivalThreatCues.snapshot(full).any { it.move == Move.Drop(3) })
    }

    @Test fun `shape objectives do not constrain a rivals winning line and large boards use connect five`() {
        val ui = threat()
        assertTrue(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(shapeRule = ShapeRule.VERTICAL_ONLY))).any { it.move == Move.Drop(3) })
        val large = blank(9, 7, 5)
        val state = large.copy(pieces = large.pieces.toMutableList().apply {
            for (col in 0..3) this[54 + col] = Piece(Player.P2, PieceKind.NORMAL, 0)
        })
        val cue = RivalThreatCues.snapshot(GameUiState(state)).single()
        assertEquals(Move.Drop(4), cue.move)
        assertEquals(58, cue.cell)
    }

    @Test fun `difficulty mode and input gates never offer free help during unavailable turns`() {
        val ui = threat()
        assertTrue(RivalThreatCues.snapshot(ui, enabled = false).isEmpty())
        for (blocked in listOf(ui.copy(localPlayers = true), ui.copy(paused = true), ui.copy(animating = true),
            ui.copy(rivalThinking = true), ui.copy(state = ui.state.copy(toMove = Player.P2)),
            ui.copy(state = ui.state.copy(difficulty = Difficulty.MEDIUM)), ui.copy(state = ui.state.copy(difficulty = Difficulty.HARD)),
            ui.copy(outcome = pt.tiago.neonline.core.objectives.LevelOutcome.Lost("limit")),
            ui.copy(state = ui.state.copy(status = Status.Won(Player.P1))),
            ui.copy(tutorial = TutorialDto(listOf(TutorialStepDto("guide", forcedCol = 2)))))) {
            assertTrue(RivalThreatCues.snapshot(blocked).isEmpty())
        }
    }

    @Test fun `hidden pieces suppress every cue even when peeked and observations never restore help`() {
        val ui = threat()
        val hidden = ui.copy(state = ui.state.copy(fadeAfter = 2))
        assertTrue(RivalThreatCues.snapshot(hidden).isEmpty())
        assertTrue(RivalThreatCues.snapshot(hidden.copy(peekColumn = 0, peekedColumns = (0..6).toSet())).isEmpty())
        assertFalse(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(fadeAfter = 20))).isEmpty())
    }

    @Test fun `rotation timing suppresses probes if either upcoming turn rotates`() {
        val ui = threat()
        for (ply in listOf(4, 5, 10, 11)) assertTrue(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(rotateEvery = 6, ply = ply))).isEmpty())
        for (ply in listOf(0, 1, 2, 3, 6)) assertFalse(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(rotateEvery = 6, ply = ply))).isEmpty())
        assertTrue(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(rotateEvery = 1))).isEmpty())
        assertTrue(RivalThreatCues.snapshot(ui.copy(state = ui.state.copy(rotateEvery = 2))).isEmpty())
    }

    @Test fun `all listed actions are engine wins and inspecting preserves the entire source snapshot`() {
        val ui = threat()
        val before = StateHasher.hash(ui.state)
        val cues = RivalThreatCues.snapshot(ui)
        val probe = ui.state.copy(toMove = Player.P2)
        assertEquals(cues, RivalThreatCues.snapshot(ui))
        assertEquals(cues.size, cues.map { it.move }.distinct().size)
        for (cue in cues) {
            assertTrue(cue.move in GameEngine.validMoves(probe))
            assertEquals(Status.Won(Player.P2), GameEngine.apply(probe, cue.move).state.status)
            assertTrue(cue.cell in ui.state.pieces.indices)
        }
        assertEquals(before, StateHasher.hash(ui.state))
        assertEquals(Player.P1, ui.state.toMove)
    }
}
