package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class LaundromatServiceTest {
    @Test fun `recipe opens Stop 6 with the established level cadence`() {
        val lessons = LaundromatService.load(File("content")).lessons
        assertEquals((251..260).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), lessons.map { it.kind })
        assertEquals(listOf(252, 253, 255, 258, 260), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals("Bubble Burst", lessons.first().titleEn)
        assertTrue(lessons.last().isDoubleNeon)
        assertTrue(lessons.all { it.familyId.startsWith("laundromat-") && it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(243, lessons.single { it.ordinal == 253 }.sourceHardOrdinal)
        assertEquals(226, lessons.single { it.ordinal == 256 }.sourceHardOrdinal)
        assertEquals(229, lessons.single { it.ordinal == 259 }.sourceHardOrdinal)
        assertEquals(listOf(248, 248, 248), lessons.single { it.ordinal == 258 }.sourceOrdinals)
    }

    @Test fun `published Laundromat block is certified and follows its recipe`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        val lessons = LaundromatService.load(root).lessons
        for (lesson in lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-06", level.stopId)
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals("centrifuge", variant.rival)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.kind, variant.kind.type)
                assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                val cert = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key", Certificates.verify(level, cert).isEmpty())
                if (lesson.kind == "defense") assertTrue(DinerEntry.winning(variant.toState(level.id, difficulty), Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue(GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is Move.Pop })
                if (lesson.kind == "puzzle") {
                    val start = variant.toState(level.id, difficulty)
                    val proof = ForcedWin(150_000)
                    val target = requireNotNull(variant.kind.winInMoves)
                    assertEquals("${level.id}/$key exact target", false, proof.canWin(start, target - 1))
                    if (difficulty != Difficulty.EASY) assertEquals("${level.id}/$key unique root", 1, proof.winningMoves(start, target)?.size)
                }
                assertTrue("${level.id}/$key calibration", variant.expectedMetrics.humanWinRate in Calibrator.band(difficulty, lesson.isDoubleNeon, novelty = false))
                if (difficulty == Difficulty.HARD) {
                    assertEquals("${level.id} Hard blunder rate", 0, variant.ai.blunderRate)
                    assertFalse("${level.id} Hard may miss a tactic", variant.ai.blunderMayMiss)
                }
            }
        }
    }
}
