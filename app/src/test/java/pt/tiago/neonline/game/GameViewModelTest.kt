package pt.tiago.neonline.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LevelObjective
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher

class GameViewModelTest {

    private val profile = AiProfile(
        maxDepth = 2,
        nodeBudget = 1_000,
        attack = 3,
        defense = 4,
        center = 2,
        trap = 1,
    )

    private fun blank(): GameState = GameState.initial(
        levelId = "t",
        difficulty = Difficulty.EASY,
        engineVersion = 1,
        seedHex = "0123456789abcdef",
        width = 7,
        height = 6,
        connect = 4,
        playable = List(42) { true },
        pieces = MutableList(42) { null },
    )

    @Test
    fun `a saved match replays to the same state and hash`() {
        val start = blank()
        val moves = listOf(Move.Drop(3), Move.Drop(4), Move.Drop(3))
        val restored = GameViewModel.replay(start, moves)!!
        val direct = moves.fold(start) { acc, move -> GameEngine.apply(acc, move).state }
        assertEquals(direct, restored)
        assertEquals(StateHasher.hash(direct), GameViewModel.hashOf(restored))
    }

    @Test
    fun `replay refuses an illegal move`() {
        assertNull(GameViewModel.replay(blank(), listOf(Move.Drop(99))))
        assertNotNull(GameViewModel.replay(blank(), listOf(Move.Drop(0))))
    }

    @Test
    fun `the hint column is a legal drop`() {
        val column = hintColumnFor(blank(), profile)
        assertNotNull(column)
        assertTrue(GameEngine.validMoves(blank()).any { it is Move.Drop && it.col == column })
    }

    @Test
    fun `the hint takes the immediate win when there is one`() {
        val base = blank()
        val pieces = base.pieces.toMutableList()
        val piece = Piece(Player.P1, PieceKind.NORMAL, 0)
        pieces[35] = piece
        pieces[36] = piece
        pieces[37] = piece
        val withThree = base.copy(pieces = pieces)
        assertEquals(3, hintColumnFor(withThree, profile))
    }

    @Test
    fun `the clock lives in the state but the engine ignores it`() {
        val start = blank()
        val state = start.copy(moveClockSec = 10)
        // The engine never reads the clock: a TimeUp move is just another move.
        val timeUp = GameEngine.apply(state, Move.TimeUp)
        assertTrue(GameEngine.validMoves(state).isNotEmpty())
        // The column comes from the auxiliary stream, so any single piece on the floor is fine.
        assertEquals(1, timeUp.state.pieces.count { it != null })
    }

    @Test
    fun `factory can disable easy undo for tournament matches`() {
        val factory = GameViewModel.factory(blank(), LevelObjective(LevelKind.DUEL), profile, Difficulty.EASY, 0, undoAvailable = 0)
        val model = factory.create(GameViewModel::class.java)

        assertEquals(0, model.ui.value.undosLeft)
    }
}
