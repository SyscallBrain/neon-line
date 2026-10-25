package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.model.Move

class DiscoRinkServiceTest {
    @Test fun `recipe preserves the clock stop and adds the 341 to 350 block`() {
        val lessons = DiscoRinkService.load(File("content")).lessons
        assertEquals((301..350).toList(), lessons.map { it.ordinal })
        assertEquals(
            listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel") +
                listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel") +
                listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel") +
                listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel") +
                listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"),
            lessons.map { it.kind },
        )
        assertEquals(listOf(20, 20, 20, 15, 15, 15, 10, 10, 10, 10) + List(40) { 10 }, lessons.map { it.moveClockSec })
        assertEquals(15, lessons.single { it.ordinal == 302 }.openingPairsMax)
        assertEquals(279, lessons.single { it.ordinal == 302 }.sourceHardOrdinal)
        assertEquals("tut_301_clock", lessons.first().tutorialKey)
        assertNull(lessons.first().tutorialColumn)
        assertTrue(lessons.single { it.ordinal == 310 }.isDoubleNeon)
        assertTrue(lessons.single { it.ordinal == 320 }.isDoubleNeon)
        assertTrue(lessons.single { it.ordinal == 330 }.isDoubleNeon)
        assertTrue(lessons.single { it.ordinal == 340 }.isDoubleNeon)
        assertFalse(lessons.single { it.ordinal == 350 }.isDoubleNeon)
        assertFalse(lessons.filter { it.ordinal !in setOf(310, 320, 330, 340) }.any { it.isDoubleNeon })
        assertEquals(listOf(312, 316, 322, 326, 332, 336, 342, 346), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(298, lessons.single { it.ordinal == 312 }.sourceHardOrdinal)
        assertEquals((321..330).toList(), lessons.drop(20).take(10).map { it.ordinal })
        assertEquals((331..340).toList(), lessons.drop(30).take(10).map { it.ordinal })
        assertEquals((341..350).toList(), lessons.drop(40).map { it.ordinal })
        assertEquals(listOf(325, 328, 335, 338, 345, 348), lessons.filter { it.rotateEvery == 6 }.map { it.ordinal })
        assertEquals(List(15) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(10) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals(List(10) { 10 }, lessons.drop(40).map { it.moveClockSec })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"), lessons.drop(40).map { it.kind })
        assertTrue(Calibrator.isNoveltyIntroduction(301))
    }

    @Test fun `recipe rejects a move clock outside the serialized rules limit`() {
        val recipe = DiscoRinkService.load(File("content"))
        val malformed = recipe.copy(lessons = recipe.lessons.map { lesson ->
            if (lesson.ordinal == 301) lesson.copy(moveClockSec = 9) else lesson
        })
        try {
            DiscoRinkService.validate(malformed)
            fail("Expected the invalid clock recipe to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: campaign clocks are constrained to the allowed 10–20 second range.
        }
    }

    @Test fun `published clock levels belong to Skater and replay calibrated certificates`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        val lessons = DiscoRinkService.load(root).lessons
        for (lesson in lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-07", level.stopId)
            assertEquals(if (lesson.ordinal == 350) "Skater's Final Spin" else lesson.titleEn, level.titleEn)
            assertEquals(if (lesson.ordinal == 350) "Última volta do Patins" else lesson.titlePt, level.titlePt)
            assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
            assertEquals(lesson.tutorialKey, level.tutorial?.steps?.singleOrNull()?.textKey)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals("skater", variant.rival)
                assertEquals(lesson.moveClockSec, variant.rules.moveClockSec)
                assertEquals(lesson.rotateEvery, variant.rules.rotateEvery)
                assertEquals(if (lesson.ordinal == 350) "boss" else lesson.kind, variant.kind.type)
                if (lesson.ordinal == 350) {
                    assertEquals(3, variant.kind.bestOf)
                    assertEquals(listOf("P1", "P2", "P1"), variant.bossRounds.map { it.firstToMove })
                }
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                val band = Calibrator.band(difficulty, lesson.isDoubleNeon || lesson.ordinal == 350, novelty = lesson.ordinal == 301)
                assertTrue("${level.id}/$key calibrated ${variant.expectedMetrics.humanWinRate}", variant.expectedMetrics.humanWinRate in band)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key certificate", Certificates.verify(level, certificate).isEmpty())
                if (difficulty == Difficulty.HARD) {
                    assertEquals(0, variant.ai.blunderRate)
                    assertFalse(variant.ai.blunderMayMiss)
                    assertTrue("${level.id} search budget", variant.ai.nodeBudget <= 6_000)
                    assertTrue("${level.id} search depth", variant.ai.maxDepth <= 4)
                }
                if (lesson.popOut) assertTrue("${level.id}/$key legal Pop", GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is Move.Pop })
            }
        }
    }
}
