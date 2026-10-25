package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class DriveInServiceTest {
    @Test fun `Drive-In recipes define Pop Out levels and goals from 201 through 210`() {
        val recipes = DriveInService.load(File("content"))
        assertEquals((201..210).toList(), recipes.lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"),
            recipes.lessons.map { it.kind })
        assertEquals(listOf(201, 203, 205, 208, 210), recipes.lessons.filter { it.popOut }.map { it.ordinal })
        assertEquals(listOf("Opening Credits", "Box Office", "Popcorn Pause", "Aisle Watch", "Second Showing",
            "Matinee Puzzle", "Ticket Check", "Last Row", "Final Reel", "Closing Credits"),
            recipes.lessons.map { it.titleEn })
        assertEquals(listOf("Créditos de abertura", "Bilheteira", "Pausa para pipocas", "Vigilância da fila", "Segunda sessão",
            "Enigma da matiné", "Verificação de bilhetes", "Última fila", "Rolo final", "Créditos finais"),
            recipes.lessons.map { it.titlePt })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        assertEquals(listOf(null, null, listOf(2, 75, 69), null, null, null, null, null, null, null),
            recipes.lessons.map { it.sourceOrdinals })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), recipes.lessons
            .filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), recipes.lessons
            .filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals(201, recipes.lessons.first().ordinal)
        assertEquals("tut_201_popout", recipes.lessons.first().tutorialKey)
        assertNotNull(recipes.lessons.first().tutorialColumn)
        assertTrue(recipes.lessons.last().isDoubleNeon)
    }

    @Test fun `Drive-In staging preserves the published prefix and certifies all variants`() {
        val parent = NioFiles.createTempDirectory("drivein-stage").toFile()
        val source = File(parent, "source")
        val output = File(parent, "stage")
        try {
            File("content").copyRecursively(source)
            for (ordinal in 201..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            val frozen = source.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(source).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 200 } == true
                }
            }.associate { it.relativeTo(source).invariantSeparatorsPath to it.readBytes() }

            DriveInService.run(source, output)

            for (difficulty in Difficulty.entries) {
                val calibration = Calibrator.calibrate(output, "level-0201", difficulty, 32)
                assertTrue("level-0201/$difficulty calibration ${calibration.winRate}", calibration.inBand)
                val metrics = Files.levels(output).single { it.id == "level-0201" }
                    .variants.getValue(difficultyKey(difficulty)).expectedMetrics
                assertTrue("level-0201/$difficulty expected rate", metrics.humanWinRate in Calibrator.band(difficulty, false, true))
                assertEquals("level-0201/$difficulty recorded rate", calibration.winRate, metrics.humanWinRate, 0.0)
            }

            val levels = Files.levels(output)
            assertEquals((201..210).toList(), levels.filter { it.ordinal > 200 }.map { it.ordinal })
            assertEquals(210, levels.size)
            for ((path, bytes) in frozen) assertTrue("Changed published prefix: $path", File(output, path).readBytes().contentEquals(bytes))
            for (lesson in DriveInService.load(File("content")).lessons) {
                val level = levels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-05", level.stopId)
                assertEquals("projectionist", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                assertEquals(lesson.tutorialKey, level.tutorial?.steps?.singleOrNull()?.textKey)
                for (difficulty in Difficulty.entries) {
                    val key = difficultyKey(difficulty)
                    val variant = level.variants.getValue(key)
                    assertEquals(7, variant.width)
                    assertEquals(6, variant.height)
                    assertEquals(4, variant.connect)
                    assertTrue(variant.mask.all { row -> row == "XXXXXXX" })
                    assertEquals(lesson.popOut, variant.rules.popOut)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                    val certificate = Certificates.decodeCertificate(File(output, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    assertEquals(3, certificate.stars)
                    if (lesson.ordinal == 201) {
                        val column = requireNotNull(lesson.tutorialColumn)
                        val state = variant.toState(level.id, difficulty)
                        assertTrue("$key column $column cannot Pop", Move.Pop(column) in GameEngine.validMoves(state))
                    }
                    if (lesson.kind == "defense") assertTrue(DinerEntry.winning(variant.toState(level.id, difficulty), Player.P2).isNotEmpty())
                    if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY)
                        assertEquals(1, ForcedWin(150_000).winningMoves(variant.toState(level.id, difficulty), variant.kind.winInMoves!!)!!.size)
                }
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
