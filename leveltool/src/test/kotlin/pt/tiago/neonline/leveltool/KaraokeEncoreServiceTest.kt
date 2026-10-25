package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class KaraokeEncoreServiceTest {
    @Test fun `encore recipe preserves fading and the established mechanic cadence`() {
        val lessons = KaraokeEncoreService.load(File("content")).lessons
        assertEquals((371..380).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"), lessons.map { it.kind })
        assertEquals(List(10) { listOf(5, 3, 2) }, lessons.map { it.fadeAfter })
        assertEquals(listOf(372, 376), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf(375, 378), lessons.filter { it.rotateEvery == 6 }.map { it.ordinal })
        assertEquals(listOf(380), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
        assertTrue(lessons.all { it.tutorialKey == null && it.tutorialColumn == null })
        assertEquals(List(3) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(2) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
    }

    @Test fun `published encore levels keep Diva mirror profiles and replay every certificate`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in KaraokeEncoreService.load(root).lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-08", level.stopId)
            assertEquals("diva", level.variants.getValue("easy").rival)
            assertTrue(level.learningTags.contains("moon-karaoke"))
            assertTrue(level.learningTags.contains("fade"))
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertTrue("${level.id}/$key must keep Diva's mirror personality", variant.ai.mirror)
                assertEquals(lesson.fadeAfter?.get(difficulty.ordinal), variant.rules.fadeAfter)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.rotateEvery, variant.rules.rotateEvery)
                assertEquals(if (lesson.ordinal == 380) "duel" else lesson.kind, variant.kind.type)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            }
        }
        assertEquals(1_800, levels.size * 3)
        assertEquals(1_872, publishedCertificateCount(root))
    }
}
