package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelKind

class PuzzleRushTest {
    private fun run(d: Difficulty = Difficulty.MEDIUM) = RushRun(d, "0123456789abcdef", 1000L)
    private fun pool() = listOf(RushPuzzle(1,Difficulty.EASY,1)) + (2..5).map { RushPuzzle(it,Difficulty.EASY,2) } + (6..13).map { RushPuzzle(it,Difficulty.HARD,3) }
    @Test fun `difficulty controls duration and errors independently of source goal`() {
        assertEquals(listOf(240000L,180000L,120000L),Difficulty.entries.map { PuzzleRush.durationMs(it) })
        assertEquals(listOf(5,3,3),Difficulty.entries.map { PuzzleRush.errorLimit(it) })
        assertEquals(listOf(1,2,2,2,2,3,3), (0..6).map { PuzzleRush.stage(it) })
        assertEquals(180000L,PuzzleRush.remainingMs(run(),900L))
        assertEquals(179000L,PuzzleRush.remainingMs(run(),2000L))
        assertEquals(0L,PuzzleRush.remainingMs(run(),Long.MAX_VALUE))
    }
    @Test fun `score advances goals and mistakes advance attempt without score`() {
        var r = run()
        r=PuzzleRush.complete(r,0,true,2000)
        assertEquals(1,r.solved); assertEquals(1,r.attempt); assertEquals(0,r.errors)
        assertEquals(2,PuzzleRush.choose(pool(),r)!!.moves)
        r=PuzzleRush.complete(r,1,false,3000)
        assertEquals(1,r.solved); assertEquals(2,r.attempt); assertEquals(1,r.errors)
        repeat(4) { r=PuzzleRush.complete(r,r.attempt,true,4000) }
        assertEquals(3,PuzzleRush.choose(pool(),r)!!.moves)
    }
    @Test fun `selection is seeded deterministic ordered and does not repeat adjacent stage entries`() {
        assertTrue(PuzzleRush.ready(pool()))
        assertFalse(PuzzleRush.ready(pool().filter { it.moves!=3 }))
        for (stageScore in listOf(0,1,5)) {
            val r=run().copy(solved=stageScore)
            assertEquals(PuzzleRush.choose(pool(),r),PuzzleRush.choose(pool().reversed(),r))
            assertEquals(PuzzleRush.stage(stageScore),PuzzleRush.choose(pool(),r)!!.moves)
            if(stageScore>0) {
                val sequence=(0..10).map { PuzzleRush.choose(pool(),r.copy(attempt=it)) }
                assertTrue(sequence.zipWithNext().all { it.first!=it.second })
            }
        }
        assertNull(PuzzleRush.choose(emptyList(),run()))
    }
    @Test fun `deadline wins simultaneous solve background time and expired callback`() {
        val r=run()
        val before=PuzzleRush.complete(r,0,true,180999)
        assertEquals(1,before.solved)
        val at=PuzzleRush.complete(r,0,true,181000)
        assertEquals(0,at.solved); assertEquals(RushEnd.TIME,at.end)
        assertEquals(at,PuzzleRush.tick(r,Long.MAX_VALUE))
        assertEquals(at,PuzzleRush.complete(at,0,true,181001))
    }
    @Test fun `duplicate stale and ended callbacks cannot award or count errors twice`() {
        val first=PuzzleRush.complete(run(),0,true,2000)
        assertEquals(first,PuzzleRush.complete(first,0,true,3000))
        assertEquals(first,PuzzleRush.complete(first,0,false,3000))
        var r=run()
        repeat(3) { r=PuzzleRush.complete(r,r.attempt,false,2000) }
        assertEquals(RushEnd.ERRORS,r.end); assertEquals(3,r.errors)
        assertEquals(r,PuzzleRush.complete(r,r.attempt,true,2001))
        val stopped=PuzzleRush.stop(first)
        assertEquals(RushEnd.EXIT,stopped.end)
        assertEquals(stopped,PuzzleRush.complete(stopped,stopped.attempt,true,2001))
    }
    @Test fun `score ceiling and easy error limit end without overflow or further callbacks`() {
        val ceiling=run().copy(solved=PuzzleRush.MAX_SCORE-1,attempt=PuzzleRush.MAX_SCORE-1)
        val last=PuzzleRush.complete(ceiling,ceiling.attempt,true,2000)
        assertEquals(PuzzleRush.MAX_SCORE,last.solved)
        assertEquals(RushEnd.LIMIT,last.end)
        assertEquals(last,PuzzleRush.complete(last,last.attempt,true,3000))
        var easy=run(Difficulty.EASY)
        repeat(4) { easy=PuzzleRush.complete(easy,easy.attempt,false,2000);assertNull(easy.end) }
        easy=PuzzleRush.complete(easy,easy.attempt,false,2000)
        assertEquals(5,easy.errors);assertEquals(RushEnd.ERRORS,easy.end)
        assertThrows(IllegalArgumentException::class.java) { RushRun(Difficulty.EASY,"bad",1000) }
        assertThrows(IllegalArgumentException::class.java) { RushRun(Difficulty.EASY,"0123456789abcdef",-1) }
    }
    @Test fun `record improves only selected difficulty and is idempotent with other modes intact`() {
        val p=Profile(savedMatch=SavedMatch("level-0002",Difficulty.EASY),localSession=SavedLocalSession(wins1=3))
        val n=PuzzleRush.record(p,Difficulty.HARD,12)
        assertEquals(mapOf(Difficulty.HARD to 12),n.rushBest)
        assertEquals(p.copy(revision=n.revision,rushBest=n.rushBest),n)
        assertEquals(n,PuzzleRush.record(n,Difficulty.HARD,12))
        assertEquals(n,PuzzleRush.record(n,Difficulty.HARD,5))
        assertEquals(mapOf(Difficulty.HARD to 12,Difficulty.EASY to 1),PuzzleRush.record(n,Difficulty.EASY,1).rushBest)
        assertThrows(IllegalArgumentException::class.java) { PuzzleRush.record(p,Difficulty.EASY,-1) }
        assertThrows(IllegalArgumentException::class.java) { PuzzleRush.record(p,Difficulty.EASY,PuzzleRush.MAX_SCORE+1) }
    }
    @Test fun `legacy storage backup import and campaign reset preserve independent valid records`() {
        assertTrue(Json.decodeFromString(Profile.serializer(),"{}").rushBest.isEmpty())
        val dir=Files.createTempDirectory("rush-record").toFile()
        try {
            val p=PuzzleRush.record(Profile(),Difficulty.MEDIUM,24)
            val store=ProfileStore(dir);store.save(p)
            assertEquals(p,ProfileStore(dir).load())
            assertEquals(p,ProfileBackup.decode(ProfileBackup.encode(p)))
            assertEquals(p.rushBest,store.resetProgress().rushBest)
            store.importProfile(p)
            assertEquals(p.rushBest,store.load().rushBest)
            assertFalse(Validator.isValid(p.copy(rushBest=mapOf(Difficulty.EASY to -1))))
            assertFalse(Validator.isValid(p.copy(rushBest=mapOf(Difficulty.EASY to PuzzleRush.MAX_SCORE+1))))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `verified packaged pool has twenty two real puzzles and all goal stages`() {
        val root=listOf(java.io.File("../content"),java.io.File("content")).first { java.io.File(it,"catalog.json").isFile }
        val levels=java.io.File(root,"levels/stop-01").listFiles()!!.filter { it.extension=="json" }.flatMap { file ->
            val dto=Json.decodeFromString(pt.tiago.neonline.core.content.LevelFileDto.serializer(),file.readText())
            Difficulty.entries.map { CampaignMapLevel.from(dto,it) }
        }
        val selected=PuzzleRush.pool(levels)
        assertEquals(22,selected.size)
        assertEquals(mapOf(1 to 1,2 to 13,3 to 8),selected.groupingBy { it.moves }.eachCount())
        assertTrue(PuzzleRush.ready(selected))
        assertEquals(selected,PuzzleRush.pool(levels.reversed()+levels))
        for(p in selected) {
            val source=levels.single { it.ordinal==p.ordinal && it.difficulty==p.sourceDifficulty }
            assertEquals(LevelKind.PUZZLE,source.kind)
            assertEquals(p.moves,source.winInMoves)
            assertNull(source.fadeAfter);assertNull(source.clockSeconds)
        }
    }
}
