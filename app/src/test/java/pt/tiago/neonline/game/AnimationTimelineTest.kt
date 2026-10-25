package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class AnimationTimelineTest {
    private fun start() = GameState.initial("t", Difficulty.EASY, 1, "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })
    private fun timeline(state: GameState, move: Move): AnimationTimeline.Timeline {
        val result = GameEngine.apply(state, move)
        return AnimationTimeline.build(state, result.state, result.events)
    }

    @Test fun `fall duration uses distance from above board to landing row`() {
        assertEquals(70L, AnimationTimeline.fallMs(1))
        assertEquals(98L, AnimationTimeline.fallMs(2))
        val timeline = timeline(start(), Move.Drop(3))
        assertEquals(AnimationTimeline.fallMs(6) + AnimationTimeline.BOUNCE_MS, timeline.totalMs)
    }

    @Test fun `drop shows one travelling piece without a duplicate at destination`() {
        val timeline = timeline(start(), Move.Drop(3))
        val initial = AnimationTimeline.frameAt(timeline, 0)
        assertEquals(1, initial.pieces.size)
        assertEquals(-1f, initial.pieces.single().row, 0f)
        val mid = AnimationTimeline.frameAt(timeline, 80)
        assertTrue(mid.pieces.single().row in -1f..5f)
        assertFalse(mid.finished)
        val end = AnimationTimeline.frameAt(timeline, timeline.totalMs)
        assertEquals(5f, end.pieces.single().row, 0f)
        assertTrue(end.finished)
    }

    @Test fun `piece bounces upwards then returns to landing row`() {
        val timeline = timeline(start(), Move.Drop(0))
        val impact = AnimationTimeline.fallMs(6)
        assertEquals(5f, AnimationTimeline.frameAt(timeline, impact).pieces.single().row, 0.001f)
        assertEquals(4.86f, AnimationTimeline.frameAt(timeline, impact + 45).pieces.single().row, 0.001f)
        assertEquals(5f, AnimationTimeline.frameAt(timeline, timeline.totalMs).pieces.single().row, 0f)
    }

    @Test fun `winning line lights one cell at a time before dimming other pieces`() {
        val pieces = start().pieces.toMutableList()
        (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
        pieces[41] = Piece(Player.P2, PieceKind.NORMAL, 0)
        val timeline = timeline(start().copy(pieces = pieces), Move.Drop(3))
        val lines = timeline.phases.filter { it.effect == AnimationTimeline.Effect.LINE }
        assertEquals(listOf(1, 2, 3, 4), lines.map { it.litCells.size })
        assertEquals(1, AnimationTimeline.frameAt(timeline, lines.first().startMs).litCells.size)
        val end = AnimationTimeline.frameAt(timeline, timeline.totalMs)
        assertEquals(4, end.litCells.size)
        assertEquals(0.35f, end.pieces.single { it.piece.owner == Player.P2 }.alpha, 0.001f)
        assertTrue(end.pieces.filter { it.lit }.all { it.alpha == 1f })
    }

    @Test fun `pop and shifted pieces retain their real owners and kinds`() {
        val p1 = Piece(Player.P1, PieceKind.NORMAL, 0)
        val p2 = Piece(Player.P2, PieceKind.WILD, 1)
        val state = start().copy(popOut = true, pieces = start().pieces.toMutableList().also { it[35] = p1; it[28] = p2 })
        val timeline = timeline(state, Move.Pop(0))
        assertEquals(p1, timeline.phases.first().motions.single().piece)
        val fall = timeline.phases.single { it.effect == AnimationTimeline.Effect.FALL }
        assertEquals(p2, fall.motions.single().piece)
        val end = AnimationTimeline.frameAt(timeline, timeline.totalMs)
        assertEquals(p2, end.pieces.single().piece)
        assertEquals(5f, end.pieces.single().row, 0f)
    }

    @Test fun `rotation precedes gravity and ends at authoritative rectangular board`() {
        var state = start().copy(rotateEvery = 2)
        state = GameEngine.apply(state, Move.Drop(0)).state
        val timeline = timeline(state, Move.Drop(6))
        val rotation = timeline.phases.single { it.effect == AnimationTimeline.Effect.ROTATE }
        assertEquals(-45f, AnimationTimeline.frameAt(timeline, rotation.startMs + 225).rotationDegrees, 0.01f)
        val gravity = timeline.phases.last { it.effect == AnimationTimeline.Effect.FALL }
        assertTrue(gravity.startMs >= rotation.startMs + rotation.durationMs)
        val midGravity = AnimationTimeline.frameAt(timeline, gravity.startMs + 10)
        assertEquals(setOf(Player.P1, Player.P2), midGravity.pieces.map { it.piece.owner }.toSet())
        assertTrue(gravity.motions.any { it.piece.owner == Player.P2 })
        val end = AnimationTimeline.frameAt(timeline, timeline.totalMs)
        assertEquals(6, end.board.width)
        assertEquals(7, end.board.height)
        assertEquals(timeline.after.pieces, end.board.pieces)
        assertEquals(0f, end.rotationDegrees, 0f)
    }

    @Test fun `bomb fades cleared pieces after its fall and displays expanding ring`() {
        val state = start().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)),
            pieces = start().pieces.toMutableList().also { it[35] = Piece(Player.P2, PieceKind.NORMAL, 0) })
        val timeline = timeline(state, Move.Drop(1, PieceKind.BOMB))
        val bomb = timeline.phases.single { it.effect == AnimationTimeline.Effect.BOMB }
        val mid = AnimationTimeline.frameAt(timeline, bomb.startMs + 160)
        assertNotNull(mid.explosionCell)
        assertEquals(0.5f, mid.pieces.first { it.piece.owner == Player.P2 }.alpha, 0.01f)
        assertEquals(timeline.after.pieces.count { it != null }, AnimationTimeline.frameAt(timeline, timeline.totalMs).pieces.size)
    }

    @Test fun `neon fade interpolates newly hidden pieces and stays hidden afterwards`() {
        val state = start().copy(fadeAfter = 2, ply = 1, pieces = start().pieces.toMutableList().also { it[35] = Piece(Player.P1, PieceKind.NORMAL, 0) }, toMove = Player.P2)
        val timeline = timeline(state, Move.Drop(6))
        val fade = timeline.phases.single { it.effect == AnimationTimeline.Effect.FADE }
        val mid = AnimationTimeline.frameAt(timeline, fade.startMs + 300)
        assertEquals(0.5f, mid.pieces.first { it.piece.owner == Player.P1 }.alpha, 0.01f)
        assertEquals(0f, AnimationTimeline.frameAt(timeline, timeline.totalMs).pieces.first { it.piece.owner == Player.P1 }.alpha, 0f)
    }

    @Test fun `rejected move has no animation and leaves the board intact`() {
        val timeline = timeline(start(), Move.Drop(99))
        assertEquals(0L, timeline.totalMs)
        assertTrue(AnimationTimeline.frameAt(timeline, 0).finished)
        assertEquals(start().pieces, timeline.finalBoard.pieces)
    }
}
