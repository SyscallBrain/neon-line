package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.rules.GameEngine

class KaraokeServiceTest {
    @Test fun `recipe opens Moon Karaoke with fading and the planned mechanic cadence`() {
        val lessons = KaraokeService.load(File("content")).lessons
        assertEquals((351..360).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"), lessons.map { it.kind })
        assertEquals(List(10) { listOf(5, 3, 2) }, lessons.map { it.fadeAfter })
        assertEquals(listOf(352, 356), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf(355, 358), lessons.filter { it.rotateEvery == 6 }.map { it.ordinal })
        assertEquals(listOf(360), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
        assertEquals("tut_351_fade", lessons.first().tutorialKey)
        assertEquals((352..360).toList(), lessons.drop(1).filter { it.tutorialKey == null }.map { it.ordinal })
        assertTrue(Calibrator.isNoveltyIntroduction(351))
        assertEquals(List(3) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(2) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
    }

    @Test fun `published Moon Karaoke levels carry fade rules and replay certificates`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in KaraokeService.load(root).lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-08", level.stopId)
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
            assertEquals("diva", level.variants.getValue("easy").rival)
            assertTrue(level.learningTags.contains("moon-karaoke"))
            assertTrue(level.learningTags.contains("fade"))
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals(lesson.fadeAfter?.get(difficulty.ordinal), variant.rules.fadeAfter)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.rotateEvery, variant.rules.rotateEvery)
                assertEquals(if (lesson.ordinal == 360) "duel" else lesson.kind, variant.kind.type)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                if (lesson.popOut) assertTrue(GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is pt.tiago.neonline.core.model.Move.Pop })
            }
        }
        assertEquals(1_800, levels.size * 3)
        assertEquals(1_872, publishedCertificateCount(root))
    }
}
