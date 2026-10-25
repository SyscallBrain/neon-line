package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

class CampaignTimePersistenceTest {
    private fun saved(difficulty: Difficulty = Difficulty.EASY, elapsed: Long = 5000L) =
        SavedMatch("level-0001", difficulty, elapsedMs = elapsed)

    @Test fun `only completed campaign attempts add time and difficulties remain separate`() {
        val active = ProfileReducer.checkpoint(Profile(), 1, saved(), null, 2, 0)
        assertEquals(0L, active.stats(Difficulty.EASY).timeMs)
        assertEquals(5000L, active.savedMatch!!.elapsedMs)
        val won = ProfileReducer.checkpoint(active, 1, saved(), LevelOutcome.Won(3), 4, 0)
        val lost = ProfileReducer.checkpoint(won, 1, saved(elapsed = 2000), LevelOutcome.Lost("rival_line"), 3, 0)
        val drawn = ProfileReducer.checkpoint(lost, 1, saved(elapsed = 1000), LevelOutcome.Lost("draw"), 4, 0)
        val hard = ProfileReducer.checkpoint(drawn, 1, saved(Difficulty.HARD, 3000), LevelOutcome.Won(2), 5, 0)
        assertEquals(8000L, hard.stats(Difficulty.EASY).timeMs)
        assertEquals(3000L, hard.stats(Difficulty.HARD).timeMs)
        assertEquals(0L, hard.stats(Difficulty.MEDIUM).timeMs)
        assertEquals(1L, hard.stats(Difficulty.EASY).wins)
        assertEquals(1L, hard.stats(Difficulty.EASY).losses)
        assertEquals(1L, hard.stats(Difficulty.EASY).draws)
        assertNull(hard.savedMatch)
    }

    @Test fun `daily and free durations do not change campaign totals`() {
        val profile = Profile(statsByDifficulty = mapOf(Difficulty.EASY to DifficultyStats(timeMs = 2500L)))
        val daily = DailyChallenge.checkpoint(profile,
            SavedDaily("2026-10-16", 1, "0".repeat(64), saved()), LevelOutcome.Won(3))
        val free = FreePlay.checkpoint(daily, FreePlayConfig(), saved(), LevelOutcome.Won(3))
        assertEquals(profile.statsByDifficulty, free.statsByDifficulty)
        assertEquals(profile.progressByDifficulty, free.progressByDifficulty)
    }

    @Test fun `legacy journals default to zero and new durations survive store and backup`() {
        val legacy = Json.decodeFromString(Profile.serializer(), """{"savedMatch":{"levelId":"level-0001","difficulty":"EASY"}}""")
        assertEquals(0L, legacy.savedMatch!!.elapsedMs)
        val directory = Files.createTempDirectory("neonline-play-time").toFile()
        try {
            val active = legacy.copy(savedMatch = saved(elapsed = 98765), settings = Settings(sound = false))
            ProfileStore(directory).save(active)
            assertEquals(active, ProfileStore(directory).load())
            assertEquals(active, ProfileBackup.decode(ProfileBackup.encode(active)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `negative metadata is rejected and campaign totals saturate`() {
        assertFalse(Validator.isValid(Profile(savedMatch = saved(elapsed = -1))))
        val huge = Profile(statsByDifficulty = mapOf(Difficulty.EASY to DifficultyStats(timeMs = Long.MAX_VALUE - 3)))
        val added = ProfileReducer.checkpoint(huge, 1, saved(elapsed = 10), LevelOutcome.Won(3), 4, 0)
        assertEquals(Long.MAX_VALUE, added.stats(Difficulty.EASY).timeMs)
        assertTrue(Validator.isValid(added))
    }
}
