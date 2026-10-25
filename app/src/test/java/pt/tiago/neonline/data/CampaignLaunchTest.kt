package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class CampaignLaunchTest {
    private val levels=(1..3).map { CatalogLevel("level-%04d".format(it),it,"$it.json","0".repeat(64)) }
    @Test fun `fresh published unlocked level introduces instead of starting and locked or missing refuse`() {
        assertEquals(CampaignLaunchAction.INTRO,CampaignLaunch.action(Profile(),levels,1,Difficulty.EASY,null))
        assertEquals(CampaignLaunchAction.LOCKED,CampaignLaunch.action(Profile(),levels,2,Difficulty.EASY,null))
        assertEquals(CampaignLaunchAction.UNAVAILABLE,CampaignLaunch.action(Profile(),levels,4,Difficulty.EASY,null))
    }
    @Test fun `only validated matching save resumes directly even with incomplete imported progress`() {
        val saved=SavedMatch("level-0002",Difficulty.HARD)
        assertEquals(CampaignLaunchAction.RESUME,CampaignLaunch.action(Profile(savedMatch=saved),levels,2,Difficulty.HARD,saved))
        assertEquals(CampaignLaunchAction.LOCKED,CampaignLaunch.action(Profile(savedMatch=saved),levels,2,Difficulty.HARD,null))
        assertEquals(CampaignLaunchAction.INTRO,CampaignLaunch.action(Profile(savedMatch=saved),levels,1,Difficulty.EASY,saved))
    }
    @Test fun `replacement protects any meaningful journal including unresumable same level and boss history`() {
        assertTrue(CampaignLaunch.needsReplacement(SavedMatch("level-0001",Difficulty.EASY,moves=listOf("drop:0"))))
        assertFalse(CampaignLaunch.needsReplacement(null))
        assertFalse(CampaignLaunch.needsReplacement(SavedMatch("level-0001",Difficulty.EASY)))
        assertTrue(CampaignLaunch.needsReplacement(SavedMatch("level-0001",Difficulty.EASY,steps=listOf(SavedPly("drop",0,aiStateHex="0123456789abcdef",afterHash="0".repeat(64))))))
        assertTrue(CampaignLaunch.needsReplacement(SavedMatch("level-0050",Difficulty.EASY,bossHistory=listOf(SavedBossRound("a","b",emptyList(),0,0)))))
    }
}
