package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DriveInClosingServiceTest {
    @Test fun `closing recipe declares nine levels before the Projectionist boss`() {
        val lessons = DriveInClosingService.load(File("content")).lessons
        assertEquals((241..249).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle"), lessons.map { it.kind })
        assertEquals(listOf(242, 243, 245, 248), lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf("Premiere Rush", "Popcorn Encore", "Focus Pull", "Late Seating", "Projector's Cut", "Suspense Reel", "Final Credits", "Midnight Screening", "Closing Night"), lessons.map { it.titleEn })
        assertEquals(listOf("Corrida de estreia", "Repetição de pipocas", "Foco no fotograma", "Lugares tardios", "Corte do projetor", "Bobina de suspense", "Créditos finais", "Sessão da meia-noite", "Noite de estreia"), lessons.map { it.titlePt })
        assertTrue(lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 && !it.isDoubleNeon })
        assertEquals(List(3) { listOf(2, 3, 4) }, lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(2) { listOf(4, 5, 6) }, lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals(219, lessons.single { it.ordinal == 249 }.sourceHardOrdinal)
    }

    @Test fun `published closing variants meet objectives calibration and certificate requirements`() {
        val root = File("content")
        val lessons = DriveInClosingService.load(root).lessons
        val levels = Files.levels(root)
        assertEquals((241..249).toList(), levels.filter { it.ordinal in 241..249 }.map { it.ordinal })
        for (lesson in lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals(lesson.titleEn, level.titleEn)
            assertEquals(lesson.titlePt, level.titlePt)
            assertEquals("stop-05", level.stopId)
            assertFalse(level.isDoubleNeon)
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
                val cert = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key certificate", Certificates.verify(level, cert).isEmpty())
                assertTrue("${level.id}/$key calibration", variant.expectedMetrics.humanWinRate in Calibrator.band(difficulty, false, novelty = false))
                if (difficulty == Difficulty.HARD) {
                    assertEquals(0, variant.ai.blunderRate)
                    assertFalse(variant.ai.blunderMayMiss)
                }
                if (lesson.kind == "puzzle") {
                    val state = variant.toState(level.id, difficulty)
                    val target = requireNotNull(variant.kind.winInMoves)
                    val proof = ForcedWin(150_000)
                    assertEquals("${level.id}/$key exact minimum", false, proof.canWin(state, target - 1))
                    if (difficulty != Difficulty.EASY) assertEquals(1, proof.winningMoves(state, target)?.size)
                }
                if (lesson.kind == "defense") assertTrue(DinerEntry.winning(variant.toState(level.id, difficulty), Player.P2).isNotEmpty())
                if (lesson.popOut) assertTrue(GameEngine.validMoves(variant.toState(level.id, difficulty)).any { it is Move.Pop })
            }
        }
    }

    @Test fun `closing staging rejects ranges outside the nine ordinary levels before creating output`() {
        val parent = NioFiles.createTempDirectory("drivein-closing-range").toFile()
        val source = File(parent, "source")
        File("content").copyRecursively(source)
        val output = File(parent, "output")
        try {
            assertThrows(IllegalArgumentException::class.java) { DriveInClosingService.run(source, output, 240, 249) }
            assertFalse(output.exists())
        } finally { parent.deleteRecursively() }
    }
}
