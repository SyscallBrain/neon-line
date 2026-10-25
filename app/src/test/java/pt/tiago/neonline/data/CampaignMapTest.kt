package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelKind

class CampaignMapTest {
    private fun levels(range: IntRange=1..4)=range.map { CampaignMapLevel("level-%04d".format(it),it,Difficulty.EASY,"N$it","N$it",
        if(it==50)LevelKind.BOSS else LevelKind.PUZZLE,2,null,null,"sparky",false,false,null,null,null,false,0,4) }
    @Test fun `fresh path locks future levels and highlights first real uncompleted level`() {
        val nodes=CampaignMap.nodes(Profile(),levels(),Difficulty.EASY,null,false,null)
        assertEquals(4,nodes.size); assertTrue(nodes[0].current); assertTrue(nodes[0].unlocked)
        assertTrue(nodes.drop(1).all { !it.unlocked && !it.current }); assertTrue(nodes.all { it.stars==0 })
    }
    @Test fun `focus follows actual completion not imported highest reached counter`() {
        var profile=Profile(progressByDifficulty=mapOf(Difficulty.EASY to DifficultyProgress(highestReached=4)))
        assertEquals(1,CampaignMap.nodes(profile,levels(),Difficulty.EASY,null,false,null).single { it.current }.level.ordinal)
        profile=ProfileReducer.recordWin(profile,1,Difficulty.EASY,2,2,0)
        assertEquals(2,CampaignMap.nodes(profile,levels(),Difficulty.EASY,null,false,null).single { it.current }.level.ordinal)
    }
    @Test fun `validated visible resume wins focus and bypasses inconsistent progress without unlocking other nodes`() {
        val saved=SavedMatch("level-0003",Difficulty.EASY)
        val nodes=CampaignMap.nodes(Profile(),levels(),Difficulty.EASY,null,false,saved)
        assertTrue(nodes[2].unlocked); assertTrue(nodes[2].resuming); assertTrue(nodes[2].current)
        assertFalse(nodes[1].unlocked); assertFalse(nodes[3].unlocked)
        val hard=CampaignMap.nodes(Profile(),levels().map { it.copy(difficulty=Difficulty.HARD) },Difficulty.HARD,null,false,saved)
        assertEquals(1,hard.single { it.current }.level.ordinal)
        assertTrue(hard.none { it.resuming }); assertFalse(hard[2].unlocked)
    }
    @Test fun `missing stars filter chooses improvable focus and never shows locked nodes`() {
        var profile=ProfileReducer.recordWin(Profile(),1,Difficulty.EASY,3,2,0)
        profile=ProfileReducer.recordWin(profile,2,Difficulty.EASY,1,2,0)
        val nodes=CampaignMap.nodes(profile,levels(),Difficulty.EASY,null,true,null)
        assertEquals(listOf(2,3),nodes.map { it.level.ordinal }); assertEquals(3,nodes.single { it.current }.level.ordinal)
        assertEquals(1,nodes[0].stars)
    }
    @Test fun `stop filter and chapter gate agree with launch policy even for imported out of order completion`() {
        val profile=Profile(progressByDifficulty=mapOf(Difficulty.EASY to DifficultyProgress(completedVariantIds=setOf("level-0051:easy"))))
        val nodes=CampaignMap.nodes(profile,levels(51..53),Difficulty.EASY,2,false,null)
        assertTrue(nodes.all { !it.unlocked && !it.current })
        val unlocked=ProfileReducer.recordWin(profile,50,Difficulty.EASY,3,2,0)
        assertEquals(52,CampaignMap.nodes(unlocked,levels(51..53),Difficulty.EASY,2,false,null).single { it.current }.level.ordinal)
        assertTrue(CampaignMap.nodes(unlocked,levels(51..53),Difficulty.EASY,1,false,null).isEmpty())
    }
    @Test fun `sorted unique valid nodes and all completed focus stay bounded to the published list`() {
        val metadata=levels(1..2).reversed()+levels(1..1)+levels(0..0)+levels(601..601)
        var profile=ProfileReducer.recordWin(Profile(),1,Difficulty.EASY,3,2,0)
        profile=ProfileReducer.recordWin(profile,2,Difficulty.EASY,3,2,0)
        val nodes=CampaignMap.nodes(profile,metadata,Difficulty.EASY,null,false,null)
        assertEquals(listOf(1,2),nodes.map { it.level.ordinal }); assertEquals(2,nodes.single { it.current }.level.ordinal)
        assertTrue(CampaignMap.nodes(profile,metadata,Difficulty.EASY,null,true,null).isEmpty())
    }
}
