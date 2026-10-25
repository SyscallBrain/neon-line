package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

class CampaignStatisticsTest {
    private val levels=(1..3).map { CatalogLevel("level-%04d".format(it),it,"unused","unused") }
    private fun summary(profile: Profile,difficulty: Difficulty=Difficulty.EASY)=CampaignStatistics.summarize(profile,levels,difficulty)

    @Test fun `an empty campaign has finite progress and no invented win rate`() {
        val result=CampaignStatistics.summarize(Profile(),emptyList(),Difficulty.EASY)
        assertEquals(0,result.maxStars)
        assertEquals(0f,result.starProgress)
        assertNull(result.winRate)
        assertFalse(result.hasLegacyGaps)
        assertEquals(listOf(1),CampaignStatistics.levelsToImprove(Profile(),levels,Difficulty.EASY).map { it.ordinal })
    }

    @Test fun `replays count as results while packaged progress counts each level only once`() {
        var profile=ProfileReducer.recordWin(Profile(),1,Difficulty.EASY,1,8,1)
        profile=ProfileReducer.recordWin(profile,1,Difficulty.EASY,3,5,0)
        profile=ProfileReducer.recordWin(profile,100,Difficulty.EASY,3,10,0)
        val result=summary(profile)
        assertEquals(1,result.completedLevels)
        assertEquals(1,result.threeStarLevels)
        assertEquals(3,result.stars)
        assertEquals(9,result.maxStars)
        assertEquals(3L,result.stats.wins)
        assertEquals(23L,result.stats.moves)
        assertEquals(100.0,result.winRate!!,0.001)
        assertEquals(result,CampaignStatistics.summarize(profile,levels+levels,Difficulty.EASY))
    }

    @Test fun `filters preserve unlocks and separate difficulties while including unfinished unlocked levels`() {
        var profile=ProfileReducer.recordWin(Profile(),1,Difficulty.EASY,3,5,0)
        assertEquals(listOf(2),CampaignStatistics.levelsToImprove(profile,levels,Difficulty.EASY).map { it.ordinal })
        assertEquals(listOf(1),CampaignStatistics.levelsToImprove(profile,levels,Difficulty.HARD).map { it.ordinal })
        profile=ProfileReducer.recordWin(profile,2,Difficulty.EASY,1,8,1)
        assertEquals(listOf(2,3),CampaignStatistics.levelsToImprove(profile,levels.reversed(),Difficulty.EASY).map { it.ordinal })
        profile=ProfileReducer.recordWin(profile,3,Difficulty.EASY,3,6,0)
        profile=ProfileReducer.recordWin(profile,2,Difficulty.EASY,3,5,0)
        assertTrue(CampaignStatistics.levelsToImprove(profile,levels,Difficulty.EASY).isEmpty())
        assertEquals(9,summary(profile).stars)
        assertEquals(0,summary(profile,Difficulty.HARD).stars)
    }

    @Test fun `finished losses and draws retain their own moves and hints without unlocking levels`() {
        val saved=SavedMatch("level-0001",Difficulty.EASY)
        var profile=ProfileReducer.checkpoint(Profile(),1,saved,LevelOutcome.Lost("rival_line"),7,2)
        profile=ProfileReducer.checkpoint(profile,1,saved,LevelOutcome.Lost("draw"),4,1)
        val result=summary(profile)
        assertEquals(1L,result.stats.losses)
        assertEquals(1L,result.stats.draws)
        assertEquals(11L,result.stats.moves)
        assertEquals(3L,result.stats.hintsUsed)
        assertTrue(result.stats.allResultsTracked)
        assertFalse(result.hasLegacyGaps)
        assertEquals(0,result.completedLevels)
        assertNull(profile.savedMatch)
    }

    @Test fun `legacy gaps are preserved and disclosed while new counters survive a fresh store`() {
        val old=Json.decodeFromString(Profile.serializer(),"""{"statsByDifficulty":{"EASY":{"losses":1,"draws":1,"moves":12,"hintsUsed":2}}}""")
        assertTrue(summary(old).hasLegacyGaps)
        val updated=ProfileReducer.recordWin(old,1,Difficulty.EASY,3,5,0)
        assertTrue(summary(updated).hasLegacyGaps)
        assertEquals(17L,updated.stats(Difficulty.EASY).moves)
        val directory=Files.createTempDirectory("statistics-profile").toFile()
        try {
            val fresh=ProfileReducer.recordLoss(Profile(),Difficulty.HARD,9,1)
            ProfileStore(directory).save(fresh)
            val restored=ProfileStore(directory).load()
            assertEquals(fresh.statsByDifficulty,restored.statsByDifficulty)
            assertFalse(summary(restored,Difficulty.HARD).hasLegacyGaps)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `legacy wins have complete counters and do not gain a false warning after a new loss`() {
        val old=Profile(statsByDifficulty=mapOf(Difficulty.EASY to DifficultyStats(wins=1,moves=9,hintsUsed=1)))
        val updated=ProfileReducer.recordLoss(old,Difficulty.EASY,4,2)
        assertTrue(updated.stats(Difficulty.EASY).allResultsTracked)
        assertFalse(summary(updated).hasLegacyGaps)
        assertEquals(3L,updated.stats(Difficulty.EASY).hintsUsed)
        assertFalse(Validator.isValid(Profile(statsByDifficulty=mapOf(Difficulty.EASY to DifficultyStats(hintsUsed=-1)))))
    }
}
