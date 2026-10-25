package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DinerEntryTest {
    private val root=File("content")
    @Test fun `published entry has ten distinct lessons three variants unique hard puzzle roots and valid defense goals`() {
        val levels=Files.levels(root).filter { it.ordinal in 51..60 }
        assertEquals((51..60).toList(),levels.map { it.ordinal })
        assertEquals(5,levels.count { it.variants.getValue("easy").kind.type=="defense" })
        val positions=mutableSetOf<String>()
        for(level in levels) for((key,variant) in level.variants) {
            assertEquals("dolores",variant.rival);assertTrue(variant.ai.mirror)
            val state=variant.toState(level.id,Difficulty.valueOf(key.uppercase()))
            val physical=state.pieces.joinToString { it?.owner?.name ?: "." }
            assertTrue("Repeated Diner position",positions.add(physical))
            assertTrue(GameEngine.validMoves(state).none { GameEngine.apply(state,it).state.status==Status.Won(Player.P1) })
            if(variant.kind.type=="puzzle" && key!="easy")
                assertEquals(1,ForcedWin(120_000).winningMoves(state,variant.kind.winInMoves!!)!!.size)
            if(variant.kind.type=="defense")assertTrue(variant.kind.surviveAiMoves!!>0)
        }
        assertEquals("tut_defense_1",levels.first().tutorial!!.steps.single().textKey)
        assertTrue(levels.drop(1).all { it.tutorial==null })
    }
    @Test fun `staging rejects paths overlapping the source before any writes`() {
        for(output in listOf(root,File(root,"candidates/diner-overlap"),root.absoluteFile.parentFile)) {
            assertThrows(IllegalArgumentException::class.java) { DinerEntry.run(root,output) }
        }
    }
    @Test fun `entry generator resumes without deleting later Diner lessons`() {
        val parent=java.nio.file.Files.createTempDirectory("diner-entry-compat").toFile()
        val output=File(parent,"stage")
        try {
            root.copyRecursively(output)
            val later61=File(output,"levels/stop-02/level-0061.json").readBytes()
            val later80=File(output,"levels/stop-02/level-0080.json").readBytes()
            val later120=File(output,"levels/stop-03/level-0120.json").readBytes()
            val later130=File(output,"levels/stop-03/level-0130.json").readBytes()
            val later140=File(output,"levels/stop-03/level-0140.json").readBytes()
            val later160=File(output,"levels/stop-04/level-0160.json").readBytes()
            val later170=File(output,"levels/stop-04/level-0170.json").readBytes()
            DinerEntry.run(root,output)
            assertArrayEquals(later61,File(output,"levels/stop-02/level-0061.json").readBytes())
            assertArrayEquals(later80,File(output,"levels/stop-02/level-0080.json").readBytes())
            assertArrayEquals(later120,File(output,"levels/stop-03/level-0120.json").readBytes())
            assertArrayEquals(later130,File(output,"levels/stop-03/level-0130.json").readBytes())
            assertArrayEquals(later140,File(output,"levels/stop-03/level-0140.json").readBytes())
            assertArrayEquals(later160,File(output,"levels/stop-04/level-0160.json").readBytes())
            assertArrayEquals(later170,File(output,"levels/stop-04/level-0170.json").readBytes())
            assertArrayEquals(later61,File(root,"levels/stop-02/level-0061.json").readBytes())
        } finally { parent.deleteRecursively() }
    }

    @Test fun `entry generator accepts and preserves the published campaign through level three hundred sixty`() {
        val parent=java.nio.file.Files.createTempDirectory("diner-entry-through-100").toFile()
        val source=File(parent,"source");root.copyRecursively(source)
        val level80=Certificates.decodeLevel(File(source,"levels/stop-02/level-0080.json").readText())
        val level90=level80.copy(id="level-0090",ordinal=90)
        File(source,"levels/stop-02/level-0090.json").writeText(levelJson(level90))
        val level100=level80.copy(id="level-0100",ordinal=100)
        File(source,"levels/stop-02/level-0100.json").writeText(levelJson(level100))
        val level101=level80.copy(id="level-0101",ordinal=101,stopId="stop-03")
        File(source,"levels/stop-03/level-0101.json").apply { parentFile.mkdirs();writeText(levelJson(level101)) }
        val source90=File(source,"levels/stop-02/level-0090.json")
        val output=File(parent,"stage");source.copyRecursively(output)
        try {
            DinerEntry.run(source,output)
            assertArrayEquals(source90.readBytes(),File(output,"levels/stop-02/level-0090.json").readBytes())
            assertArrayEquals(File(source,"levels/stop-02/level-0100.json").readBytes(),File(output,"levels/stop-02/level-0100.json").readBytes())
            assertArrayEquals(File(source,"levels/stop-03/level-0101.json").readBytes(),File(output,"levels/stop-03/level-0101.json").readBytes())
            assertArrayEquals(File(source,"levels/stop-05/level-0220.json").readBytes(),File(output,"levels/stop-05/level-0220.json").readBytes())
        } finally { parent.deleteRecursively() }
    }
}
