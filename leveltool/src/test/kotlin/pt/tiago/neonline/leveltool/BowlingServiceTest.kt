package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine

class BowlingServiceTest {
    @Test fun `Bowling recipes define the third ten levels with dimensions objectives and edge masks`() {
        val lessons = Json.parseToJsonElement(File("content/templates/bowling.json").readText()).jsonObject
            .getValue("lessons").jsonArray.map { it.jsonObject }.filter { it.getValue("ordinal").jsonPrimitive.int in 171..180 }
        assertEquals((171..180).toList(), lessons.map { it.getValue("ordinal").jsonPrimitive.int })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(6 to 6, 5 to 4, 7 to 6, 8 to 7, 6 to 6, 8 to 7, 5 to 4, 8 to 7, 7 to 6, 8 to 7),
            lessons.map { it.getValue("width").jsonPrimitive.int to it.getValue("height").jsonPrimitive.int })
        assertEquals(listOf(
            listOf("XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", "X#XX#X", "X#XX#X"),
            listOf("#XXX#", "XXXXX", "XXXXX", "XXXXX"),
            listOf("XX#X#XX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "#XXXXX#", "#XXXXX#"),
            listOf("X#XXXX#X", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXX##XXX"),
            listOf("XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", "#XXXX#", "#XXXX#"),
            listOf("#XXXXXX#", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XX#XX#XX", "XX#XX#XX"),
            listOf("X#X#X", "XXXXX", "XXXXX", "XXXXX"),
            listOf("#XXXXXX#", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXX##XXX", "XXX##XXX"),
            listOf("XX#X#XX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "#XXXXX#", "#XXXXX#"),
            listOf("X#XXXX#X", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#"),
        ), lessons.map { it.getValue("mask").jsonArray.map { row -> row.jsonPrimitive.content } })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true),
            lessons.map { it.getValue("isDoubleNeon").jsonPrimitive.boolean })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "puzzle" }
            .map { it.getValue("puzzleMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6), listOf(4, 5, 6)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "defense" }
            .map { it.getValue("surviveAiMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertTrue(lessons.all { it.getValue("titleEn").jsonPrimitive.content.isNotBlank() && it.getValue("titlePt").jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `Bowling recipes define the fourth ten levels with dimensions objectives and edge masks`() {
        val lessons = Json.parseToJsonElement(File("content/templates/bowling.json").readText()).jsonObject
            .getValue("lessons").jsonArray.map { it.jsonObject }.filter { it.getValue("ordinal").jsonPrimitive.int in 181..190 }
        assertEquals((181..190).toList(), lessons.map { it.getValue("ordinal").jsonPrimitive.int })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(7 to 6, 6 to 6, 8 to 7, 5 to 4, 7 to 6, 6 to 6, 8 to 7, 5 to 4, 7 to 6, 8 to 7),
            lessons.map { it.getValue("width").jsonPrimitive.int to it.getValue("height").jsonPrimitive.int })
        assertEquals(listOf(
            listOf("X#XXX#X", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX"),
            listOf("XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", "#XXXX#", "#XXXX#"),
            listOf("XX#XX#XX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#"),
            listOf("XXXXX", "XXXXX", "XXXXX", "XX#XX"),
            listOf("#XXXXX#", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXX##XX"),
            listOf("X#XX#X", "X#XX#X", "XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX"),
            listOf("XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#", "#XXXXXX#"),
            listOf("X#X#X", "XXXXX", "XXXXX", "XX#XX"),
            listOf("#XXXXX#", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XX#XX#X", "XX#XX#X"),
            listOf("#XXXXXX#", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXX##XXX"),
        ), lessons.map { it.getValue("mask").jsonArray.map { row -> row.jsonPrimitive.content } })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true),
            lessons.map { it.getValue("isDoubleNeon").jsonPrimitive.boolean })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "puzzle" }
            .map { it.getValue("puzzleMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6), listOf(4, 5, 6)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "defense" }
            .map { it.getValue("surviveAiMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertTrue(lessons.all { it.getValue("titleEn").jsonPrimitive.content.isNotBlank() && it.getValue("titlePt").jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `Bowling recipes close Stop Four with nine lessons and the boss opening`() {
        val lessons = Json.parseToJsonElement(File("content/templates/bowling.json").readText()).jsonObject
            .getValue("lessons").jsonArray.map { it.jsonObject }.filter { it.getValue("ordinal").jsonPrimitive.int in 191..200 }
        assertEquals((191..200).toList(), lessons.map { it.getValue("ordinal").jsonPrimitive.int })
        assertEquals(listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "duel"),
            lessons.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(6 to 6, 8 to 7, 7 to 6, 5 to 4, 6 to 6, 8 to 7, 7 to 6, 5 to 4, 8 to 7, 8 to 7),
            lessons.map { it.getValue("width").jsonPrimitive.int to it.getValue("height").jsonPrimitive.int })
        assertEquals(listOf(
            listOf("XXX#XX", "XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX"),
            listOf("XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#", "#XXXXXX#"),
            listOf("X#XXX#X", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXX#XXX", "XXX#XXX"),
            listOf("XXXXX", "XXXXX", "XXXXX", "#XXX#"),
            listOf("#XXXX#", "#XXXX#", "XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX"),
            listOf("XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XX#XX#XX", "XX#XX#XX"),
            listOf("XX#X#XX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "#XXXXX#"),
            listOf("#XXX#", "XXXXX", "XXXXX", "XXXXX"),
            listOf("X#XXXX#X", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XX#XX#XX"),
            listOf("X#XXXX#X", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XX#XX#XX"),
        ), lessons.map { it.getValue("mask").jsonArray.map { row -> row.jsonPrimitive.content } })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, false),
            lessons.map { it.getValue("isDoubleNeon").jsonPrimitive.boolean })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4), listOf(2, 3, 4)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "puzzle" }
            .map { it.getValue("puzzleMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), lessons
            .filter { it.getValue("kind").jsonPrimitive.content == "defense" }
            .map { it.getValue("surviveAiMoves").jsonArray.map { value -> value.jsonPrimitive.int } })
        assertEquals("Final Approach", lessons[8].getValue("titleEn").jsonPrimitive.content)
        assertEquals("Strike Master Opening", lessons[9].getValue("titleEn").jsonPrimitive.content)
    }

    @Test fun `Bowling recipes extend the second block with combined edge obstacles`() {
        val lessons = Json.parseToJsonElement(File("content/templates/bowling.json").readText()).jsonObject
            .getValue("lessons").jsonArray.map { it.jsonObject }.filter { it.getValue("ordinal").jsonPrimitive.int in 161..170 }
        assertEquals((161..170).toList(), lessons.map { it.getValue("ordinal").jsonPrimitive.int })
        assertEquals(listOf("duel", "puzzle", "duel", "defense", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true),
            lessons.map { it.getValue("isDoubleNeon").jsonPrimitive.boolean })
        assertEquals(listOf(
            listOf("XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "X#XXX#X", "X#XXX#X"),
            listOf("XXXXX", "XXXXX", "XXXXX", "XXXXX"),
            listOf("XX#XX#XX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXX#XXXX"),
            listOf("XXXXXX", "XXXXXX", "XXXXXX", "XXXXXX", "XX##XX", "XX##XX"),
            listOf("XXXXXXX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "#XXXXX#", "#XXXXX#"),
            listOf("XXX##XXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#", "#XXXXXX#"),
            listOf("X#XXXX#X", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXX##XXX"),
            listOf("#XXXXXX#", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XX#XX#XX", "XX#XX#XX"),
            listOf("XX#X#XX", "XXXXXXX", "XXXXXXX", "XXXXXXX", "#XXXXX#", "#XXXXX#"),
            listOf("XX#XX#XX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "#XXXXXX#"),
        ), lessons.map { it.getValue("mask").jsonArray.map { row -> row.jsonPrimitive.content } })
        assertTrue(lessons.all { it.getValue("titleEn").jsonPrimitive.content.isNotBlank() && it.getValue("titlePt").jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `Bowling recipes define the first ten levels dimensions objectives tutorials and masks`() {
        val root = Json.parseToJsonElement(File("content/templates/bowling.json").readText()).jsonObject
        val lessons = root.getValue("lessons").jsonArray.map { it.jsonObject }.filter { it.getValue("ordinal").jsonPrimitive.int <= 160 }
        assertEquals((151..160).toList(), lessons.map { it.getValue("ordinal").jsonPrimitive.int })
        assertEquals(listOf("duel", "duel", "duel", "duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel"),
            lessons.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(listOf(2, 3, 4), listOf(2, 3, 4)), lessons.filter { it.getValue("kind").jsonPrimitive.content == "puzzle" }
            .map { it.getValue("puzzleMoves").jsonArray.map { moves -> moves.jsonPrimitive.int } })
        assertEquals(listOf(listOf(4, 5, 6), listOf(4, 5, 6)), lessons.filter { it.getValue("kind").jsonPrimitive.content == "defense" }
            .map { it.getValue("surviveAiMoves").jsonArray.map { moves -> moves.jsonPrimitive.int } })
        assertEquals(listOf(5 to 4, 6 to 6, 8 to 7, 7 to 6, 6 to 6, 6 to 6, 8 to 7, 8 to 7, 8 to 7, 8 to 7),
            lessons.map { it.getValue("width").jsonPrimitive.int to it.getValue("height").jsonPrimitive.int })
        assertEquals(listOf("tut_151_small_board", "tut_152_pillars", "tut_153_ceiling"),
            lessons.take(3).map { it.getValue("tutorialKey").jsonPrimitive.content })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, true),
            lessons.map { it.getValue("isDoubleNeon").jsonPrimitive.boolean })
        assertEquals(listOf("XXX##XXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX"),
            lessons[2].getValue("mask").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(lessons.all { it.getValue("titleEn").jsonPrimitive.content.isNotBlank() && it.getValue("titlePt").jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `staging certifies variable boards and tutorials while preserving the published prefix`() {
        val parent = NioFiles.createTempDirectory("bowling-stage").toFile()
        val source = File(parent, "source")
        val output = File(parent, "stage")
        try {
            File("content").copyRecursively(source)
            for (ordinal in 161..200) {
                val id = "level-%04d".format(ordinal)
                File(source, "levels/stop-04/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            for (ordinal in 201..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            Certifier.rebuildCatalog(source, 2, Files.levels(source))
            val frozen = source.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(source).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 160 } == true
                }
            }.associate { it.relativeTo(source).invariantSeparatorsPath to it.readBytes() }
            BowlingService.run(source, output)
            val levels = Files.levels(output)
            assertEquals((161..170).toList(), levels.filter { it.ordinal > 160 }.map { it.ordinal })
            for ((path, bytes) in frozen) assertTrue(path, File(output, path).readBytes().contentEquals(bytes))
            for (lesson in BowlingService.load(File("content")).lessons.filter { it.ordinal in 161..170 }) {
                val level = levels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-04", level.stopId)
                assertEquals("strike", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                assertEquals(lesson.tutorialKey, level.tutorial?.steps?.singleOrNull()?.textKey)
                for (difficulty in Difficulty.entries) {
                    val key = difficultyKey(difficulty)
                    val variant = level.variants.getValue(key)
                    assertEquals(lesson.width, variant.width)
                    assertEquals(lesson.height, variant.height)
                    assertEquals(lesson.mask, variant.mask)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                    val certificate = Certificates.decodeCertificate(File(output, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    val state = variant.toState(level.id, difficulty)
                    lesson.tutorialColumn?.let { column ->
                        assertEquals(Move.Drop(column), Certificates.parseMove(certificate.steps.first().move))
                        assertTrue(Move.Drop(column) in GameEngine.validMoves(state))
                    }
                    if (lesson.kind == "defense") assertTrue(DinerEntry.winning(state, Player.P2).isNotEmpty())
                    if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY)
                        assertEquals(1, ForcedWin(150_000).winningMoves(state, variant.kind.winInMoves!!)!!.size)
                }
            }

            val nextOutput = File(parent, "stage180")
            val prefixThrough170 = output.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(output).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 170 } == true
                }
            }.associate { it.relativeTo(output).invariantSeparatorsPath to it.readBytes() }
            BowlingService.run(output, nextOutput)
            val nextLevels = Files.levels(nextOutput)
            assertEquals((171..180).toList(), nextLevels.filter { it.ordinal > 170 }.map { it.ordinal })
            for ((path, bytes) in prefixThrough170)
                assertTrue("Changed published prefix: $path", File(nextOutput, path).readBytes().contentEquals(bytes))
            for (lesson in BowlingService.load(File("content")).lessons.filter { it.ordinal in 171..180 }) {
                val level = nextLevels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-04", level.stopId)
                assertEquals("strike", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                for (difficulty in Difficulty.entries) {
                    val key = difficultyKey(difficulty)
                    val variant = level.variants.getValue(key)
                    assertEquals(lesson.width, variant.width)
                    assertEquals(lesson.height, variant.height)
                    assertEquals(lesson.mask, variant.mask)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                    val certificate = Certificates.decodeCertificate(File(nextOutput, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    val state = variant.toState(level.id, difficulty)
                    if (lesson.kind == "defense") assertTrue(DinerEntry.winning(state, Player.P2).isNotEmpty())
                    if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY)
                        assertEquals(1, ForcedWin(150_000).winningMoves(state, variant.kind.winInMoves!!)!!.size)
                }
            }

            val finalOutput = File(parent, "stage190")
            val prefixThrough180 = nextOutput.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(nextOutput).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 180 } == true
                }
            }.associate { it.relativeTo(nextOutput).invariantSeparatorsPath to it.readBytes() }
            BowlingService.run(nextOutput, finalOutput)
            val finalLevels = Files.levels(finalOutput)
            assertEquals((181..190).toList(), finalLevels.filter { it.ordinal > 180 }.map { it.ordinal })
            for ((path, bytes) in prefixThrough180)
                assertTrue("Changed published prefix: $path", File(finalOutput, path).readBytes().contentEquals(bytes))
            for (lesson in BowlingService.load(File("content")).lessons.filter { it.ordinal in 181..190 }) {
                val level = finalLevels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-04", level.stopId)
                assertEquals("strike", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                for (difficulty in Difficulty.entries) {
                    val key = difficultyKey(difficulty)
                    val variant = level.variants.getValue(key)
                    assertEquals(lesson.width, variant.width)
                    assertEquals(lesson.height, variant.height)
                    assertEquals(lesson.mask, variant.mask)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                    val certificate = Certificates.decodeCertificate(File(finalOutput, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    val state = variant.toState(level.id, difficulty)
                    if (lesson.kind == "defense") assertTrue(DinerEntry.winning(state, Player.P2).isNotEmpty())
                    if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY)
                        assertEquals(1, ForcedWin(150_000).winningMoves(state, variant.kind.winInMoves!!)!!.size)
                }
            }

            val stopFourOutput = File(parent, "stage200")
            val prefixThrough190 = finalOutput.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(finalOutput).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 190 } == true
                }
            }.associate { it.relativeTo(finalOutput).invariantSeparatorsPath to it.readBytes() }
            BowlingService.run(finalOutput, stopFourOutput)
            val stopFourLevels = Files.levels(stopFourOutput)
            assertEquals((191..200).toList(), stopFourLevels.filter { it.ordinal > 190 }.map { it.ordinal })
            for ((path, bytes) in prefixThrough190)
                assertTrue("Changed published prefix: $path", File(stopFourOutput, path).readBytes().contentEquals(bytes))
            for (lesson in BowlingService.load(File("content")).lessons.filter { it.ordinal in 191..200 }) {
                val level = stopFourLevels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-04", level.stopId)
                assertEquals("strike", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                for (difficulty in Difficulty.entries) {
                    val key = difficultyKey(difficulty)
                    val variant = level.variants.getValue(key)
                    assertEquals(lesson.width, variant.width)
                    assertEquals(lesson.height, variant.height)
                    assertEquals(lesson.mask, variant.mask)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves?.get(difficulty.ordinal), variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves?.get(difficulty.ordinal), variant.kind.surviveAiMoves)
                    val certificate = Certificates.decodeCertificate(File(stopFourOutput, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    val state = variant.toState(level.id, difficulty)
                    if (lesson.kind == "defense") assertTrue(DinerEntry.winning(state, Player.P2).isNotEmpty())
                    if (lesson.kind == "puzzle" && difficulty != Difficulty.EASY)
                        assertEquals(1, ForcedWin(150_000).winningMoves(state, variant.kind.winInMoves!!)!!.size)
                }
            }
        } finally { parent.deleteRecursively() }
    }
}
