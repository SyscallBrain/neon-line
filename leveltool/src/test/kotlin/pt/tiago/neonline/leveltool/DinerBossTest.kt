package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files as NioFiles
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class DinerBossTest {
    @Test fun `stages a level one hundred Dolores boss with the certified level ninety opening`() {
        val parent=NioFiles.createTempDirectory("diner-boss").toFile()
        val output=File(parent,"stage")
        try {
            DinerBoss.run(File("content"),output)
            val level=Files.levels(output).first { it.ordinal==100 }
            assertEquals("stop-02",level.stopId)
            assertEquals("diner-boss",level.familyId)
            assertEquals("Chefe da Meia-Noite",level.titlePt)
            assertEquals("Midnight Diner boss",level.titleEn)
            assertFalse(level.isDoubleNeon)
            for(difficulty in Difficulty.entries) {
                val key=difficultyKey(difficulty)
                val variant=level.variants.getValue(key)
                assertEquals("boss",variant.kind.type)
                assertEquals(3,variant.kind.bestOf)
                assertEquals("dolores",variant.rival)
                val cert=Certificates.decodeCertificate(File(output,"certificates/${level.id}/$key.json").readText())
                assertTrue(Certificates.verify(level,cert).isEmpty())
            }
        } finally { parent.deleteRecursively() }
    }
}
