package pt.tiago.neonline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class ProfileReducerTest {

    @Test
    fun `a win with one star unlocks the next level`() {
        var profile = Profile()
        profile = ProfileReducer.recordWin(profile, levelOrdinal = 1, difficulty = Difficulty.EASY, stars = 1, moves = 20, hintsUsed = 2)
        assertTrue(ProfileReducer.isUnlocked(profile, 2, Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(profile, 3, Difficulty.EASY))
        assertEquals(2, profile.continueOrdinal(Difficulty.EASY))
    }

    @Test
    fun `a better result never lowers the recorded best`() {
        var profile = Profile()
        profile = ProfileReducer.recordWin(profile, 5, Difficulty.MEDIUM, stars = 3, moves = 8, hintsUsed = 0)
        profile = ProfileReducer.recordWin(profile, 5, Difficulty.MEDIUM, stars = 1, moves = 20, hintsUsed = 3)
        val best = profile.progress(Difficulty.MEDIUM).bestByVariantId[ProfileReducer.variantId(5, Difficulty.MEDIUM)]!!
        assertEquals(3, best.stars)
        assertEquals(8, best.fewestMoves)
        assertEquals(0, best.hintsUsed)
        assertEquals(2, profile.revision)
    }

    @Test
    fun `each difficulty keeps its own progress`() {
        var profile = Profile()
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.EASY, stars = 3, moves = 9, hintsUsed = 0)
        assertTrue(ProfileReducer.isUnlocked(profile, 2, Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(profile, 2, Difficulty.MEDIUM))
        assertEquals(3, profile.totalStars())
    }

    @Test
    fun `stars add up across difficulties for the theme unlocks`() {
        var profile = Profile()
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.EASY, stars = 3, moves = 9, hintsUsed = 0)
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.MEDIUM, stars = 2, moves = 12, hintsUsed = 1)
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.HARD, stars = 1, moves = 18, hintsUsed = 2)
        assertEquals(6, profile.totalStars())
        assertEquals("neon_road", profile.unlockedSkin())
    }

    @Test
    fun `the first stop is open and later stops need their boss`() {
        var profile = Profile()
        assertTrue(ProfileReducer.isStopUnlocked(profile, 1, Difficulty.EASY))
        assertFalse(ProfileReducer.isStopUnlocked(profile, 2, Difficulty.EASY))
        profile = ProfileReducer.recordWin(profile, 50, Difficulty.EASY, stars = 3, moves = 10, hintsUsed = 0)
        assertTrue(ProfileReducer.isStopUnlocked(profile, 2, Difficulty.EASY))
        assertFalse(ProfileReducer.isStopUnlocked(profile, 3, Difficulty.EASY))
    }

    @Test
    fun `losses and draws only move the statistics`() {
        var profile = Profile()
        profile = ProfileReducer.recordLoss(profile, Difficulty.HARD, moves = 14)
        profile = ProfileReducer.recordDraw(profile, Difficulty.HARD)
        val stats = profile.stats(Difficulty.HARD)
        assertEquals(1L, stats.losses)
        assertEquals(1L, stats.draws)
        assertEquals(14L, stats.moves)
        assertTrue(profile.progress(Difficulty.HARD).completedVariantIds.isEmpty())
    }

    @Test
    fun `the saved match is carried on the profile`() {
        val profile = ProfileReducer.withSavedMatch(
            Profile(),
            SavedMatch(levelId = "level-0007", difficulty = Difficulty.MEDIUM, moves = listOf("Drop(col=3, kind=NORMAL)")),
        )
        assertEquals("level-0007", profile.savedMatch?.levelId)
        assertEquals(1, profile.savedMatch?.moves?.size)
    }
}
