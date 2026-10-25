package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

class DailyChallengeTest {
    private val sha = "a".repeat(64)
    private fun saved(date: String = "2026-10-14", difficulty: Difficulty = Difficulty.EASY) =
        SavedDaily(date, 1, sha, SavedMatch("level-0002", difficulty))
    private val pool = DailyPoolDto(1, 2, (2..14).flatMap { ordinal -> Difficulty.entries.map {
        DailyEntryDto("level-%04d".format(ordinal), ordinal, difficultyKey(it), sha)
    } })
    @Test fun `choice is reproducible across entry ordering and independent of other difficulties`() {
        for (difficulty in Difficulty.entries) {
            val choice = DailyChallenge.choose(pool, "2026-10-14", difficulty)!!
            assertEquals(difficultyKey(difficulty), choice.difficulty)
            assertEquals(choice, DailyChallenge.choose(pool.copy(entries = pool.entries.reversed()), "2026-10-14", difficulty))
            assertEquals(choice, DailyChallenge.choose(pool.copy(entries = pool.entries.filter { it.difficulty == difficultyKey(difficulty) }), "2026-10-14", difficulty))
        }
        // SHA-256("2026-10-14:easy:1"), first four unsigned bytes modulo thirteen.
        assertEquals(14, DailyChallenge.choose(pool, "2026-10-14", Difficulty.EASY)!!.ordinal)
    }
    @Test fun `calendar dates are strict and missing difficulties cannot play`() {
        assertTrue(DailyChallenge.validDate("2024-02-29"))
        for (date in listOf("2026-02-29", "2026-2-03", "2026-13-01", "junk")) {
            assertFalse(DailyChallenge.validDate(date))
            assertNull(DailyChallenge.choose(pool, date, Difficulty.HARD))
        }
        assertNull(DailyChallenge.choose(pool.copy(entries = emptyList()), "2026-10-14", Difficulty.EASY))
    }
    @Test fun `retries keep best stars and count only finished attempts`() {
        var profile = DailyChallenge.checkpoint(Profile(), saved(), null)
        assertTrue(profile.dailyHistory.isEmpty())
        assertEquals(saved(), profile.savedDaily)
        profile = DailyChallenge.checkpoint(profile, saved(), LevelOutcome.Won(1))
        profile = DailyChallenge.checkpoint(profile, saved(), LevelOutcome.Won(3))
        profile = DailyChallenge.checkpoint(profile, saved(), LevelOutcome.Lost("failed"))
        profile = DailyChallenge.checkpoint(profile, saved(), LevelOutcome.Won(2))
        assertNull(profile.savedDaily)
        assertEquals(DailyRecord("2026-10-14", Difficulty.EASY, "level-0002", 1, sha, 3, 4, 3), profile.dailyHistory.single())
        assertTrue(DailyChallenge.isValid(profile))
    }
    @Test fun `daily results do not award campaign stars stats achievements or local wins`() {
        val profile = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 2, 4, 1).copy(
            settings = Settings(sound = false), savedMatch = SavedMatch("level-0001", Difficulty.HARD), localSession = SavedLocalSession(wins1 = 4))
        val updated = DailyChallenge.checkpoint(profile, saved(), LevelOutcome.Won(3))
        assertEquals(profile.copy(revision = updated.revision, dailyHistory = updated.dailyHistory), updated)
        assertEquals(profile.totalStars(), updated.totalStars())
    }
    @Test fun `history retains exactly 270 calendar days and all three difficulties`() {
        var profile = Profile()
        val start = LocalDate.of(2024, 1, 1)
        for (day in 0..270) for (difficulty in Difficulty.entries)
            profile = DailyChallenge.checkpoint(profile, saved(start.plusDays(day.toLong()).toString(), difficulty), LevelOutcome.Won(3))
        assertEquals(810, profile.dailyHistory.size)
        assertEquals(start.plusDays(1).toString(), profile.dailyHistory.last().date)
        assertTrue(DailyChallenge.isValid(profile))
        val future = profile.dailyHistory
        profile = DailyChallenge.checkpoint(profile, saved(start.toString()), LevelOutcome.Lost("failed"))
        assertEquals(future, profile.dailyHistory)
    }
    @Test fun `invalid history duplicates and mismatched pinned games are rejected`() {
        val profile = DailyChallenge.checkpoint(Profile(), saved(), LevelOutcome.Won(3))
        val record = profile.dailyHistory.single()
        for (bad in listOf(record.copy(date = "bad"), record.copy(stars = 4), record.copy(wins = 2),
            record.copy(stars = 0), record.copy(levelSha256 = "bad"), record.copy(poolVersion = 0)))
            assertFalse(DailyChallenge.isValid(profile.copy(dailyHistory = listOf(bad))))
        assertFalse(DailyChallenge.isValid(profile.copy(dailyHistory = listOf(record, record))))
        assertFalse(DailyChallenge.isValid(profile.copy(savedDaily = saved().copy(poolVersion = 2))))
        assertThrows(IllegalArgumentException::class.java) { DailyChallenge.checkpoint(profile, saved().copy(levelSha256 = "b".repeat(64)), LevelOutcome.Won(3)) }
    }
    @Test fun `legacy profile and backup storage reset preserve independent daily data`() {
        assertTrue(Json.decodeFromString(Profile.serializer(), "{}").dailyHistory.isEmpty())
        val dir = Files.createTempDirectory("daily-store").toFile()
        try {
            val profile = DailyChallenge.checkpoint(Profile(), saved(), LevelOutcome.Won(2)).copy(savedDaily = saved())
            val store = ProfileStore(dir)
            store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            val reset = store.resetProgress()
            assertEquals(profile.savedDaily, reset.savedDaily)
            assertEquals(profile.dailyHistory, reset.dailyHistory)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `exported daily pool loads with integrity checks`() {
        val root = listOf(File("build/generated/neonAssets"), File("app/build/generated/neonAssets")).first { it.isDirectory }
        fun repository(tamper: Boolean) = CatalogRepository(object : AssetSource {
            override fun readText(path: String): String? = if (tamper && path == "daily/pool.json") "{}" else File(root, path).takeIf { it.isFile }?.readText()
        })
        val actual = repository(false).dailyPool()!!
        assertEquals(39, actual.entries.size)
        assertTrue(actual.entries.none { it.ordinal == 1 })
        assertNull(repository(true).dailyPool())
    }
}
