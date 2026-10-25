package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class BowlingBossTest {
    @Test fun `converts final Strike opening to certified boss while preserving its board`() {
        val parent = NioFiles.createTempDirectory("bowling-boss").toFile()
        val source = File("content")
        val base = File(parent, "base")
        try {
            BowlingBoss.run(source, base)

            val opening = Files.levels(source).single { it.ordinal == 199 }
            val boss = Files.levels(base).single { it.ordinal == 200 }
            assertEquals("stop-04", boss.stopId)
            assertEquals("bowling-boss", boss.familyId)
            assertEquals("Mestre da Pista", boss.titlePt)
            assertEquals("Strike Master", boss.titleEn)
            assertFalse(boss.isDoubleNeon)
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val ordinaryVariant = opening.variants.getValue(key)
                val bossVariant = boss.variants.getValue(key)
                assertEquals(ordinaryVariant.width, bossVariant.width)
                assertEquals(ordinaryVariant.height, bossVariant.height)
                assertEquals(ordinaryVariant.mask, bossVariant.mask)
                assertEquals(ordinaryVariant.pieces, bossVariant.pieces)
                assertEquals("boss", bossVariant.kind.type)
                assertEquals(3, bossVariant.kind.bestOf)
                assertEquals("strike", bossVariant.rival)
                assertTrue(bossVariant.bossRounds.isEmpty())
                assertTrue(Certificates.verify(boss, Certificates.decodeCertificate(
                    File(base, "certificates/${boss.id}/$key.json").readText())).isEmpty())
            }
        } finally { parent.deleteRecursively() }
    }
}
