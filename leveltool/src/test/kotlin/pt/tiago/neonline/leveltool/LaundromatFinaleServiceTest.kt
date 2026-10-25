package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class LaundromatFinaleServiceTest {
    @Test fun `recipe continues Laundromat cadence through Double Neon 280`() {
        val lessons = LaundromatFinaleService.load(File("content")).lessons
        assertEquals((271..280).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), lessons.map { it.kind })
        assertEquals(listOf(272, 273, 275, 278, 280), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals((271..280).map { it == 280 }, lessons.map { it.isDoubleNeon })
        assertTrue(lessons.all { it.familyId.startsWith("laundromat-") && it.width == 7 && it.height == 6 && it.connect == 4 })
    }

    @Test fun `published finale matches recipe and every variant remains certified`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in LaundromatFinaleService.load(root).lessons) {
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
                val start = variant.toState(level.id, difficulty)
                if (lesson.kind == "defense") assertTrue("${level.id}/$key immediate threat", DinerEntry.winning(start, Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue("${level.id}/$key legal Pop Out", GameEngine.validMoves(start).any { it is Move.Pop })
                assertTrue("${level.id}/$key calibration", variant.expectedMetrics.humanWinRate in Calibrator.band(difficulty, lesson.isDoubleNeon, novelty = false))
                if (difficulty == Difficulty.HARD) {
                    assertEquals(0, variant.ai.blunderRate)
                    assertFalse(variant.ai.blunderMayMiss)
                }
                if (lesson.kind == "puzzle") {
                    val target = requireNotNull(variant.kind.winInMoves)
                    val proof = ForcedWin(150_000)
                    assertFalse("${level.id}/$key exact target", proof.canWin(start, target - 1) == true)
                    if (difficulty != Difficulty.EASY) assertEquals("${level.id}/$key unique root", 1, proof.winningMoves(start, target)?.size)
                }
            }
        }
    }
}
