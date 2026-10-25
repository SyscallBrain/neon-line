package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.game.*

class ClockwiseConfigTest {
    private val opening = listOf(0, 1, 2, 3, 0, 4)
    private fun free(config: FreePlayConfig): SavedFreePlay {
        var state = config.initial()
        val initialHash = StateHasher.hash(state)
        val steps = opening.map { col ->
            val move = Move.Drop(col)
            val next = GameEngine.apply(state, move).state
            MatchReplay.step(state, move, next).also { state = next }
        }
        return SavedFreePlay(config, SavedMatch("free-play", config.difficulty, initialHash = initialHash,
            stateHash = StateHasher.hash(state), steps = steps, hintsLeft = config.hints(), undosLeft = config.undos()))
    }
    private fun local(config: LocalConfig): SavedLocalSession {
        var state = config.initial(Player.P1)
        val steps = opening.map { col ->
            val move = Move.Drop(col)
            val next = GameEngine.apply(state, move).state
            MatchReplay.step(state, move, next).also { state = next }
        }
        return SavedLocalSession(config = config, steps = steps, stateHash = StateHasher.hash(state))
    }

    @Test fun `new free configs pin clockwise engine and retain hard classic exact policy`() {
        val config = FreePlayConfig(version = 7, difficulty = Difficulty.HARD)
        assertNotNull(config.ai())
        assertEquals(4, config.initial().engineVersion)
        assertEquals(512, config.maxPlies())
        assertTrue(config.exactEndgameRival())
        assertEquals(config.copy(version = 6).ai(), config.ai())
        assertEquals(config.ai(), config.hintAi())
        for (d in listOf(Difficulty.EASY, Difficulty.MEDIUM)) assertFalse(config.copy(difficulty = d).exactEndgameRival())
        val rotating = config.copy(rules = FreePlayRules(rotation = true))
        assertFalse(rotating.exactEndgameRival())
        val after = requireNotNull(FreePlay.restore(free(rotating))).state
        assertEquals(Player.P1, after.pieceAt(6, 1)?.owner)
        assertEquals(Player.P2, after.pieceAt(6, 0)?.owner)
    }

    @Test fun `old and new free saves retain their engine across replay with mismatched envelopes rejected`() {
        for (version in 2..7) {
            val config = FreePlayConfig(version = version, rules = FreePlayRules(rotation = true))
            val saved = free(config)
            val after = requireNotNull(FreePlay.restore(saved)).state
            val expected = when { version == 7 -> 4; version >= 5 -> 3; else -> 2 }
            assertEquals(expected, after.engineVersion)
            assertEquals(Player.P1, after.pieceAt(6, if (version == 7) 1 else 4)?.owner)
            assertEquals(saved, Json.decodeFromString(SavedFreePlay.serializer(), Json.encodeToString(SavedFreePlay.serializer(), saved)))
            assertNull(FreePlay.restore(saved.copy(engineVersion = if (version == 7) 3 else 4)))
            assertNull(FreePlay.restore(saved.copy(config = config.copy(version = if (version == 7) 6 else 7))))
        }
    }

    @Test fun `local configs and envelopes decode old omitted fields and pin clockwise only in version two`() {
        assertEquals(1, Json.decodeFromString(LocalConfig.serializer(), "{}").version)
        val old = Json.decodeFromString(SavedLocalSession.serializer(), "{}")
        assertEquals(2, old.engineVersion)
        for (version in 1..2) {
            val config = LocalConfig(version = version, rotation = true, popOut = true)
            val saved = local(config)
            val after = requireNotNull(LocalSessionReplay.restore(saved))
            assertEquals(if (version == 2) 4 else 2, saved.engineVersion)
            assertEquals(Player.P1, after.pieceAt(6, if (version == 2) 1 else 4)?.owner)
            assertEquals(after, LocalSessionReplay.trace(saved)?.state)
            assertEquals(saved, Json.decodeFromString(SavedLocalSession.serializer(), Json.encodeToString(SavedLocalSession.serializer(), saved)))
            assertNull(LocalSessionReplay.restore(saved.copy(engineVersion = if (version == 2) 2 else 4)))
            assertNull(LocalSessionReplay.restore(saved.copy(config = config.copy(version = 3 - version))))
        }
    }

    @Test fun `new local and free journals survive disk backup import and campaign reset`() {
        val dir = Files.createTempDirectory("clockwise-config").toFile()
        try {
            val profile = Profile(localSession = local(LocalConfig(version = 2, rotation = true)),
                savedFreePlay = free(FreePlayConfig(version = 7, rules = FreePlayRules(rotation = true))),
                savedMatch = SavedMatch("level-0002", Difficulty.EASY))
            assertTrue(Validator.isValid(profile))
            val store = ProfileStore(dir)
            store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            store.importProfile(profile)
            val reset = store.resetProgress()
            assertNull(reset.savedMatch)
            assertEquals(profile.localSession, reset.localSession)
            assertEquals(profile.savedFreePlay, reset.savedFreePlay)
            assertNotNull(LocalSessionReplay.restore(reset.localSession!!))
            assertNotNull(FreePlay.restore(reset.savedFreePlay!!))
        } finally { dir.deleteRecursively() }
    }
}
