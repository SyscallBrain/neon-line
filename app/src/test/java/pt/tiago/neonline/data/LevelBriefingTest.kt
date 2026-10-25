package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.kindEnum
import pt.tiago.neonline.LevelInfo
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.serialization.StateHasher

class LevelBriefingTest {
    private fun info(kind: LevelKind = LevelKind.DUEL) = LevelInfo("level-0001",1,"First","Primeiro",kind,
        if(kind==LevelKind.PUZZLE) 3 else null,if(kind==LevelKind.DEFENSE) 5 else null,"sparky",10,7,7,6,4,false,null,null,null,3,null)
    private fun state() = GameState.initial("level-0001",Difficulty.EASY,2,"0123456789abcdef",7,6,4,List(42){true},List(42){null})
    @Test fun `briefing faithfully derives rules starter resources and inventory without changing state`() {
        val state=state().copy(popOut=true,rotateEvery=6,fadeAfter=3,moveClockSec=10,toMove=Player.P2,
            inventory=Player.entries.associateWith { mapOf(PieceKind.BOMB to 1,PieceKind.LOCK to 2) })
        val hash=StateHasher.hash(state)
        val brief=LevelBriefing.from(info(),state)
        assertEquals(1,brief.ordinal); assertEquals(Difficulty.EASY,brief.difficulty)
        assertEquals("Primeiro",brief.titlePt); assertEquals("First",brief.titleEn)
        assertEquals(Player.P2,brief.starter); assertEquals(3,brief.hints); assertEquals(3,brief.undos)
        assertEquals(state.inventory,brief.inventory); assertEquals(0,brief.blockedCells)
        assertTrue(brief.popOut); assertEquals(6,brief.rotateEvery); assertEquals(3,brief.fadeAfter); assertEquals(10,brief.clockSeconds)
        assertEquals("Sparky",brief.rivalName); assertEquals(hash,StateHasher.hash(state))
    }
    @Test fun `star policy matches evaluator kind including boss winning moves and puzzle hints`() {
        val policies=mapOf(LevelKind.DUEL to BriefingStars.MOVES,LevelKind.SHAPE to BriefingStars.MOVES,
            LevelKind.PUZZLE to BriefingStars.HINTS,LevelKind.BOSS to BriefingStars.BOSS_WINNING_MOVES,LevelKind.DEFENSE to BriefingStars.DEFENSE)
        for((kind,policy) in policies) {
            val brief=LevelBriefing.from(info(kind),state())
            assertEquals(policy,brief.stars); assertEquals(10,brief.objective.starsTwo); assertEquals(7,brief.objective.starsThree)
        }
    }
    @Test fun `briefing counts real blocked cells and preserves shape with no easy undo on hard`() {
        val state=state().copy(difficulty=Difficulty.HARD,playable=List(42){it!=41},shapeRule=ShapeRule.DIAGONAL_ONLY)
        val brief=LevelBriefing.from(info(LevelKind.SHAPE),state)
        assertEquals(1,brief.blockedCells); assertEquals(0,brief.undos); assertEquals(state.shapeRule,brief.objective.shapeRule)
    }
    @Test fun `mismatched level metadata is refused and unknown rival uses actual id`() {
        assertTrue(runCatching { LevelBriefing.from(info(),state().copy(levelId="level-0002")) }.isFailure)
        assertTrue(runCatching { LevelBriefing.from(info().copy(width=9),state()) }.isFailure)
        assertEquals("future-rival",LevelBriefing.from(info().copy(rival="future-rival"),state()).rivalName)
    }
    @Test fun `all published variants provide truthful difficulty specific briefing metadata`() {
        val root=listOf(java.io.File("../content"),java.io.File("content")).first { java.io.File(it,"catalog.json").isFile }
        var count=0
        for(file in java.io.File(root,"levels").walkTopDown().filter { it.isFile && it.extension=="json" }) {
            val level=kotlinx.serialization.json.Json.decodeFromString(pt.tiago.neonline.core.content.LevelFileDto.serializer(),file.readText())
            for(difficulty in Difficulty.entries) {
                val variant=level.variants.getValue(difficulty.name.lowercase())
                val state=with(variant) { toState(level.id,difficulty) }
                val info=LevelInfo(level.id,level.ordinal,level.titleEn,level.titlePt,variant.kindEnum(),variant.kind.winInMoves,
                    variant.kind.surviveAiMoves,variant.rival,variant.stars.two,variant.stars.three,variant.width,variant.height,variant.connect,
                    variant.rules.popOut,variant.rules.rotateEvery,variant.rules.fadeAfter,variant.rules.moveClockSec,variant.hints,null)
                val brief=LevelBriefing.from(info,state)
                assertEquals(variant.hints,brief.hints); assertEquals(difficulty,brief.difficulty)
                assertEquals(variant.stars.three,brief.objective.starsThree)
                assertEquals(variant.stars.two,brief.objective.starsTwo)
                assertEquals(state.toMove,brief.starter)
                assertTrue(brief.objective.starsThree!!>0)
                assertTrue(brief.objective.starsTwo!!>=brief.objective.starsThree!!)
                count++
            }
        }
        assertEquals(1_800,count)
    }
}
