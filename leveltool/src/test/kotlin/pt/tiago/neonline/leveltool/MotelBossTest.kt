package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class MotelBossTest {
    @Test fun `creates a certified Flamingo boss from level 149 and stages three playable rounds`() {
        val parent = NioFiles.createTempDirectory("motel-boss").toFile()
        val source = File(parent, "source")
        val ordinary = File(parent, "ordinary")
        val base = File(parent, "base")
        val output = File(parent, "boss")
        try {
            File("content").copyRecursively(source)
            for (ordinal in 141..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 150 -> "stop-03"; ordinal <= 200 -> "stop-04"; ordinal <= 250 -> "stop-05"; ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            Certifier.rebuildCatalog(source, 2, Files.levels(source))
            MotelService.run(source, ordinary)
            MotelBoss.run(ordinary, base)
            val baseLevel = Files.levels(base).single { it.ordinal == 150 }
            assertEquals("stop-03", baseLevel.stopId)
            assertEquals("motel-boss", baseLevel.familyId)
            assertEquals("Chefe do Motel Flamingo", baseLevel.titlePt)
            assertEquals("Flamingo Motel boss", baseLevel.titleEn)
            assertFalse(baseLevel.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = baseLevel.variants.getValue(key)
                assertEquals("boss", variant.kind.type)
                assertEquals(3, variant.kind.bestOf)
                assertEquals("flamingo", variant.rival)
                assertTrue(Certificates.verify(baseLevel,
                    Certificates.decodeCertificate(File(base, "certificates/${baseLevel.id}/$key.json").readText())).isEmpty())
            }
            BossContent.run(base, output, onlyOrdinals = setOf(150))
            val level = Files.levels(output).single { it.ordinal == 150 }
            assertEquals(listOf("P1", "P2", "P1"), level.variants.getValue("easy").bossRounds.map { it.firstToMove })
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals(3, variant.bossRounds.map { it.pieces }.toSet().size)
                for (slot in 0..2) {
                    val name = if (slot == 0) key else "$key-round-${slot + 1}"
                    val certificate = Certificates.decodeCertificate(File(output, "certificates/${level.id}/$name.json").readText())
                    assertTrue("$key round $slot", Certificates.verify(level, certificate).isEmpty())
                }
            }
        } finally { parent.deleteRecursively() }
    }
}
