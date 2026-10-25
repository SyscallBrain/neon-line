package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import java.nio.file.Files
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.serialization.StateHasher

class FreePlayPeekTest {
    @Test fun `new config enables difficulty based visual fade while old versions remain exact`() {
        for ((difficulty, fade) in listOf(Difficulty.EASY to 5, Difficulty.MEDIUM to 3, Difficulty.HARD to 2)) {
            val config = FreePlayConfig(version = 3, difficulty = difficulty, rules = FreePlayRules(fade = true))
            assertEquals(fade, config.initial().fadeAfter)
            assertEquals(config.copy(rules = FreePlayRules()).ai(), config.ai())
            assertNull(config.copy(rules = FreePlayRules()).initial().fadeAfter)
            assertFalse(config.copy(version = 2).isValid())
            assertFalse(config.copy(version = 1).isValid())
        }
        val legacy = Json.decodeFromString(FreePlayConfig.serializer(), "{}")
        assertEquals(FreePlayConfig(), legacy)
        assertNull(legacy.initial().fadeAfter)
        assertEquals(legacy.ai(), legacy.copy(version = 2).ai())
    }
    @Test fun `AI still sees a winning line made of hidden pieces`() {
        val config = FreePlayConfig(version = 3, difficulty = Difficulty.HARD, rules = FreePlayRules(fade = true))
        val initial = config.initial()
        val pieces = MutableList<Piece?>(42) { null }
        for (column in 0..2) pieces[35 + column] = Piece(Player.P2, PieceKind.NORMAL, 0)
        val hidden = initial.copy(pieces = pieces, ply = 10, toMove = Player.P2)
        val choice = pt.tiago.neonline.core.ai.AiSearch(config.ai()!!).chooseMove(hidden)!!
        assertEquals(Move.Drop(3), choice.move)
        assertEquals(Status.Won(Player.P2), pt.tiago.neonline.core.rules.GameEngine.apply(hidden.copy(aiStateHex = choice.aiStateHex), choice.move).state.status)
    }
    @Test fun `peeks are counted on checkpoint once and restart preserves accumulated totals`() {
        val config = FreePlayConfig(version = 3, rules = FreePlayRules(fade = true))
        val hash = StateHasher.hash(config.initial())
        val start = SavedMatch("free-play", Difficulty.EASY, initialHash = hash, stateHash = hash, hintsLeft = 3, undosLeft = 3)
        var profile = FreePlay.checkpoint(Profile(), config, start, null)
        profile = FreePlay.checkpoint(profile, config, start.copy(peekCount = 1), null)
        profile = FreePlay.checkpoint(profile, config, start.copy(peekCount = 1), null)
        assertEquals(1L, profile.freePlayPeeks[Difficulty.EASY])
        profile = FreePlay.checkpoint(profile, config, start.copy(peekCount = 2), LevelOutcome.Lost("draw"))
        assertEquals(2L, profile.freePlayPeeks[Difficulty.EASY])
        profile = FreePlay.checkpoint(profile, config, start, null)
        profile = FreePlay.checkpoint(profile, config, start.copy(peekCount = 1), null)
        assertEquals(3L, profile.freePlayPeeks[Difficulty.EASY])
        assertEquals(0, profile.totalStars()); assertTrue(profile.rivalRecords.isEmpty())
    }
    @Test fun `peek totals and optional journal counter survive storage backup and campaign reset`() {
        val dir = Files.createTempDirectory("peek-profile").toFile()
        try {
            val config = FreePlayConfig(version = 3, rules = FreePlayRules(fade = true))
            val hash = StateHasher.hash(config.initial())
            val saved = SavedFreePlay(config, SavedMatch("free-play", Difficulty.EASY, initialHash = hash, stateHash = hash, hintsLeft = 3, undosLeft = 3, peekCount = 2))
            val profile = Profile(savedFreePlay = saved, freePlayPeeks = mapOf(Difficulty.EASY to 2L))
            val store = ProfileStore(dir); store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            assertEquals(profile.freePlayPeeks, store.resetProgress().freePlayPeeks)
            assertNotNull(FreePlay.restore(store.load().savedFreePlay!!))
            assertEquals(0, Json.decodeFromString(SavedMatch.serializer(), "{\"levelId\":\"free-play\",\"difficulty\":\"EASY\"}").peekCount)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `invalid counters are refused and totals saturate safely`() {
        assertFalse(Validator.isValid(Profile(freePlayPeeks = mapOf(Difficulty.EASY to -1L))))
        assertFalse(Validator.isValid(Profile(savedMatch = SavedMatch("level-0001", Difficulty.EASY, peekCount = -1))))
        val config = FreePlayConfig(version = 3)
        val profile = Profile(freePlayPeeks = mapOf(Difficulty.EASY to FreePlay.MAX_COUNTER))
        val after = FreePlay.checkpoint(profile, config, SavedMatch("free-play", Difficulty.EASY, peekCount = 1), null)
        assertTrue(FreePlay.isValid(after))
        assertEquals(FreePlay.MAX_COUNTER, after.freePlayPeeks[Difficulty.EASY])
    }
}
