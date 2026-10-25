package pt.tiago.neonline.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class RushAchievementsTest {
    @Test fun `twenty five in one run earns only its run difficulty atomically`() {
        for (difficulty in Difficulty.entries) {
            val before = PuzzleRush.record(Profile(), difficulty, 24)
            assertTrue(before.achievements.isEmpty())
            val after = PuzzleRush.record(before, difficulty, 25)
            val goal = RushAchievements.progress(after, difficulty)
            assertTrue(goal.unlocked)
            assertEquals(25, goal.current)
            assertEquals(1f, goal.fraction)
            assertEquals(before.revision + 1, after.revision)
            assertEquals(listOf(Achievement(goal.id, after.revision)), after.achievements)
            Difficulty.entries.filter { it != difficulty }.forEach {
                assertFalse(RushAchievements.progress(after, it).unlocked)
            }
            assertEquals(after, PuzzleRush.record(after, difficulty, 25))
            assertEquals(after, PuzzleRush.record(after, difficulty, 10))
            assertEquals(after.achievements, PuzzleRush.record(after, difficulty, 30).achievements)
        }
    }

    @Test fun `separate runs do not accumulate and deadline cannot provide twenty fifth solve`() {
        var profile = PuzzleRush.record(Profile(), Difficulty.MEDIUM, 13)
        profile = PuzzleRush.record(profile, Difficulty.MEDIUM, 12)
        assertEquals(13, RushAchievements.progress(profile, Difficulty.MEDIUM).current)
        assertEquals(13f / 25f, RushAchievements.progress(profile, Difficulty.MEDIUM).fraction)
        assertTrue(profile.achievements.isEmpty())
        val run = RushRun(Difficulty.HARD, "0123456789abcdef", 1000, solved = 24, attempt = 24)
        val expired = PuzzleRush.complete(run, 24, true, 121000)
        assertEquals(24, expired.solved)
        assertTrue(PuzzleRush.record(Profile(), expired.difficulty, expired.solved).achievements.isEmpty())
    }

    @Test fun `legacy scores reconcile together with campaign in one revision once`() {
        val completed = ProfileReducer.variantId(1, Difficulty.EASY)
        val old = Profile(revision = 7, rushBest = mapOf(Difficulty.HARD to 25),
            progressByDifficulty = mapOf(Difficulty.EASY to DifficultyProgress(completedVariantIds = setOf(completed))),
            achievements = listOf(Achievement("future-badge", 3)))
        val dir = Files.createTempDirectory("rush-badges-legacy").toFile()
        try {
            ProfileStore(dir).save(old)
            val updated = ProfileStore(dir).reconcileAchievements()
            assertEquals(8L, updated.revision)
            assertEquals(3, updated.achievements.size)
            assertTrue(RushAchievements.progress(updated, Difficulty.HARD).unlocked)
            assertTrue(updated.achievements.contains(Achievement("future-badge", 3)))
            assertTrue(updated.achievements.filter { it.id != "future-badge" }.all { it.unlockedAtRevision == 8L })
            assertEquals(updated, ProfileStore(dir).reconcileAchievements())
            assertEquals(updated, ProfileStore(dir).load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `backup import reset and reload preserve rush badges with independent journals`() {
        val dir = Files.createTempDirectory("rush-badges-backup").toFile()
        try {
            val original = PuzzleRush.record(Profile(savedMatch = SavedMatch("level-0002", Difficulty.EASY),
                localSession = SavedLocalSession(wins1 = 3)), Difficulty.EASY, 25)
            val backup = ProfileBackup.decode(ProfileBackup.encode(original))
            assertEquals(original, backup)
            val store = ProfileStore(dir)
            store.importProfile(backup)
            val imported = ProfileStore(dir).load()
            assertEquals(original.achievements, imported.achievements)
            assertEquals(original.savedMatch, imported.savedMatch)
            val reset = store.resetProgress()
            assertEquals(original.rushBest, reset.rushBest)
            assertEquals(original.achievements, reset.achievements)
            assertEquals(original.localSession, reset.localSession)
            assertNull(reset.savedMatch)
            assertEquals(reset, ProfileStore(dir).reconcileAchievements())
            // Recorded badges remain earned even if a legacy record is missing.
            store.save(reset.copy(rushBest = emptyMap()))
            val retained = store.resetProgress()
            assertEquals(original.achievements, retained.achievements)
            assertTrue(RushAchievements.progress(retained, Difficulty.EASY).unlocked)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `import backfills old rush best and record can repair a missing badge without improvement`() {
        val old = Profile(revision = 5, rushBest = mapOf(Difficulty.MEDIUM to 40))
        val repaired = PuzzleRush.record(old, Difficulty.MEDIUM, 1)
        assertEquals(6L, repaired.revision)
        assertTrue(RushAchievements.progress(repaired, Difficulty.MEDIUM).unlocked)
        assertEquals(old.rushBest, repaired.rushBest)
        val dir = Files.createTempDirectory("rush-badges-import").toFile()
        try {
            ProfileStore(dir).importProfile(ProfileBackup.decode(ProfileBackup.encode(old)))
            val imported = ProfileStore(dir).load()
            assertEquals(6L, imported.revision)
            assertTrue(RushAchievements.progress(imported, Difficulty.MEDIUM).unlocked)
        } finally { dir.deleteRecursively() }
    }
}
