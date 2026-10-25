package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class KaraokeFinaleServiceTest {
    @Test fun `finale recipe continues fading with the established mechanics`() {
        val lessons = KaraokeFinaleService.load(File("content")).lessons
        assertEquals((391..399).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), lessons.map { it.kind })
        assertEquals(List(9) { listOf(5, 3, 2) }, lessons.map { it.fadeAfter })
        assertEquals(listOf(392, 396), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf(395, 398), lessons.filter { it.rotateEvery == 6 }.map { it.ordinal })
        assertTrue(lessons.all { !it.isDoubleNeon && it.tutorialKey == null && it.tutorialColumn == null })
        assertEquals(List(3) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(2) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
    }

    @Test fun `published finale levels retain Diva mirror and replay all variants`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in KaraokeFinaleService.load(root).lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-08", level.stopId)
            assertTrue(level.learningTags.contains("fade"))
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals("diva", variant.rival)
                assertTrue(variant.ai.mirror)
                assertEquals(lesson.fadeAfter?.get(difficulty.ordinal), variant.rules.fadeAfter)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.rotateEvery, variant.rules.rotateEvery)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            }
        }
    }
}
