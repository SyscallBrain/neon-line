package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class KaraokeBossTest {
    @Test fun `level four hundred transforms the final Diva duel into the fading three-round boss`() {
        val source = Files.levels(File("content")).single { it.ordinal == 399 }
        val boss = KaraokeBoss.toBoss(source)
        assertEquals(400, boss.ordinal)
        assertEquals("level-0400", boss.id)
        assertEquals("stop-08", boss.stopId)
        assertEquals("karaoke-boss", boss.familyId)
        assertEquals("Diva's Final Encore", boss.titleEn)
        assertEquals("Último Encore da Diva", boss.titlePt)
        assertFalse(boss.isDoubleNeon)
        for (difficulty in Difficulty.entries) {
            val key = difficultyKey(difficulty)
            val sourceVariant = source.variants.getValue(key)
            val variant = boss.variants.getValue(key)
            assertEquals("boss", variant.kind.type)
            assertEquals(3, variant.kind.bestOf)
            assertEquals("diva", variant.rival)
            assertTrue(variant.ai.mirror)
            assertEquals(sourceVariant.rules, variant.rules)
            assertTrue(variant.bossRounds.isEmpty())
        }
    }

    @Test fun `boss staging preserves campaign prefix and adds distinct certified rounds`() {
        val parent = NioFiles.createTempDirectory("karaoke-boss").toFile()
        val source = File(parent, "source")
        val base = File(parent, "base")
        val output = File(parent, "boss")
        val frozen = File("content/levels/stop-08/level-0390.json").readBytes()
        try {
            File("content").copyRecursively(source)
            File(source, "levels/stop-08/level-0400.json").delete()
            File(source, "certificates/level-0400").deleteRecursively()
            for (ordinal in 401..600) {
                val id = "level-%04d".format(ordinal)
                val stop = when { ordinal <= 450 -> "stop-09"; ordinal <= 500 -> "stop-10"; ordinal <= 550 -> "stop-11"; else -> "stop-12" }
                File(source, "levels/$stop/$id.json").delete()
                File(source, "certificates/$id").deleteRecursively()
            }
            KaraokeBoss.run(source, base)
            BossContent.run(base, output, onlyOrdinals = setOf(400))
            assertArrayEquals(frozen, File(output, "levels/stop-08/level-0390.json").readBytes())
            val boss = Files.levels(output).single { it.ordinal == 400 }
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = boss.variants.getValue(key)
                assertEquals(listOf("P1", "P2", "P1"), variant.bossRounds.map { it.firstToMove })
                assertEquals(3, variant.bossRounds.map { it.pieces }.toSet().size)
                for (slot in 0..2) {
                    val name = if (slot == 0) key else "$key-round-${slot + 1}"
                    val certificate = Certificates.decodeCertificate(File(output, "certificates/${boss.id}/$name.json").readText())
                    assertTrue("$key round $slot", Certificates.verify(boss, certificate).isEmpty())
                }
            }
        } finally { parent.deleteRecursively() }
    }
}
