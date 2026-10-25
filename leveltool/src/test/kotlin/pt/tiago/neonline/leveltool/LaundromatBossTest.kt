package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class LaundromatBossTest {
    @Test fun `Centrifuge boss follows level 299 with three distinct certified alternating rounds`() {
        val parent = NioFiles.createTempDirectory("laundromat-boss").toFile()
        val source = File("content")
            val campaign = File(parent, "campaign")
            check(source.copyRecursively(campaign))
            File(campaign, "levels/stop-06/level-0300.json").delete()
            File(campaign, "certificates/level-0300").deleteRecursively()
            for (ordinal in 301..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when {
                    ordinal <= 350 -> "stop-07"
                    ordinal <= 400 -> "stop-08"
                    ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12"
                }
                File(campaign, "levels/$stop/$id.json").delete()
                File(campaign, "certificates/$id").deleteRecursively()
            }
        val base = File(parent, "base")
        val output = File(parent, "rounds")
        try {
            LaundromatBoss.run(campaign, base)
            val opening = Files.levels(campaign).single { it.ordinal == 299 }
            val boss = Files.levels(base).single { it.ordinal == 300 }
            assertEquals("stop-06", boss.stopId)
            assertEquals("laundromat-boss", boss.familyId)
            assertEquals("Mestre da Centrífuga", boss.titlePt)
            assertEquals("Centrifuge Master", boss.titleEn)
            assertFalse(boss.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val ordinary = opening.variants.getValue(key)
                val variant = boss.variants.getValue(key)
                assertEquals(ordinary.pieces, variant.pieces)
                assertFalse(variant.rules.popOut)
                assertEquals("boss", variant.kind.type)
                assertEquals(3, variant.kind.bestOf)
                assertEquals("centrifuge", variant.rival)
                assertTrue(variant.bossRounds.isEmpty())
                assertTrue(Certificates.verify(boss, Certificates.decodeCertificate(
                    File(base, "certificates/${boss.id}/$key.json").readText())).isEmpty())
            }
            BossContent.run(base, output, onlyOrdinals = setOf(300))
            val complete = Files.levels(output).single { it.ordinal == 300 }
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
