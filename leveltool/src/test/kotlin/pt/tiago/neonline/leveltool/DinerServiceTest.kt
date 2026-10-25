package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.levelJson
import pt.tiago.neonline.core.content.CertificateDto
import pt.tiago.neonline.core.content.KindDto
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.model.Difficulty
import kotlinx.serialization.json.Json

class DinerServiceTest {
    private val root = File("content")

    @Test fun `hard four-move puzzles admit the documented wider authoring band`() {
        assertEquals(11..20,DinerService.selectionBand(Difficulty.HARD,78,KindDto("puzzle",winInMoves=4)))
        assertEquals(19..24,DinerService.selectionBand(Difficulty.MEDIUM,78,KindDto("puzzle",winInMoves=4)))
        assertEquals(11..16,DinerService.selectionBand(Difficulty.HARD,77,KindDto("defense",surviveAiMoves=9)))
    }

    @Test fun `service recipes define thirty nine lessons with seven-use family caps and equal types`() {
        val recipes = DinerService.load(root)
        assertEquals(6, recipes.families.size)
        assertEquals((61..99).toList(), recipes.lessons.map { it.ordinal })
        val kinds = recipes.lessons.groupingBy { lesson -> recipes.families.first { it.id == lesson.familyId }.kind }.eachCount()
        assertEquals(mapOf("duel" to 13,"defense" to 13,"puzzle" to 13),kinds)
        assertTrue(recipes.lessons.zipWithNext().all { (a,b) -> a.familyId != b.familyId })
        assertTrue(recipes.lessons.groupingBy { it.familyId }.eachCount().values.all { it <= 7 })
        assertEquals(listOf("diner-counter-duel","diner-edge-defense","diner-short-puzzle","diner-long-duel","diner-center-defense","diner-long-puzzle","diner-counter-duel","diner-edge-defense","diner-short-puzzle"),recipes.lessons.filter { it.ordinal >= 91 }.map { it.familyId })
        assertEquals("diner-long-puzzle",recipes.lessons.first { it.ordinal==90 }.familyId)
        val first = recipes.lessons.first()
        for (bad in listOf(recipes.copy(version=2), recipes.copy(engineVersion=4),
            recipes.copy(lessons=recipes.lessons + first),
            recipes.copy(lessons=recipes.lessons.map { it.copy(familyId="missing") }),
            recipes.copy(lessons=recipes.lessons.map { it.copy(familyId=recipes.lessons[0].familyId) }),
            recipes.copy(families=recipes.families.map { it.copy(kind="shape") }),
            recipes.copy(families=recipes.families.map { if(it.kind=="puzzle") it.copy(puzzleMoves=listOf(1,3,4)) else it }),
            recipes.copy(families=recipes.families.map { if(it.kind=="defense") it.copy(threatColumns=listOf(7)) else it }),
            recipes.copy(families=recipes.families.map { if(it.id=="diner-edge-defense") it.copy(kind="duel",threatColumns=emptyList()) else it }),
            recipes.copy(families=recipes.families.map { it.copy(minOpeningPairs=0) }))) {
            assertThrows(IllegalArgumentException::class.java) { DinerService.validate(bad) }
        }
    }

    @Test fun `staging refuses overlapping paths and an unowned existing directory before writing`() {
        for (output in listOf(root, File(root,"candidates/service"), root.absoluteFile.parentFile)) {
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
        }
        val dir = NioFiles.createTempDirectory("diner-unowned").toFile()
        try {
            File(dir,"keep.txt").writeText("untouched")
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,dir) }
            assertEquals(listOf("keep.txt"),dir.list()!!.toList())
            assertEquals("untouched",File(dir,"keep.txt").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `staging preserves published prefix and refuses changed recipes or a damaged prefix`() {
        val parent = NioFiles.createTempDirectory("diner-owned").toFile()
        val output = File(parent,"stage")
        val frozenLevel = File(root,"levels/stop-02/level-0060.json").readBytes()
        try {
            DinerService.prepare(root,output)
            DinerService.prepare(root,output)
            assertEquals((81..90).toList(),Files.levels(output).filter { it.ordinal in 81..90 }.map { it.ordinal })
            assertEquals(listOf(100),Files.levels(output).filter { it.ordinal==100 }.map { it.ordinal })
            assertEquals((101..250).toList(),Files.levels(output).filter { it.ordinal in 101..250 }.map { it.ordinal })
            assertEquals((251..300).toList(),Files.levels(output).filter { it.ordinal in 251..300 }.map { it.ordinal })
            assertArrayEquals(File(root,"levels/stop-05/level-0250.json").readBytes(),File(output,"levels/stop-05/level-0250.json").readBytes())
            assertTrue(Files.levels(output).none { it.ordinal in 91..99 })
            for (ordinal in 1..80) {
                val source = Files.levels(root).first { it.ordinal==ordinal }
                val old = File(root,"levels/${source.stopId}/${source.id}.json").readBytes()
                assertArrayEquals("level $ordinal",old,File(output,"levels/${source.stopId}/${source.id}.json").readBytes())
            }
            val recipes = File(output,"templates/families.json")
            val original = recipes.readText()
            recipes.writeText(original + " ")
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
            recipes.writeText(original)
            File(output,"levels/stop-02/level-0060.json").writeText("{}")
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
            assertArrayEquals(frozenLevel,File(root,"levels/stop-02/level-0060.json").readBytes())
        } finally { parent.deleteRecursively() }
    }

    @Test fun `resume refuses a lesson file with missing or mismatched certificates`() {
        val parent = NioFiles.createTempDirectory("diner-incomplete").toFile()
        val output = File(parent,"stage")
        try {
            DinerService.prepare(root,output)
            val source = Certificates.decodeLevel(File(root,"levels/stop-02/level-0054.json").readText())
            val level = source.copy(id="level-0091",ordinal=91,familyId="diner-short-puzzle")
            File(output,"levels/stop-02/level-0091.json").writeText(levelJson(level))
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
            val cert = File(output,"certificates/level-0091/easy.json")
            cert.parentFile.mkdirs()
            File(root,"certificates/level-0054/easy.json").copyTo(cert)
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun `resume binds each certificate file to its own difficulty`() {
        val parent = NioFiles.createTempDirectory("diner-wrong-difficulty").toFile()
        val output = File(parent,"stage")
        try {
            DinerService.prepare(root,output)
            val source = Certificates.decodeLevel(File(root,"levels/stop-02/level-0054.json").readText())
            val level = source.copy(id="level-0091",ordinal=91,familyId="diner-short-puzzle")
            val variant = level.variants.getValue("easy")
            val path = DinerEntry.witness(variant.toState(level.id,Difficulty.EASY),variant)!!
            val cert = Certificates.fromSteps(level,Difficulty.EASY,path)
            assertTrue(Certificates.verify(level,cert).isEmpty())
            File(output,"levels/stop-02/level-0091.json").writeText(levelJson(level))
            for(key in listOf("easy","medium","hard")) {
                File(output,"certificates/level-0091/$key.json").apply {
                    parentFile.mkdirs();writeText(Json.encodeToString(CertificateDto.serializer(),cert))
                }
            }
            assertThrows(IllegalArgumentException::class.java) { DinerService.prepare(root,output) }
        } finally { parent.deleteRecursively() }
    }
}
