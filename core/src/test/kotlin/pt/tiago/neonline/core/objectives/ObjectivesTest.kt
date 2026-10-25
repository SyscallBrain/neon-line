package pt.tiago.neonline.core.objectives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.ShapeRule
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine

class ShapeObjectiveTest {

    @Test
    fun `a non-conforming line does not end a shape level`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            shapeRule = ShapeRule.DIAGONAL_ONLY,
        )
        // P1 completes a horizontal four: the level asks for a diagonal, so play continues.
        val r = GameEngine.apply(s, Move.Drop(3))
        assertNull(r.outcome)
        assertEquals(Player.P2, r.state.toMove)
    }

    @Test
    fun `a conforming line ends a shape level`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            "..122..",
            ".1222..",
            "11222..",
            shapeRule = ShapeRule.DIAGONAL_ONLY,
        )
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P1, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `vertical only levels ignore horizontal lines`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            shapeRule = ShapeRule.VERTICAL_ONLY,
        )
        assertNull(GameEngine.apply(s, Move.Drop(3)).outcome)
        val vertical = Boards.state(
            ".......",
            ".......",
            "..1....",
            "..1....",
            "..1....",
            ".......",
            shapeRule = ShapeRule.VERTICAL_ONLY,
        )
        val r = GameEngine.apply(vertical, Move.Drop(2))
        assertTrue(r.outcome is MatchOutcome.Win)
    }

    @Test
    fun `through target levels need a target cell in the line`() {
        val withTarget = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            targets = setOf(38), // row 5, col 3
            shapeRule = ShapeRule.THROUGH_TARGET,
        )
        val r = GameEngine.apply(withTarget, Move.Drop(3))
        assertTrue(r.outcome is MatchOutcome.Win)

        val withoutTarget = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            targets = setOf(0), // a different cell
            shapeRule = ShapeRule.THROUGH_TARGET,
        )
        assertNull(GameEngine.apply(withoutTarget, Move.Drop(3)).outcome)
    }

    @Test
    fun `the rival still wins with any line on a shape level`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            firstToMove = Player.P2,
            shapeRule = ShapeRule.DIAGONAL_ONLY,
        )
        // P2 makes a horizontal four: the shape rule only binds the objective holder.
        val r = GameEngine.apply(s, Move.Drop(3))
        val outcome = r.outcome
        assertTrue(outcome is MatchOutcome.Win)
        assertEquals(Player.P2, (outcome as MatchOutcome.Win).player)
    }

    @Test
    fun `the shape rule travels in the state and its hash`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            shapeRule = ShapeRule.DIAGONAL_ONLY,
        )
        val r = GameEngine.apply(s, Move.Drop(3)).state
        assertEquals(ShapeRule.DIAGONAL_ONLY, r.shapeRule)
        val without = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        assertTrue(
            pt.tiago.neonline.core.serialization.StateHasher.hash(s) !=
                pt.tiago.neonline.core.serialization.StateHasher.hash(without),
        )
    }
}

class LevelEvaluatorTest {

    private val duel = LevelObjective(
        kind = LevelKind.DUEL,
        starsTwo = 14,
        starsThree = 10,
    )
    private val win = MatchOutcome.Win(Player.P1, emptyList())
    private val rivalWin = MatchOutcome.Win(Player.P2, emptyList())
    private val draw = MatchOutcome.Draw("board_full")

    @Test
    fun `duel stars follow the certified move counts`() {
        assertEquals(LevelOutcome.Won(3), LevelEvaluator.evaluate(duel, win, myMoves = 10))
        assertEquals(LevelOutcome.Won(3), LevelEvaluator.evaluate(duel, win, myMoves = 9))
        assertEquals(LevelOutcome.Won(2), LevelEvaluator.evaluate(duel, win, myMoves = 14))
        assertEquals(LevelOutcome.Won(1), LevelEvaluator.evaluate(duel, win, myMoves = 15))
    }

    @Test
    fun `duel losses come from the rival or a draw`() {
        assertEquals(LevelOutcome.Lost(LossReasons.RIVAL_LINE), LevelEvaluator.evaluate(duel, rivalWin, myMoves = 5))
        assertEquals(LevelOutcome.Lost(LossReasons.DRAW), LevelEvaluator.evaluate(duel, draw, myMoves = 5))
    }

    @Test
    fun `puzzle stars follow the hints`() {
        val puzzle = LevelObjective(kind = LevelKind.PUZZLE, winInMoves = 3)
        assertEquals(LevelOutcome.Won(3), LevelEvaluator.evaluate(puzzle, win, myMoves = 3, hintsUsed = 0))
        assertEquals(LevelOutcome.Won(2), LevelEvaluator.evaluate(puzzle, win, myMoves = 3, hintsUsed = 1))
        assertEquals(LevelOutcome.Won(1), LevelEvaluator.evaluate(puzzle, win, myMoves = 3, hintsUsed = 3))
    }

    @Test
    fun `a puzzle won after the limit is a loss`() {
        val puzzle = LevelObjective(kind = LevelKind.PUZZLE, winInMoves = 3)
        assertEquals(
            LevelOutcome.Lost(LossReasons.OUT_OF_MOVES),
            LevelEvaluator.evaluate(puzzle, win, myMoves = 4),
        )
    }

    @Test
    fun `defense survives with one star and a threat with two`() {
        val defense = LevelObjective(kind = LevelKind.DEFENSE, surviveAiMoves = 8)
        assertEquals(LevelOutcome.Won(1), LevelEvaluator.evaluate(defense, null, myMoves = 8))
        assertEquals(
            LevelOutcome.Won(2),
            LevelEvaluator.evaluate(defense, null, myMoves = 8, threatAtEnd = true),
        )
        assertEquals(LevelOutcome.Won(3), LevelEvaluator.evaluate(defense, win, myMoves = 4))
        assertEquals(
            LevelOutcome.Lost(LossReasons.RIVAL_LINE),
            LevelEvaluator.evaluate(defense, rivalWin, myMoves = 4),
        )
    }

    @Test
    fun `shape and boss levels use the duel star rule`() {
        val shape = LevelObjective(kind = LevelKind.SHAPE, starsTwo = 12, starsThree = 8)
        assertEquals(LevelOutcome.Won(3), LevelEvaluator.evaluate(shape, win, myMoves = 8))
        val boss = LevelObjective(kind = LevelKind.BOSS, bestOf = 3, starsTwo = 12, starsThree = 8)
        assertEquals(LevelOutcome.Won(2), LevelEvaluator.evaluate(boss, win, myMoves = 12))
        assertEquals(LevelOutcome.Lost(LossReasons.RIVAL_LINE), LevelEvaluator.evaluate(boss, rivalWin, myMoves = 12))
    }
}
