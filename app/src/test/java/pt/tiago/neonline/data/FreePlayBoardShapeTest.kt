package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.game.BoardAccessibility
import pt.tiago.neonline.game.MatchReplay

class FreePlayBoardShapeTest {
    @Test fun `masks have symmetric reachable floors for both geometries and connect targets`() {
        for ((width,height) in listOf(7 to 6, 9 to 7)) for (shape in FreePlayBoardShape.entries) for (connect in 4..5) {
            val rules = FreePlayRules(width = width, height = height, connect = connect, shape = shape)
            assertTrue(rules.isValid())
            val state = FreePlayConfig(version = 4, rules = rules).initial()
            assertEquals(rules.playableMask(), state.playable)
            assertEquals(width * height, state.playable.size)
            assertTrue(state.pieces.all { it == null })
            for (col in 0 until width) {
                val column = (0 until height).map { state.playable[state.index(it, col)] }
                assertTrue(column.takeWhile { it }.size >= 4)
                assertFalse(column.dropWhile { it }.any { it })
                assertEquals(column, (0 until height).map { state.playable[state.index(it, width - 1 - col)] })
            }
            // A whole top row is available, so connect4/5 remains achievable.
            assertTrue((0 until width).all { state.playable[state.index(0, it)] })
        }
        val pillars = FreePlayRules(shape = FreePlayBoardShape.PILLARS).playableMask()
        assertFalse(pillars[4 * 7 + 1]); assertFalse(pillars[5 * 7 + 5]); assertTrue(pillars[5 * 7 + 3])
        val steps = FreePlayRules(shape = FreePlayBoardShape.STEPS).playableMask()
        assertFalse(steps[5 * 7]); assertTrue(steps[4 * 7]); assertTrue(steps[5 * 7 + 3])
    }
    @Test fun `only version four may use shapes and previous defaults retain exact states and AI`() {
        for (version in 1..3) {
            val legacy = FreePlayConfig(version = version)
            assertEquals(legacy.initial(), legacy.copy(version = 4).initial())
            assertEquals(legacy.ai(), legacy.copy(version = 4).ai())
            assertFalse(legacy.copy(rules = FreePlayRules(shape = FreePlayBoardShape.PILLARS)).isValid())
        }
        assertEquals(FreePlayBoardShape.RECTANGLE, Json.decodeFromString(FreePlayRules.serializer(), "{}").shape)
        val faded = FreePlayConfig(version = 3, rules = FreePlayRules(fade = true))
        assertEquals(StateHasher.hash(faded.initial()), StateHasher.hash(faded.copy(version = 4).initial()))
        assertEquals(512, faded.copy(version = 4).maxPlies())
    }
    @Test fun `shape selection clears incompatible rules and raw combinations reject imports`() {
        val rules = FreePlayRules(popOut = true, rotation = true, specials = true, fade = true, clockSeconds = 20)
        for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
            val selected = rules.withShape(shape)
            assertFalse(selected.popOut); assertFalse(selected.rotation)
            assertFalse(selected.specials); assertTrue(selected.fade); assertEquals(20, selected.clockSeconds)
            assertTrue(selected.isValid()); assertTrue(selected.hasComplexSearch())
            assertFalse(selected.copy(specials = true).isValid()); assertFalse(selected.copy(popOut = true).isValid()); assertFalse(selected.copy(rotation = true).isValid())
            assertFalse(Validator.isValid(Profile(savedFreePlay = SavedFreePlay(FreePlayConfig(version = 4, rules = selected.copy(popOut = true)), SavedMatch("free-play", Difficulty.EASY)))))
            assertTrue(selected.withShape(FreePlayBoardShape.RECTANGLE).isValid())
        }
        assertEquals(rules, rules.withShape(FreePlayBoardShape.RECTANGLE))
    }
    @Test fun `drops fill only reachable cells and accessible capacity follows the floor`() {
        for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
            var state = FreePlayConfig(version = 4, rules = FreePlayRules(shape = shape)).initial()
            val col = 1
            val capacity = (0 until state.height).count { state.playable[state.index(it, col)] }
            val accessible = BoardAccessibility.columns(state, PieceKind.NORMAL)[col]
            assertEquals(capacity, accessible.free)
            assertEquals(state.height - capacity, accessible.cells.count { it.blocked })
            assertEquals(capacity - 1, accessible.landingRow)
            repeat(capacity) {
                val result = GameEngine.apply(state, Move.Drop(col))
                assertTrue(result.events.none { it is GameEvent.MoveRejected }); state = result.state
                assertTrue(state.pieces.indices.all { state.pieces[it] == null || state.playable[it] })
            }
            assertFalse(Move.Drop(col) in GameEngine.validMoves(state))
            assertNull(BoardAccessibility.columns(state, PieceKind.NORMAL)[col].landingRow)
        }
    }
    @Test fun `shapes cannot enable bombs whose legacy settlement ignores solid floors`() {
        for ((width,height) in listOf(7 to 6, 9 to 7)) for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
            val unsafe = FreePlayConfig(version = 4, rules = FreePlayRules(width = width, height = height, shape = shape, specials = true))
            assertFalse(unsafe.isValid()); assertNull(unsafe.ai())
            assertTrue(runCatching { unsafe.initial() }.isFailure)
            assertFalse(Validator.isValid(Profile(savedFreePlay = SavedFreePlay(unsafe, SavedMatch("free-play", Difficulty.EASY)))))
        }
    }
    @Test fun `medium and hard defend immediate threats on a raised floor within variant budget`() {
        for (difficulty in listOf(Difficulty.MEDIUM, Difficulty.HARD)) for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
            val config = FreePlayConfig(version = 4, difficulty = difficulty, rules = FreePlayRules(shape = shape))
            val initial = config.initial()
            // Build an immediate horizontal threat on row3, supporting each cell from its floor.
            val pieces = initial.pieces.toMutableList()
            for (col in 0..3) for (row in 4 until initial.height) if (initial.playable[initial.index(row,col)])
                pieces[initial.index(row,col)] = Piece(if (row == 4 && col == 3) Player.P1 else Player.P2, PieceKind.NORMAL, 0)
            for (col in 0..2) pieces[initial.index(3,col)] = Piece(Player.P1, PieceKind.NORMAL, 0)
            val state = initial.copy(pieces = pieces, toMove = Player.P2)
            val threat = state.copy(toMove = Player.P1)
            assertEquals(listOf(Move.Drop(3)), GameEngine.validMoves(threat).filter { GameEngine.apply(threat, it).state.status == Status.Won(Player.P1) })
            val search = AiSearch(config.ai()!!); val choice = search.chooseMove(state)!!
            assertEquals(Move.Drop(3), choice.move)
            assertTrue(search.nodesExplored <= config.ai()!!.nodeBudget)
            assertTrue(config.hintAi()!!.nodeBudget <= 3000)
        }
    }
    @Test fun `shaped saved journals survive storage backup reset and refuse changed masks`() {
        val dir = Files.createTempDirectory("free-shape").toFile()
        try {
            for (shape in listOf(FreePlayBoardShape.PILLARS, FreePlayBoardShape.STEPS)) {
                val config = FreePlayConfig(version = 4, rules = FreePlayRules(shape = shape, fade = true, clockSeconds = 10))
                var state = config.initial()
                val steps = listOf(Move.Drop(1), Move.Drop(2), Move.Drop(3)).map { move ->
                    val before = state; val result = GameEngine.apply(before, move)
                    assertTrue(result.events.none { it is GameEvent.MoveRejected }); state = result.state
                    MatchReplay.step(before, move, state)
                }
                val saved = SavedFreePlay(config, SavedMatch("free-play", Difficulty.EASY, initialHash = StateHasher.hash(config.initial()), stateHash = StateHasher.hash(state),
                    steps = steps, hintsLeft = 3, undosLeft = 3, clockMs = 10_000))
                assertEquals(state, FreePlay.restore(saved)!!.state)
                val profile = Profile(savedFreePlay = saved, freePlayPeeks = mapOf(Difficulty.EASY to 2L))
                val store = ProfileStore(dir); store.save(profile)
                assertEquals(profile, ProfileStore(dir).load()); assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
                assertEquals(saved, store.resetProgress().savedFreePlay)
                assertNull(FreePlay.restore(saved.copy(config = config.copy(rules = config.rules.copy(shape = FreePlayBoardShape.RECTANGLE)))))
            }
        } finally { dir.deleteRecursively() }
    }
}
