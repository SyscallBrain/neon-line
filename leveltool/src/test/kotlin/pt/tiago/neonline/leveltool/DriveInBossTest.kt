package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class DriveInBossTest {
    @Test fun `Projectionist boss follows level 249 and certifies three alternating rounds`() {
        val parent = NioFiles.createTempDirectory("drivein-boss").toFile()
        val source = File(parent, "source")
        val base = File(parent, "base")
        val output = File(parent, "boss")
        try {
            File("content").copyRecursively(source)
            File(source, "levels/stop-05/level-0250.json").delete()
            File(source, "certificates/level-0250").deleteRecursively()
            for (ordinal in 251..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 300 -> "stop-06"; ordinal <= 350 -> "stop-07"; ordinal <= 400 -> "stop-08"; ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            DriveInBoss.run(source, base)
            val opening = Files.levels(source).single { it.ordinal == 249 }
            val boss = Files.levels(base).single { it.ordinal == 250 }
            assertEquals("stop-05", boss.stopId)
            assertEquals("drivein-boss", boss.familyId)
            assertEquals("Projeção final", boss.titlePt)
            assertEquals("Final Projection", boss.titleEn)
            assertFalse(boss.isDoubleNeon)
            assertFalse(boss.learningTags.contains("popout"))
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val ordinary = opening.variants.getValue(key)
                val variant = boss.variants.getValue(key)
                assertEquals(ordinary.width, variant.width)
                assertEquals(ordinary.height, variant.height)
                assertEquals(ordinary.mask, variant.mask)
                assertEquals(ordinary.pieces, variant.pieces)
                assertEquals(false, variant.rules.popOut)
                assertEquals("boss", variant.kind.type)
                assertEquals(3, variant.kind.bestOf)
                assertEquals("projectionist", variant.rival)
                assertTrue(variant.bossRounds.isEmpty())
                assertTrue(Certificates.verify(boss, Certificates.decodeCertificate(
                    File(base, "certificates/${boss.id}/$key.json").readText())).isEmpty())
            }

            BossContent.run(base, output, onlyOrdinals = setOf(250))
            val complete = Files.levels(output).single { it.ordinal == 250 }
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = complete.variants.getValue(key)
                assertEquals(listOf("P1", "P2", "P1"), variant.bossRounds.map { it.firstToMove })
                assertEquals(3, variant.bossRounds.map { it.pieces }.toSet().size)
                for (slot in 0..2) {
                    val name = if (slot == 0) key else "$key-round-${slot + 1}"
                    val cert = Certificates.decodeCertificate(File(output, "certificates/${complete.id}/$name.json").readText())
                    assertTrue("$key round $slot", Certificates.verify(complete, cert).isEmpty())
                }
            }
        } finally { parent.deleteRecursively() }
    }
}
