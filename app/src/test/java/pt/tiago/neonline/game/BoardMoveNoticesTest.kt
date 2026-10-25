package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.data.LocalConfig

class BoardMoveNoticesTest {
    private fun state(vararg columns: Int, config: LocalConfig = LocalConfig(), starter: Player = Player.P1): GameState {
        var state = config.initial(starter)
        columns.forEach { state = GameEngine.apply(state, Move.Drop(it)).state }
        return state
    }
    private fun ui(before: GameState, move: Move, local: Boolean = false): GameUiState {
        val result = GameEngine.apply(before, move)
        assertTrue(result.events.none { it is GameEvent.MoveRejected })
        return GameUiState(result.state, events = result.events, localPlayers = local)
    }

    @Test fun `P2 move announces actual owner and exact immediate normal drop threats`() {
        val ui = ui(state(6, 0, 6, 1, 5), Move.Drop(2))
        val notice = BoardMoveNotices.snapshot(ui)!!
        assertEquals(Player.P2, notice.player)
        assertEquals(2, notice.column)
        assertEquals(MoveNoticeAction.DROP, notice.action)
        assertEquals(PieceKind.NORMAL, notice.kind)
        assertEquals(listOf(3), notice.threatColumns)
        assertNull(notice.win)
        assertFalse(notice.rotated)
        assertEquals(notice, BoardMoveNotices.snapshot(ui.copy(clockMs = 999, selectedPiece = PieceKind.BOMB)))
    }

    @Test fun `P1 threat only announced for local players and P2 starter stays P2`() {
        val ui = ui(state(0, 6, 1, 6), Move.Drop(2))
        assertEquals(emptyList<Int>(), BoardMoveNotices.snapshot(ui)!!.threatColumns)
        assertEquals(listOf(3), BoardMoveNotices.snapshot(ui.copy(localPlayers = true))!!.threatColumns)
        val second = BoardMoveNotices.snapshot(ui(state(starter = Player.P2), Move.Drop(4), true))!!
        assertEquals(Player.P2, second.player)
        assertEquals(4, second.column)
    }

    @Test fun `pop special and timeout describe actual actions and timeout column`() {
        val pop = BoardMoveNotices.snapshot(ui(state(6, 0, 5, config = LocalConfig(popOut = true)), Move.Pop(0), true))!!
        assertEquals(Player.P2, pop.player)
        assertEquals(MoveNoticeAction.POP, pop.action)
        assertEquals(0, pop.column)
        val bomb = BoardMoveNotices.snapshot(ui(state(config = LocalConfig(specials = true), starter = Player.P2), Move.Drop(4, PieceKind.BOMB), true))!!
        assertEquals(Player.P2, bomb.player)
        assertEquals(PieceKind.BOMB, bomb.kind)
        val timed = ui(state(config = LocalConfig(clockSeconds = 10), starter = Player.P2), Move.TimeUp, true)
        val timeout = BoardMoveNotices.snapshot(timed)!!
        assertEquals(MoveNoticeAction.TIMEOUT, timeout.action)
        assertEquals(Player.P2, timeout.player)
        assertEquals(timed.events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col, timeout.column)
    }

    @Test fun `rotation retains original move column and reports rotation`() {
        val ui = ui(state(0, 6, 1, 6, 2, config = LocalConfig(rotation = true)), Move.Drop(6), true)
        val notice = BoardMoveNotices.snapshot(ui)!!
        assertEquals(6, notice.column)
        assertEquals(6, ui.state.width)
        assertEquals(Player.P2, notice.player)
        assertTrue(notice.rotated)
    }

    @Test fun `horizontal and vertical winning line directions come from actual winner events`() {
        val horizontal = BoardMoveNotices.snapshot(ui(state(0, 6, 1, 6, 2, 5), Move.Drop(3)))!!
        assertEquals(Player.P1, horizontal.win!!.player)
        assertEquals(setOf(MoveNoticeDirection.HORIZONTAL), horizontal.win!!.directions)
        assertEquals(emptyList<Int>(), horizontal.threatColumns)
        val vertical = BoardMoveNotices.snapshot(ui(state(0, 6, 0, 6, 0, 5), Move.Drop(0)))!!
        assertEquals(setOf(MoveNoticeDirection.VERTICAL), vertical.win!!.directions)
    }

    @Test fun `diagonal wins and shape rejected lines do not produce invented winners`() {
        val before = state(0, 1, 1, 2, 6, 2, 2, 3, 6, 3, 5, 3)
        val diagonal = BoardMoveNotices.snapshot(ui(before, Move.Drop(3)))!!
        assertEquals(setOf(MoveNoticeDirection.DIAGONAL), diagonal.win!!.directions)
        val rejected = ui(state(0, 6, 1, 6, 2, 5).copy(shapeRule = ShapeRule.VERTICAL_ONLY), Move.Drop(3))
        assertEquals(Status.InProgress, rejected.state.status)
        assertNull(BoardMoveNotices.snapshot(rejected)!!.win)
    }

    @Test fun `pop can award line to opponent without changing the reported mover`() {
        val base = state(config = LocalConfig(popOut = true))
        val pieces = base.pieces.toMutableList()
        pieces[base.index(5, 0)] = Piece(Player.P1, PieceKind.NORMAL, 0)
        pieces[base.index(4, 0)] = Piece(Player.P2, PieceKind.NORMAL, 0)
        for (column in 1..3) pieces[base.index(5, column)] = Piece(Player.P2, PieceKind.NORMAL, 0)
        val current = ui(base.copy(pieces = pieces), Move.Pop(0), true)
        assertEquals(Status.Won(Player.P2), current.state.status)
        val notice = BoardMoveNotices.snapshot(current)!!
        assertEquals(Player.P1, notice.player)
        assertEquals(Player.P2, notice.win!!.player)
        assertEquals(setOf(MoveNoticeDirection.HORIZONTAL), notice.win!!.directions)
    }

    @Test fun `no settled move notice while paused animating restored undone or rejected`() {
        val current = ui(state(), Move.Drop(0))
        assertNull(BoardMoveNotices.snapshot(current.copy(paused = true)))
        assertNull(BoardMoveNotices.snapshot(current.copy(animating = true)))
        assertNull(BoardMoveNotices.snapshot(current.copy(events = emptyList())))
        assertNull(BoardMoveNotices.snapshot(current.copy(events = current.events + GameEvent.MoveRejected("bad"))))
        val threat = ui(state(6, 0, 6, 1, 5), Move.Drop(2)).copy(outcome = LevelOutcome.Lost("out_of_moves"))
        assertEquals(emptyList<Int>(), BoardMoveNotices.snapshot(threat)!!.threatColumns)
    }

    @Test fun `hidden pieces suppress derived threats even with a column peeked`() {
        val visible = ui(state(6, 0, 6, 1, 5), Move.Drop(2))
        val hidden = visible.copy(state = visible.state.copy(fadeAfter = 3), peekColumn = 0, peekedColumns = setOf(0))
        assertEquals(listOf(3), BoardMoveNotices.snapshot(visible)!!.threatColumns)
        assertEquals(emptyList<Int>(), BoardMoveNotices.snapshot(hidden)!!.threatColumns)
        val won = ui(state(0, 6, 1, 6, 2, 5), Move.Drop(3))
        assertNull(BoardMoveNotices.snapshot(won.copy(state = won.state.copy(fadeAfter = 3)))!!.win)
    }
}
