package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DriveInContinuationServiceTest {
    @Test fun `follow-up recipe declares the approved 211 through 220 sequence`() {
        val recipes = DriveInContinuationService.load(File("content"))
        assertEquals((211..220).toList(), recipes.lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"), recipes.lessons.map { it.kind })
        assertEquals(listOf(212, 213, 215, 218, 220), recipes.lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf("Late Show", "Snack Bar Shuffle", "Jump Cut", "Aisle Patrol", "Reel Change", "Three-Scene Twist", "Ticket Rush", "Balcony Guard", "Last Take", "Premiere Night"), recipes.lessons.map { it.titleEn })
        assertEquals(listOf("Sessão tardia", "Corrida ao balcão", "Corte brusco", "Vigia da plateia", "Mudança de bobina", "Reviravolta em três cenas", "Corrida aos bilhetes", "Guarda do balcão", "Última tomada", "Noite de estreia"), recipes.lessons.map { it.titlePt })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), recipes.lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), recipes.lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertNull(recipes.lessons.single { it.ordinal == 213 }.sourceHardOrdinal)
        assertTrue(recipes.lessons.filter { it.kind == "puzzle" }.all { it.sourceOrdinals == null && it.sourceHardOrdinal == null })
        assertTrue(recipes.lessons.last().isDoubleNeon)
        assertTrue(recipes.lessons.dropLast(1).none { it.isDoubleNeon })
    }

    @Test fun `published continuation is certified and calibrated against its recipes`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        val recipes = DriveInContinuationService.load(root)
        for (lesson in recipes.lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
            assertEquals("projectionist", level.variants.getValue("easy").rival)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals(7, variant.width); assertEquals(6, variant.height); assertEquals(4, variant.connect)
                assertEquals("P1", variant.firstToMove)
                assertEquals(lesson.popOut, variant.rules.popOut)
                assertEquals(lesson.kind, variant.kind.type)
                assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key certificate", Certificates.verify(level, certificate).isEmpty())
                val band = Calibrator.band(difficulty, lesson.isDoubleNeon, novelty = false)
                assertTrue("${level.id}/$key calibration ${variant.expectedMetrics.humanWinRate}", variant.expectedMetrics.humanWinRate in band)
                if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY) {
                    val roots = ForcedWin(150_000).winningMoves(variant.toState(level.id, difficulty), requireNotNull(variant.kind.winInMoves))
                    assertEquals("${level.id}/$key unique first move", 1, roots?.size)
                }
                if (lesson.kind == "defense") assertTrue("${level.id}/$key real threat", DinerEntry.winning(variant.toState(level.id, difficulty), Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue("${level.id}/$key legal Pop", GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is Move.Pop })
            }
        }
        val independentlyCalibrated = Calibrator.calibrate(root, "level-0211", Difficulty.EASY, 32)
        assertTrue("independent 32-game replay must be in band", independentlyCalibrated.inBand)
        assertEquals(levels.single { it.id == "level-0211" }.variants.getValue("easy").expectedMetrics.humanWinRate,
            independentlyCalibrated.winRate, 0.0)
    }

    @Test fun `published Hard rivals preserve tactical safety`() {
        for (ordinal in 211..220) {
            val level = Files.levels(File("content")).single { it.ordinal == ordinal }
            val hard = level.variants.getValue("hard").ai
            assertEquals("level-$ordinal Hard must not force deliberate blunders", 0, hard.blunderRate)
            assertFalse("level-$ordinal Hard must respect immediate wins and blocks", hard.blunderMayMiss)
        }
    }
}
