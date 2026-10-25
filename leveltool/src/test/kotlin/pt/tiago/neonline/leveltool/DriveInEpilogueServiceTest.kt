package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DriveInEpilogueServiceTest {
    @Test fun `epilogue recipe declares the approved 221 through 230 sequence`() {
        val recipes = DriveInEpilogueService.load(File("content"))
        assertEquals((221..230).toList(), recipes.lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), recipes.lessons.map { it.kind })
        assertEquals(listOf(222, 223, 225, 228, 230), recipes.lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf("Midnight Snack", "Popcorn Panic", "Frame by Frame", "Quiet Row", "Projector Jam", "Plot Twist", "Closing Time", "Last Screening", "Final Cut", "After Credits"), recipes.lessons.map { it.titleEn })
        assertEquals(listOf("Lanche da meia-noite", "Pânico das pipocas", "Fotograma a fotograma", "Fila tranquila", "Projetor encravado", "Reviravolta", "Hora de fechar", "Última sessão", "Corte final", "Depois dos créditos"), recipes.lessons.map { it.titlePt })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), recipes.lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), recipes.lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals(listOf(219, 216, 219), recipes.lessons.single { it.ordinal == 229 }.sourceOrdinals)
        assertEquals(219, recipes.lessons.single { it.ordinal == 229 }.sourceHardOrdinal)
        assertTrue(recipes.lessons.filter { it.ordinal != 229 }.all { it.sourceOrdinals == null })
        assertTrue(recipes.lessons.last().isDoubleNeon)
        assertTrue(recipes.lessons.dropLast(1).none { it.isDoubleNeon })
    }

    @Test fun `published epilogue is certified and calibrated against its recipes`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        val recipes = DriveInEpilogueService.load(root)
        for (lesson in recipes.lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals(7, variant.width); assertEquals(6, variant.height); assertEquals(4, variant.connect)
                assertEquals("P1", variant.firstToMove)
                assertEquals("projectionist", variant.rival)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.kind, variant.kind.type)
                assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key certificate", Certificates.verify(level, certificate).isEmpty())
                val band = Calibrator.band(difficulty, lesson.isDoubleNeon, novelty = false)
                assertTrue("${level.id}/$key calibration ${variant.expectedMetrics.humanWinRate}", variant.expectedMetrics.humanWinRate in band)
                if (difficulty == Difficulty.HARD) {
                    assertEquals("${level.id} Hard blunder rate", 0, variant.ai.blunderRate)
                    assertFalse("${level.id} Hard tactical safety", variant.ai.blunderMayMiss)
                }
                if (lesson.kind == "puzzle") {
                    val start = variant.toState(level.id, difficulty)
                    val proof = ForcedWin(150_000)
                    val target = requireNotNull(variant.kind.winInMoves)
                    assertEquals("${level.id}/$key cannot win sooner", false, proof.canWin(start, target - 1))
                    if (difficulty != Difficulty.EASY) {
                        val roots = proof.winningMoves(start, target)
                        assertEquals("${level.id}/$key unique first move", 1, roots?.size)
                    }
                }
                if (lesson.kind == "defense") assertTrue("${level.id}/$key real threat", DinerEntry.winning(variant.toState(level.id, difficulty), Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue("${level.id}/$key legal Pop", GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is Move.Pop })
            }
        }
    }
}
