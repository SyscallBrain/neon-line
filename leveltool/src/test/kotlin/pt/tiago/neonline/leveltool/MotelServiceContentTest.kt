package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.Move

class MotelServiceContentTest {
    @Test fun `generates the full first Motel block when the source campaign ends at 100`() {
        val parent = NioFiles.createTempDirectory("motel-from-100").toFile()
        val source = File(parent, "source")
        val output = File(parent, "stage")
        try {
            File("content").copyRecursively(source)
            for (ordinal in 101..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 150 -> "stop-03"; ordinal <= 200 -> "stop-04"; ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            MotelService.run(source, output)
            assertEquals((1..149).toList(), Files.levels(output).map { it.ordinal })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun `stages missing Flamingo levels with replayable certificates and preserves the published prefix`() {
        val parent = NioFiles.createTempDirectory("motel-stage").toFile()
        val source = File(parent, "source")
        val output = File(parent, "stage")
        try {
            val contentRoot = File("content")
            contentRoot.copyRecursively(source)
            for (ordinal in 131..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 150 -> "stop-03"; ordinal <= 200 -> "stop-04"; ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            val frozen = contentRoot.walkTopDown().filter { file ->
                file.isFile && file.relativeTo(contentRoot).invariantSeparatorsPath.let { path ->
                    (path.startsWith("levels/") || path.startsWith("certificates/")) &&
                        Regex("level-([0-9]{4})").find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 130 } == true
                }
            }.associate { it.relativeTo(contentRoot).invariantSeparatorsPath to it.readBytes() }
            MotelService.run(source, output)
            val levels = Files.levels(output)
            assertEquals((1..149).toList(), levels.map { it.ordinal })
            for ((path, bytes) in frozen) assertTrue(path, File(output, path).readBytes().contentEquals(bytes))
            for (lesson in MotelService.load(File("content")).lessons) {
                val level = levels.single { it.ordinal == lesson.ordinal }
                assertEquals("stop-03", level.stopId)
                assertEquals(lesson.titlePt, level.titlePt)
                assertEquals(lesson.titleEn, level.titleEn)
                assertEquals("flamingo", level.variants.getValue("easy").rival)
                assertEquals(lesson.isDoubleNeon, level.isDoubleNeon)
                assertEquals(lesson.tutorialKey, level.tutorial?.steps?.singleOrNull()?.textKey)
                for (difficulty in Difficulty.entries) {
                    val key = difficulty.name.lowercase()
                    val variant = level.variants.getValue(key)
                    assertEquals(lesson.kind, variant.kind.type)
                    assertEquals(lesson.puzzleMoves, variant.kind.winInMoves)
                    assertEquals(lesson.surviveAiMoves, variant.kind.surviveAiMoves)
                    assertTrue("${level.id}/$key human rate", variant.expectedMetrics.humanWinRate in 0.0..1.0)
                    assertEquals(lesson.shapeRule, variant.kind.shapeRule)
                    assertEquals(lesson.shapeRule, variant.shapeRule)
                    assertEquals(lesson.targetCell?.let { listOf(it) } ?: emptyList<Int>(), variant.targets)
                    val certificate = Certificates.decodeCertificate(File(output, "certificates/${level.id}/$key.json").readText())
                    assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
                    if (lesson.ordinal == 101) assertEquals(Move.Drop(3), Certificates.parseMove(certificate.steps.first().move))
                }
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
