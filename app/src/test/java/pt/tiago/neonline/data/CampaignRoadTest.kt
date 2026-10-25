package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class CampaignRoadTest {
    private fun levels(range: IntRange) = range.map { CatalogLevel("level-%04d".format(it),it,"levels/$it.json","0".repeat(64)) }
    @Test fun `fresh road has twelve stops and only published unlocked first stop opens`() {
        val road=CampaignRoad.summarize(Profile(),levels(1..50),Difficulty.EASY,1)
        assertEquals(12,road.size); assertEquals((1..12).toList(),road.map { it.number })
        assertTrue(road[0].canOpen); assertTrue(road[0].current); assertEquals(50,road[0].availableLevels)
        assertTrue(road.drop(1).all { it.status==RoadStopStatus.UNAVAILABLE && !it.canOpen && !it.current })
        assertTrue(road.all { it.litSegments.isEmpty() && it.stars==0 })
    }
    @Test fun `segments follow exact published completed ordinals in the chosen difficulty`() {
        val progress=DifficultyProgress(completedVariantIds=setOf("level-0001:easy","level-0050:easy","level-0051:easy"),
            bestByVariantId=mapOf("level-0001:easy" to LevelBest(3),"level-0050:easy" to LevelBest(2),"level-0051:easy" to LevelBest(3)))
        val profile=Profile(progressByDifficulty=mapOf(Difficulty.EASY to progress))
        val road=CampaignRoad.summarize(profile,levels(1..100),Difficulty.EASY,51)
        assertEquals(setOf(0,49),road[0].litSegments); assertEquals(5,road[0].stars)
        assertEquals(setOf(0),road[1].litSegments); assertTrue(road[1].current); assertTrue(road[1].canOpen)
        val hard=CampaignRoad.summarize(profile,levels(1..100),Difficulty.HARD,51)
        assertTrue(hard.all { it.litSegments.isEmpty() }); assertEquals(RoadStopStatus.LOCKED,hard[1].status)
        assertTrue(hard[0].current)
    }
    @Test fun `missing published levels are unavailable even if imported progress says unlocked`() {
        val profile=ProfileReducer.recordWin(Profile(),50,Difficulty.EASY,3,1,0)
        val road=CampaignRoad.summarize(profile,levels(1..50),Difficulty.EASY,51)
        assertEquals(RoadStopStatus.UNAVAILABLE,road[1].status); assertFalse(road[1].current)
        assertFalse(CampaignRoad.canOpen(profile,levels(1..50),Difficulty.EASY,2))
        assertFalse(CampaignRoad.canOpen(profile,levels(1..50),Difficulty.EASY,0))
        assertFalse(CampaignRoad.canOpen(profile,levels(1..50),Difficulty.EASY,13))
    }
    @Test fun `partial catalog counts only unique valid published ids and preserves segment offsets`() {
        val levels=levels(3..4)+levels(3..3)+levels(0..0)+levels(601..601)
        val profile=ProfileReducer.recordWin(Profile(),1,Difficulty.EASY,3,1,0)
        val stop=CampaignRoad.summarize(profile,levels,Difficulty.EASY,3)[0]
        assertEquals(2,stop.availableLevels); assertEquals(6,stop.maxStars)
        assertTrue(stop.litSegments.isEmpty()); assertEquals(0,stop.stars)
        assertEquals(listOf(3,4),CampaignRoad.levelsForStop(levels,1).map { it.ordinal })
        assertTrue(CampaignRoad.levelsForStop(levels,13).isEmpty())
    }
    @Test fun `published second stop requires previous boss and does not trust reached counter`() {
        val profile=Profile(progressByDifficulty=mapOf(Difficulty.EASY to DifficultyProgress(highestReached=100)))
        val road=CampaignRoad.summarize(profile,levels(1..100),Difficulty.EASY,51)
        assertEquals(RoadStopStatus.LOCKED,road[1].status); assertFalse(road[1].canOpen)
        assertTrue(CampaignRoad.canOpen(ProfileReducer.recordWin(profile,50,Difficulty.EASY,1,1,0),levels(1..100),Difficulty.EASY,2))
    }
    @Test fun `empty catalog has no current stop and no playable signs`() {
        val road=CampaignRoad.summarize(Profile(),emptyList(),Difficulty.HARD,600)
        assertEquals(12,road.size); assertTrue(road.all { !it.canOpen && !it.current && it.maxStars==0 })
    }
}
