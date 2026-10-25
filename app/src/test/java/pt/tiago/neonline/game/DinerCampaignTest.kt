package pt.tiago.neonline.game

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class DinerCampaignTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun cleanup(){Dispatchers.resetMain()}
    private fun root()=listOf(File("../content"),File("content")).first { File(it,"catalog.json").isFile }

    @Test fun `published campaign witnesses through Bubble Laundromat win through the actual app rival and objectives`() = runTest(dispatcher) {
        var driveInDrop = false
        var driveInPop = false
        var laundromatPop = false
        for(ordinal in (51..99) + (101..149) + (151..199) + (201..249) + (251..299) + (301..349)) for(difficulty in Difficulty.entries) {
            val id="level-%04d".format(ordinal)
            val stop = when { ordinal <= 100 -> "stop-02"; ordinal <= 150 -> "stop-03"; ordinal <= 200 -> "stop-04"; ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; else -> "stop-07" }
            val level=Json.decodeFromString(LevelFileDto.serializer(),File(root(),"levels/$stop/$id.json").readText())
            val variant=level.variants.getValue(difficultyKey(difficulty))
            val certificate=Json.decodeFromString(CertificateDto.serializer(),File(root(),"certificates/$id/${difficultyKey(difficulty)}.json").readText())
            val ai=variant.ai.let { AiProfile(it.maxDepth,it.nodeBudget,it.attack,it.defense,it.center,it.trap,it.blunderRate,it.mirror,it.prefersPop,it.blunderMayMiss) }
            val objective=LevelObjective(variant.kindEnum(),winInMoves=variant.kind.winInMoves,surviveAiMoves=variant.kind.surviveAiMoves,
                shapeRule=variant.shapeRule?.let { ShapeRule.valueOf(it.uppercase()) },starsTwo=variant.stars.two,starsThree=variant.stars.three)
            var profile=Profile()
            val game=GameViewModel(variant.toState(id,difficulty),objective,ai,hintsAvailable=variant.hints,
                animationEnabled=false,aiDelayMs=0,searchDispatcher=dispatcher,tutorial=level.tutorial,
                onCheckpoint={saved,outcome,moves->profile=ProfileReducer.checkpoint(profile,ordinal,saved,outcome,moves,0,variant.rival)})
            if (ordinal in 211..249) {
                driveInDrop = driveInDrop || certificate.steps.any { it.move.startsWith("Drop(") }
                driveInPop = driveInPop || certificate.steps.any { it.move.startsWith("Pop(") }
            }
            if (ordinal in 251..299) laundromatPop = laundromatPop || certificate.steps.any { it.move.startsWith("Pop(") }
            val humanSteps=certificate.steps.filterIndexed { index,_->index%2==0 }
            for(step in humanSteps) {
                assertNull("$id/$difficulty ended before certificate",game.ui.value.outcome)
                val action=Regex("(Drop|Pop)\\(col=(\\d+).*").matchEntire(step.move) ?: error("Bad move ${step.move}")
                val col=action.groupValues[2].toInt()
                val move=if(action.groupValues[1]=="Pop") Move.Pop(col) else Move.Drop(col)
                game.play(move);runCurrent()
            }
            assertEquals("$id/$difficulty",LevelOutcome.Won(3),game.ui.value.outcome)
            assertEquals(certificate.steps.last().afterStateHash,game.snapshot().stateHash)
            assertEquals(1L,profile.stats(difficulty).wins)
            assertNull(profile.savedMatch)
            assertTrue(ProfileReducer.variantId(ordinal,difficulty) in profile.progress(difficulty).completedVariantIds)
            game.pause()
            assertEquals(1L,profile.stats(difficulty).wins)
        }
        assertTrue("Drive-In certificates should include Drop", driveInDrop)
        assertTrue("Pop Out levels should exercise a certified Pop", driveInPop)
        assertTrue("Laundromat Pop Out certificates should exercise Pop", laundromatPop)
    }

    @Test fun `Diner opens after boss fifty independently per difficulty and reveals Dolores on encounter`() {
        val levels=(1..200).map { CatalogLevel("level-%04d".format(it),it,"unused","unused") }
        val fresh=Profile()
        assertFalse(CampaignRoad.canOpen(fresh,levels,Difficulty.EASY,2))
        val won=ProfileReducer.recordWin(fresh,50,Difficulty.EASY,1,10,0)
        assertTrue(CampaignRoad.canOpen(won,levels,Difficulty.EASY,2))
        assertFalse(CampaignRoad.canOpen(won,levels,Difficulty.HARD,2))
        assertEquals(50,CampaignRoad.levelsForStop(levels,2).size)
        assertTrue(ProfileReducer.isUnlocked(won,51,Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(won,52,Difficulty.EASY))
        val through60=(51..60).fold(won) { profile,ordinal -> ProfileReducer.recordWin(profile,ordinal,Difficulty.EASY,1,10,0) }
        assertTrue(ProfileReducer.isUnlocked(through60,61,Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(through60,62,Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(through60,61,Difficulty.HARD))
        val through70=(61..70).fold(through60) { profile,ordinal -> ProfileReducer.recordWin(profile,ordinal,Difficulty.EASY,1,10,0) }
        assertTrue(ProfileReducer.isUnlocked(through70,71,Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(through70,72,Difficulty.EASY))
        val through80=(71..80).fold(through70) { profile,ordinal -> ProfileReducer.recordWin(profile,ordinal,Difficulty.EASY,1,10,0) }
        assertTrue(ProfileReducer.isUnlocked(through80,81,Difficulty.EASY))
        assertFalse(ProfileReducer.isUnlocked(through80,82,Difficulty.EASY))
        val through99=(81..99).fold(through80) { profile,ordinal -> ProfileReducer.recordWin(profile,ordinal,Difficulty.EASY,1,10,0) }
        assertTrue(ProfileReducer.isUnlocked(through99,100,Difficulty.EASY))
        assertFalse(ProfileReducer.isStopUnlocked(through99,3,Difficulty.EASY))
        assertFalse(CampaignRoad.canOpen(through99,levels,Difficulty.EASY,3))
        val throughBoss=ProfileReducer.recordWin(through99,100,Difficulty.EASY,1,10,0)
        assertTrue(ProfileReducer.isStopUnlocked(throughBoss,3,Difficulty.EASY))
        assertTrue(CampaignRoad.canOpen(throughBoss,levels,Difficulty.EASY,3))
        assertEquals((101..150).toList(),CampaignRoad.levelsForStop(levels,3).map { it.ordinal })
        assertFalse(ProfileReducer.isStopUnlocked(throughBoss,3,Difficulty.MEDIUM))
        val throughMotelBoss=ProfileReducer.recordWin(throughBoss,150,Difficulty.EASY,1,10,0)
        assertTrue(ProfileReducer.isStopUnlocked(throughMotelBoss,4,Difficulty.EASY))
        assertFalse(ProfileReducer.isStopUnlocked(throughMotelBoss,4,Difficulty.MEDIUM))
        assertEquals((151..200).toList(),CampaignRoad.levelsForStop(levels,4).map { it.ordinal })
    }
}
