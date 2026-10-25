package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome
import java.io.File
import java.nio.file.Files

class CampaignAchievementsTest {
    private fun goal(profile: Profile, goal: CampaignGoal, difficulty: Difficulty = Difficulty.EASY) =
        CampaignAchievements.progress(profile, difficulty).first { it.definition.goal == goal }
    private fun wins(count: Int, difficulty: Difficulty = Difficulty.EASY, stars: Int = 3): Profile =
        (1..count).fold(Profile()) { profile, ordinal -> ProfileReducer.recordWin(profile, ordinal, difficulty, stars, 4, 1) }
    private fun legacy(count: Int, stars: Int = 3): Profile = wins(count, stars = stars).copy(achievements = emptyList())

    @Test fun `all definitions have stable unique ids and sixteen goals per difficulty`() {
        assertEquals(48, CampaignAchievements.definitions.size)
        assertEquals(48, CampaignAchievements.definitions.map { it.id }.toSet().size)
        Difficulty.entries.forEach { assertEquals(16, CampaignAchievements.progress(Profile(), it).size) }
    }
    @Test fun `fresh profile has no unlocks and finite zero progress`() {
        assertTrue(CampaignAchievements.reconcile(Profile()).achievements.isEmpty())
        assertTrue(CampaignAchievements.progress(Profile(), Difficulty.EASY).all { !it.unlocked && it.current == 0 && it.fraction == 0f })
    }
    @Test fun `first win grants only its own difficulty in the same revision`() {
        val profile = ProfileReducer.recordWin(Profile(), 1, Difficulty.HARD, 3, 4, 0)
        assertEquals(1L, profile.revision)
        assertEquals(listOf(Achievement("campaign.first_win.hard", 1)), profile.achievements)
        assertTrue(goal(profile, CampaignGoal.FIRST_WIN, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.FIRST_WIN).unlocked)
    }
    @Test fun `replaying a level never counts as multiple completed levels or duplicates a badge`() {
        val profile = (1..30).fold(Profile()) { p, _ -> ProfileReducer.recordWin(p, 1, Difficulty.EASY, 3, 4, 0) }
        assertEquals(1, profile.achievements.size)
        assertEquals(1, goal(profile, CampaignGoal.LEVELS_10).current)
        assertEquals(1, goal(profile, CampaignGoal.PERFECT_10).current)
        assertEquals(3, goal(profile, CampaignGoal.STARS_100).current)
        assertEquals(30L, profile.stats(Difficulty.EASY).wins)
        assertEquals(1L, profile.achievements.first().unlockedAtRevision)
    }
    @Test fun `level thresholds unlock at ten twenty five and fifty distinct wins`() {
        assertFalse(goal(wins(9), CampaignGoal.LEVELS_10).unlocked)
        assertTrue(goal(wins(10), CampaignGoal.LEVELS_10).unlocked)
        assertFalse(goal(wins(24), CampaignGoal.LEVELS_25).unlocked)
        assertTrue(goal(wins(25), CampaignGoal.LEVELS_25).unlocked)
        assertFalse(goal(wins(49), CampaignGoal.LEVELS_50).unlocked)
        assertTrue(goal(wins(50), CampaignGoal.LEVELS_50).unlocked)
    }
    @Test fun `three star goals require best three stars rather than mere completion`() {
        val low = wins(25, stars = 2)
        assertFalse(goal(low, CampaignGoal.PERFECT_10).unlocked)
        assertEquals(0, goal(low, CampaignGoal.PERFECT_10).current)
        assertTrue(goal(wins(10), CampaignGoal.PERFECT_10).unlocked)
        assertTrue(goal(wins(25), CampaignGoal.PERFECT_25).unlocked)
    }
    @Test fun `star goal uses unique best results and survives worse repeats`() {
        var profile = wins(33)
        assertEquals(99, goal(profile, CampaignGoal.STARS_100).current)
        assertFalse(goal(profile, CampaignGoal.STARS_100).unlocked)
        profile = ProfileReducer.recordWin(profile, 34, Difficulty.EASY, 1, 8, 2)
        assertTrue(goal(profile, CampaignGoal.STARS_100).unlocked)
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.EASY, 1, 15, 3)
        assertTrue(goal(profile, CampaignGoal.STARS_100).unlocked)
        assertEquals(1f, goal(profile, CampaignGoal.STARS_100).fraction)
    }
    @Test fun `level fifty alone proves stop goal without assuming other levels completed`() {
        val profile = ProfileReducer.recordWin(Profile(), 50, Difficulty.MEDIUM, 1, 12, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_1, Difficulty.MEDIUM).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.MEDIUM).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_1).unlocked)
    }
    @Test fun `level one hundred alone proves stop two goal`() {
        val profile = ProfileReducer.recordWin(Profile(), 100, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_2, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_2).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `level one hundred fifty alone proves Stop 3 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 150, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_3, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_3).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `level two hundred alone proves Stop 4 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 200, Difficulty.MEDIUM, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_4, Difficulty.MEDIUM).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_4, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.MEDIUM).unlocked)
    }
    @Test fun `level two hundred fifty alone proves Stop 5 goal only for its difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 250, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_5, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_5, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `level three hundred alone proves Stop 6 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 300, Difficulty.MEDIUM, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_6, Difficulty.MEDIUM).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_6, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.MEDIUM).unlocked)
    }
    @Test fun `level three hundred fifty alone proves Stop 7 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 350, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_7, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_7, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `level four hundred alone proves Stop 8 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 400, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_8, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_8, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `level four hundred fifty alone proves Stop 9 goal independently per difficulty`() {
        val profile = ProfileReducer.recordWin(Profile(), 450, Difficulty.HARD, 1, 18, 2)
        assertTrue(goal(profile, CampaignGoal.STOP_9, Difficulty.HARD).unlocked)
        assertFalse(goal(profile, CampaignGoal.STOP_9, Difficulty.EASY).unlocked)
        assertFalse(goal(profile, CampaignGoal.LEVELS_50, Difficulty.HARD).unlocked)
    }
    @Test fun `losses draws and local session never earn campaign milestones`() {
        val local = SavedLocalSession(wins1 = 100, wins2 = 100)
        val profile = ProfileReducer.recordDraw(ProfileReducer.recordLoss(Profile(localSession = local), Difficulty.EASY, 3), Difficulty.HARD)
        assertTrue(CampaignAchievements.reconcile(profile).achievements.isEmpty())
    }
    @Test fun `legacy reconciliation is idempotent and preserves all other data`() {
        val profile = legacy(25).copy(savedMatch = SavedMatch("level-0026", Difficulty.EASY), settings = Settings(soundVolume = .3f),
            localSession = SavedLocalSession(), achievements = listOf(Achievement("future-badge", 1)))
        val result = CampaignAchievements.reconcile(profile)
        assertEquals(profile.revision + 1, result.revision)
        assertEquals(profile.progressByDifficulty, result.progressByDifficulty)
        assertEquals(profile.statsByDifficulty, result.statsByDifficulty)
        assertEquals(profile.settings, result.settings)
        assertEquals(profile.savedMatch, result.savedMatch)
        assertEquals(profile.localSession, result.localSession)
        assertTrue(result.achievements.contains(Achievement("future-badge", 1)))
        assertEquals(result, CampaignAchievements.reconcile(result))
    }
    @Test fun `malformed and wrong difficulty ids do not earn goals`() {
        val ids = setOf("level-0000:easy", "level-0601:easy", "level-0001:hard", "garbage")
        val profile = Profile(progressByDifficulty = mapOf(Difficulty.EASY to DifficultyProgress(ids, ids.associateWith { LevelBest(3) })))
        assertTrue(CampaignAchievements.reconcile(profile).achievements.isEmpty())
    }
    @Test fun `orphan best records do not claim completed or three star levels`() {
        val profile = Profile(progressByDifficulty = mapOf(Difficulty.EASY to DifficultyProgress(bestByVariantId =
            (1..50).associate { ProfileReducer.variantId(it, Difficulty.EASY) to LevelBest(3) })))
        assertTrue(CampaignAchievements.reconcile(profile).achievements.isEmpty())
    }
    @Test fun `existing unlock remains recorded even if current best records are missing`() {
        val profile = Profile(achievements = listOf(Achievement("campaign.perfect_25.easy", 7)))
        assertTrue(goal(profile, CampaignGoal.PERFECT_25).unlocked)
        assertEquals(25, goal(profile, CampaignGoal.PERFECT_25).current)
        assertEquals(profile, CampaignAchievements.reconcile(profile))
    }
    @Test fun `checkpoint awards badge and clears match in one profile result`() {
        val saved = SavedMatch("level-0001", Difficulty.EASY)
        val after = ProfileReducer.checkpoint(Profile(savedMatch = saved), 1, saved, LevelOutcome.Won(3), 4, 0)
        assertNull(after.savedMatch)
        assertEquals(listOf("campaign.first_win.easy"), after.achievements.map { it.id })
        assertEquals(1L, after.stats(Difficulty.EASY).wins)
    }
    @Test fun `store backfill persists once across independent instances and backup round trip`() {
        val dir = Files.createTempDirectory("achievement-test").toFile()
        try {
            val store = ProfileStore(dir); val old = legacy(10); store.save(old)
            val unlocked = store.reconcileAchievements()
            assertEquals(old.revision + 1, unlocked.revision)
            assertEquals(unlocked, ProfileStore(dir).reconcileAchievements())
            assertEquals(unlocked, ProfileBackup.decode(ProfileBackup.encode(unlocked)))
            assertEquals(unlocked, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `import reconciles old progress and campaign reset clears campaign badges`() {
        val dir = Files.createTempDirectory("achievement-test").toFile()
        try {
            val store = ProfileStore(dir); store.importProfile(legacy(25))
            assertTrue(goal(store.load(), CampaignGoal.PERFECT_25).unlocked)
            assertTrue(store.resetProgress().achievements.isEmpty())
            assertEquals(0, goal(store.reconcileAchievements(), CampaignGoal.PERFECT_25).current)
        } finally { dir.deleteRecursively() }
    }
}
