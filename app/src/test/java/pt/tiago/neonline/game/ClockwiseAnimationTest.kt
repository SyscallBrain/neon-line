package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class ClockwiseAnimationTest {
    private fun start(version: Int) = GameState.initial("clockwise-animation", Difficulty.EASY, version,
        "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null }, rotateEvery = 2)

    @Test fun `new clockwise animation agrees with rotation and settled physical board`() {
        for (version in 2..4) {
            val before = GameEngine.apply(start(version), Move.Drop(0)).state
            val result = GameEngine.apply(before, Move.Drop(6))
            val timeline = AnimationTimeline.build(before, result.state, result.events)
            val turn = timeline.phases.single { it.effect == AnimationTimeline.Effect.ROTATE }
            assertEquals(if (version == 4) 45f else -45f,
                AnimationTimeline.frameAt(timeline, turn.startMs + 225).rotationDegrees, .01f)
            val gravity = timeline.phases.last { it.effect == AnimationTimeline.Effect.FALL }
            assertEquals(6, gravity.board.width)
            assertEquals(7, gravity.board.height)
            val col = if (version == 4) 0 else 5
            assertEquals(Player.P1, gravity.board.pieces[(if (version == 4) 0 else 6) * 6 + col]?.owner)
            assertEquals(Player.P2, gravity.board.pieces[(if (version == 4) 6 else 0) * 6 + col]?.owner)
            assertEquals(result.state.pieces, AnimationTimeline.frameAt(timeline, timeline.totalMs).board.pieces)
        }
    }

    @Test fun `bomb settlement occurs before rotation and rotated falls keep real piece identities`() {
        val base = start(4).copy(ply = 1, inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        val upper = Piece(Player.P2, PieceKind.NORMAL, 0)
        val lower = Piece(Player.P1, PieceKind.NORMAL, 0)
        val before = base.copy(pieces = base.pieces.toMutableList().apply {
            this[21] = upper; this[28] = lower; this[35] = Piece(Player.P2, PieceKind.NORMAL, 0)
        })
        val result = GameEngine.apply(before, Move.Drop(1, PieceKind.BOMB))
        val timeline = AnimationTimeline.build(before, result.state, result.events)
        val bomb = timeline.phases.indexOfFirst { it.effect == AnimationTimeline.Effect.BOMB }
        val rotation = timeline.phases.indexOfFirst { it.effect == AnimationTimeline.Effect.ROTATE }
        assertTrue(bomb >= 0 && rotation > bomb)
        val beforeRotation = timeline.phases.subList(bomb + 1, rotation).filter { it.effect == AnimationTimeline.Effect.FALL }
        assertEquals(1, beforeRotation.size)
        assertEquals(upper, beforeRotation.single().motions.single().piece)
        val afterRotation = timeline.phases.drop(rotation + 1).filter { it.effect == AnimationTimeline.Effect.FALL }
        assertTrue(afterRotation.isNotEmpty())
        assertTrue(afterRotation.flatMap { it.motions }.all { it.piece == upper })
        assertEquals(upper, result.state.pieceAt(6, 0))
        assertEquals(result.state.pieces, AnimationTimeline.frameAt(timeline, timeline.totalMs).board.pieces)
    }

    @Test fun `pop falls settle in the old geometry before clockwise rotation`() {
        val base = start(4).copy(ply = 1, popOut = true)
        val survivor = Piece(Player.P2, PieceKind.NORMAL, 0)
        val before = base.copy(pieces = base.pieces.toMutableList().apply {
            this[28] = survivor; this[35] = Piece(Player.P1, PieceKind.NORMAL, 0)
        })
        val result = GameEngine.apply(before, Move.Pop(0))
        val timeline = AnimationTimeline.build(before, result.state, result.events)
        val rotateIndex = timeline.phases.indexOfFirst { it.effect == AnimationTimeline.Effect.ROTATE }
        val settle = timeline.phases.take(rotateIndex).last { it.effect == AnimationTimeline.Effect.FALL }
        assertEquals(7, settle.board.width)
        assertEquals(survivor, settle.motions.single().piece)
        assertEquals(5f, settle.motions.single().toRow, 0f)
        val rotate = timeline.phases[rotateIndex]
        assertEquals(survivor, rotate.board.pieces[35])
        val gravity = timeline.phases.drop(rotateIndex + 1).single { it.effect == AnimationTimeline.Effect.FALL }
        assertEquals(6, gravity.board.width)
        assertEquals(survivor, gravity.motions.single().piece)
        assertEquals(0f, gravity.motions.single().col, 0f)
        assertEquals(survivor, result.state.pieceAt(6, 0))
    }
}
