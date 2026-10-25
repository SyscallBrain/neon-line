package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DriveInFinaleServiceTest {
    @Test fun `finale recipe declares the approved 231 through 240 sequence`() {
        val recipes = DriveInFinaleService.load(File("content"))
        assertEquals((231..240).toList(), recipes.lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), recipes.lessons.map { it.kind })
        assertEquals(listOf(232, 233, 235, 238, 240), recipes.lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf("Reel Rush", "Snack Stack", "Cut to the Chase", "Seat Saver", "Lights, Camera, Action", "Hidden Scene", "Box Office", "Balcony Encore", "Final Matinee", "Silver Screen"), recipes.lessons.map { it.titleEn })
        assertEquals(listOf("Corrida de bobinas", "Pilha de snacks", "Direto ao ponto", "Guarda-lugar", "Luzes, câmara, ação", "Cena escondida", "Bilheteira", "Repetição na varanda", "Sessão final", "Ecrã prateado"), recipes.lessons.map { it.titlePt })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), recipes.lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), recipes.lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertNull(recipes.lessons.single { it.ordinal == 236 }.sourceOrdinals)
        assertEquals(219, recipes.lessons.single { it.ordinal == 236 }.sourceHardOrdinal)
        assertEquals(219, recipes.lessons.single { it.ordinal == 239 }.sourceHardOrdinal)
        assertTrue(recipes.lessons.last().isDoubleNeon)
        assertTrue(recipes.lessons.dropLast(1).none { it.isDoubleNeon })
    }

    @Test fun `published finale is certified and calibrated against its recipes`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        val recipes = DriveInFinaleService.load(root)
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
