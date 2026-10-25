package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotelServiceTest {
    @Test fun `published 600 level campaign can be staged unchanged`() {
        val parent = NioFiles.createTempDirectory("motel-complete").toFile()
        val source = File("content")
        val output = File(parent, "stage")
        try {
            MotelService.run(source, output)
            assertArrayEquals(File(source, "catalog.json").readBytes(), File(output, "catalog.json").readBytes())
            val sourceFiles = source.walkTopDown().filter { file ->
                file.isFile && (file.invariantSeparatorsPath.contains("/levels/") || isPublishedCertificateFile(file))
            }
                .associate { it.relativeTo(source).invariantSeparatorsPath to it.readBytes() }
            assertEquals(2_472, sourceFiles.size)
            for ((path, bytes) in sourceFiles) assertArrayEquals(path, bytes, File(output, path).readBytes())
        } finally { parent.deleteRecursively() }
    }

    @Test fun `motel recipe introduces all four shape rules and closes the first block with Double Neon`() {
        val recipe = MotelService.load(File("content"))
        assertEquals((101..110).toList(), recipe.lessons.take(10).map { it.ordinal })
        assertEquals(listOf("shape", "duel", "puzzle", "shape", "duel", "defense", "shape", "puzzle", "shape", "duel"),
            recipe.lessons.take(10).map { it.kind })
        assertEquals(listOf(101,104,107,109), recipe.lessons.filter { it.kind == "shape" }.map { it.ordinal })
        assertEquals(listOf("diagonal_only", "through_target", "horizontal_only", "vertical_only"),
            recipe.lessons.filter { it.kind == "shape" }.map { it.shapeRule })
        assertEquals("tut_101_diagonal", recipe.lessons.first().tutorialKey)
        assertTrue(recipe.lessons.all { it.titleEn.isNotBlank() && it.titlePt.isNotBlank() })
        assertTrue(recipe.lessons[9].isDoubleNeon)
    }

    @Test fun `second Motel block has three duels puzzles defenses and Double Neon at 120`() {
        val lessons = MotelService.load(File("content")).lessons.filter { it.ordinal in 111..120 }
        assertEquals((111..120).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.kind })
        assertEquals(listOf(2, 3, 4), lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(4, 5, 6), lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true), lessons.map { it.isDoubleNeon })
        assertTrue(lessons.all { it.titleEn.isNotBlank() && it.titlePt.isNotBlank() })
    }

    @Test fun `third Motel block repeats duel puzzle defense and closes with Double Neon at 130`() {
        val lessons = MotelService.load(File("content")).lessons.filter { it.ordinal in 121..130 }
        assertEquals((121..130).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.kind })
        assertEquals(listOf(2, 3, 4), lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(4, 5, 6), lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals((121..129).map { false } + true, lessons.map { it.isDoubleNeon })
        assertEquals(listOf("Late Checkout", "Housekeeping", "Fire Escape", "Noisy Neighbors", "Key Under the Mat",
            "Quiet Hours", "Vacancy Call", "Locked Suite", "Night Audit", "Neon on the Roof"), lessons.map { it.titleEn })
        assertEquals(listOf("Check-out tardio", "Serviço de limpeza", "Saída de emergência", "Vizinhos barulhentos",
            "Chave debaixo do tapete", "Horas de silêncio", "Chamada de vaga", "Suite fechada", "Auditoria noturna", "Néon no telhado"),
            lessons.map { it.titlePt })
    }

    @Test fun `fourth Motel block repeats duel puzzle defense and closes with Double Neon at 140`() {
        val lessons = MotelService.load(File("content")).lessons.filter { it.ordinal in 131..140 }
        assertEquals((131..140).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.kind })
        assertEquals(listOf(2, 3, 4), lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(4, 5, 6), lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertEquals((131..139).map { false } + true, lessons.map { it.isDoubleNeon })
        assertEquals(listOf("Wake-Up Call", "Do Not Disturb", "Emergency Exit", "Room Change", "Hidden Key",
            "Quiet Corridor", "Morning Shift", "Final Reservation", "Fire Alarm", "Neon Sunrise"), lessons.map { it.titleEn })
        assertEquals(listOf("Chamada para acordar", "Não incomodar", "Saída segura", "Mudança de quarto", "Chave escondida",
            "Corredor tranquilo", "Turno da manhã", "Última reserva", "Alarme de incêndio", "Amanhecer néon"),
            lessons.map { it.titlePt })
    }

    @Test fun `fifth Motel block completes three duel puzzle defense cycles before the boss`() {
        val lessons = MotelService.load(File("content")).lessons.filter { it.ordinal in 141..149 }
        assertEquals((141..149).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense"),
            lessons.map { it.kind })
        assertEquals(listOf(2, 3, 4), lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(listOf(4, 5, 6), lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves })
        assertTrue(lessons.none { it.isDoubleNeon })
        assertEquals(listOf("Night Manager", "Quiet Please", "Fire Door", "Guest Upgrade", "Spare Key",
            "Late Rounds", "Morning Bell", "No Smoking", "Final Checkout"), lessons.map { it.titleEn })
        assertEquals(listOf("Gerente da noite", "Silêncio, por favor", "Porta corta-fogo", "Quarto melhor",
            "Chave suplente", "Ronda tardia", "Toque da manhã", "Proibido fumar", "Check-out final"), lessons.map { it.titlePt })
    }
}
