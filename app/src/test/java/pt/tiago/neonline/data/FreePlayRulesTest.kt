package pt.tiago.neonline.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.game.MatchReplay
import java.nio.file.Files

class FreePlayRulesTest {
    private fun journal(config: FreePlayConfig, moves: List<Move>): SavedFreePlay {
        var state = config.initial()
        val initialHash = StateHasher.hash(state)
        val steps = moves.map { move ->
            val before = state
            val result = GameEngine.apply(before, move)
            assertTrue(result.events.none { it is GameEvent.MoveRejected })
            state = result.state
            MatchReplay.step(before, move, state)
        }
        return SavedFreePlay(config, SavedMatch("free-play", config.difficulty, initialHash = initialHash,
            stateHash = StateHasher.hash(state), steps = steps, hintsLeft = config.hints(), undosLeft = config.undos(),
            clockMs = config.rules.clockSeconds.takeIf { it > 0 }?.times(1000L)))
    }
    @Test fun `legacy configuration retains exact classic state and profile after new fields`() {
        val legacy = Json.decodeFromString(FreePlayConfig.serializer(), "{}")
        assertEquals(1, legacy.version)
        val expected = GameState.initial("free-play", Difficulty.EASY, CURRENT_ENGINE_VERSION, "0123456789abcdef", 7, 6, 4, List(42) { true }, List(42) { null })
        assertEquals(StateHasher.hash(expected), StateHasher.hash(legacy.initial()))
        assertEquals(1000, legacy.ai()!!.nodeBudget)
        assertNotNull(FreePlay.restore(journal(legacy, listOf(Move.Drop(2)))))
    }
    @Test fun `version two rule combinations reach the engine and reject unsupported geometry`() {
        for ((w,h) in listOf(7 to 6, 9 to 7)) for (connect in 4..5) for (specials in listOf(false,true)) {
            val rules = FreePlayRules(width = w, height = h, connect = connect, popOut = true, specials = specials, clockSeconds = 10)
            val config = FreePlayConfig(version = 2, rules = rules, starter = Player.P2)
            val start = config.initial()
            assertEquals(w, start.width); assertEquals(h, start.height); assertEquals(connect, start.connect)
            assertEquals(Player.P2, start.toMove); assertTrue(start.popOut); assertEquals(10, start.moveClockSec)
            for (player in Player.entries) for (kind in listOf(PieceKind.WILD, PieceKind.BOMB, PieceKind.LOCK))
                assertEquals(if (specials) 1 else 0, start.inventory.getValue(player)[kind] ?: 0)
        }
        assertFalse(FreePlayRules(width = 9, height = 7, rotation = true).isValid())
        assertFalse(FreePlayRules(clockSeconds = 1).isValid())
        assertFalse(FreePlayConfig(rules = FreePlayRules(popOut = true)).isValid())
    }
    @Test fun `special pop rotation and timeout journals resume with the same exact state`() {
        val special = FreePlayConfig(version = 2, rules = FreePlayRules(popOut = true, specials = true))
        val specialSaved = journal(special, listOf(Move.Drop(0), Move.Drop(1, PieceKind.WILD), Move.Pop(0), Move.Drop(2, PieceKind.BOMB)))
        assertEquals(specialSaved.match.stateHash, StateHasher.hash(FreePlay.restore(specialSaved)!!.state))
        val rotation = FreePlayConfig(version = 2, rules = FreePlayRules(rotation = true, clockSeconds = 20))
        val rotated = journal(rotation, listOf(Move.Drop(0),Move.Drop(1),Move.Drop(2),Move.Drop(3),Move.Drop(4),Move.Drop(5)))
        val restored = FreePlay.restore(rotated)!!.state
        assertEquals(6, restored.width); assertEquals(7, restored.height)
        assertEquals(rotated.match.stateHash, StateHasher.hash(restored))
        val timed = journal(rotation, listOf(Move.TimeUp))
        assertEquals(1, FreePlay.restore(timed)!!.humanMoves)
        assertNull(FreePlay.restore(timed.copy(match = timed.match.copy(clockMs = 20_001))))
    }
    @Test fun `complex rules use bounded profiles and classic profiles remain unchanged`() {
        for (difficulty in Difficulty.entries) {
            val classic = FreePlayConfig(difficulty = difficulty)
            assertEquals(classic.ai(), classic.copy(version = 2).ai())
            val special = classic.copy(version = 2, rules = FreePlayRules(width = 9, height = 7, specials = true, popOut = true))
            assertTrue(special.ai()!!.nodeBudget <= 1000)
            assertTrue(special.hintAi()!!.nodeBudget <= 1000)
            assertNotNull(special.ai())
        }
    }
    @Test fun `bounded medium and hard defend immediate threats with connect five and special inventory`() {
        for (difficulty in listOf(Difficulty.MEDIUM, Difficulty.HARD)) for ((width,height,connect) in listOf(Triple(7,6,4), Triple(9,7,5))) {
            val config = FreePlayConfig(version = 2, difficulty = difficulty, starter = Player.P2,
                rules = FreePlayRules(width = width, height = height, connect = connect, specials = true, popOut = true))
            val start = config.initial().let { state -> state.copy(pieces = state.pieces.toMutableList().also { pieces ->
                (0 until connect - 1).forEach { pieces[(height - 1) * width + it] = Piece(Player.P1, PieceKind.NORMAL, 0) }
            }) }
            val choice = pt.tiago.neonline.core.ai.AiSearch(config.ai()!!).chooseMove(start)!!
            val result = GameEngine.apply(start.copy(aiStateHex = choice.aiStateHex), choice.move)
            assertTrue(result.events.none { it is GameEvent.MoveRejected })
            assertFalse((result.outcome as? MatchOutcome.Win)?.player == Player.P1)
            if (result.outcome == null) assertTrue(GameEngine.validMoves(result.state).none { move ->
                (GameEngine.apply(result.state, move).outcome as? MatchOutcome.Win)?.player == Player.P1
            })
        }
    }
    @Test fun `custom game survives backup storage and campaign reset without requiring new unlocks`() {
        val dir = Files.createTempDirectory("free-rules").toFile()
        try {
            val config = FreePlayConfig(version = 2, rules = FreePlayRules(specials = true, popOut = true))
            val saved = journal(config, listOf(Move.Drop(2, PieceKind.LOCK),Move.Drop(3)))
            val profile = Profile(savedFreePlay = saved, freePlayStats = mapOf(Difficulty.EASY to RivalRecord(wins = 2)))
            val store = ProfileStore(dir); store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            val reset = store.resetProgress()
            assertEquals(saved, reset.savedFreePlay)
            assertNotNull(FreePlay.restore(reset.savedFreePlay!!))
            assertEquals(profile.freePlayStats, reset.freePlayStats)
        } finally { dir.deleteRecursively() }
    }
}
