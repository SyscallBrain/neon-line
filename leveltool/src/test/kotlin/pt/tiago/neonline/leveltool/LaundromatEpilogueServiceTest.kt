package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class LaundromatEpilogueServiceTest {
    @Test fun `recipe closes three duel puzzle defense cycles before the boss`() {
        val lessons = LaundromatEpilogueService.load(File("content")).lessons
        assertEquals((291..299).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense"), lessons.map { it.kind })
        assertEquals(listOf(292, 295, 298), lessons.filter { it.popOut }.map { it.ordinal })
        assertTrue(lessons.none { it.isDoubleNeon })
        assertTrue(lessons.all { it.familyId.startsWith("laundromat-") && it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(List(3) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(3) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
    }

    @Test fun `published epilogue levels match the recipe and replay every certificate`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in LaundromatEpilogueService.load(root).lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-06", level.stopId)
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals(false, level.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals("centrifuge", variant.rival)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.kind, variant.kind.type)
                assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                val start = variant.toState(level.id, difficulty)
                if (lesson.kind == "defense") assertTrue(DinerEntry.winning(start, Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue(GameEngine.validMoves(start).any { it is Move.Pop })
                assertTrue(variant.expectedMetrics.humanWinRate in Calibrator.band(difficulty, false, novelty = false))
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue(Certificates.verify(level, certificate).isEmpty())
                if (lesson.kind == "puzzle") {
                    val target = requireNotNull(variant.kind.winInMoves)
                    val proof = ForcedWin(150_000)
                    assertFalse(proof.canWin(start, target - 1) == true)
                    if (difficulty != Difficulty.EASY) assertEquals(1, proof.winningMoves(start, target)?.size)
                }
                if (difficulty == Difficulty.HARD) {
                    assertEquals(0, variant.ai.blunderRate)
                    assertFalse(variant.ai.blunderMayMiss)
                }
            }
        }
    }
}
