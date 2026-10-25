package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*

class BossContentTest {
    private val root=File("content")
    private fun bosses()=Files.levels(root).filter { it.ordinal in setOf(50,100,150,200,250,300,350,400,450,500,550,600) }
    @Test fun `completed campaign publishes the Strike boss at level two hundred`() {
        val actual = Files.levels(root).filter { it.variants.values.any { variant -> variant.kind.type == "boss" } }.map { it.ordinal }.toSet()
        assertEquals(setOf(50,100,150,200,250,300,350,400,450,500,550,600), actual)
    }
    @Test fun `boss has three distinct alternating openings and every round has an actual rival witness`() {
        for(level in bosses()) {
            if(level.ordinal==100) {
                assertEquals("Chefe da Meia-Noite",level.titlePt)
                assertEquals("Midnight Diner boss",level.titleEn)
            }
            if(level.ordinal==150) {
                assertEquals("Chefe do Motel Flamingo",level.titlePt)
                assertEquals("Flamingo Motel boss",level.titleEn)
            }
            if(level.ordinal==200) {
                assertEquals("Mestre da Pista",level.titlePt)
                assertEquals("Strike Master",level.titleEn)
            }
            if(level.ordinal==250) {
                assertEquals("Projeção final",level.titlePt)
                assertEquals("Final Projection",level.titleEn)
            }
            if(level.ordinal==300) {
                assertEquals("Mestre da Centrífuga",level.titlePt)
                assertEquals("Centrifuge Master",level.titleEn)
            }
            if(level.ordinal==400) {
                assertEquals("Último Encore da Diva",level.titlePt)
                assertEquals("Diva's Final Encore",level.titleEn)
            }
            if(level.ordinal==450) {
                assertEquals("Arcada final do Pixel",level.titlePt)
                assertEquals("Pixel's Final Arcade",level.titleEn)
            }
            for((key,variant) in level.variants) {
            assertEquals(listOf("P1","P2","P1"),variant.bossRounds.map { it.firstToMove })
            assertEquals(3,variant.bossRounds.map { it.pieces }.toSet().size)
            val certs=(0..2).map { slot ->
                val name=if(slot==0) key else "$key-round-${slot+1}"
                val cert=Certificates.decodeCertificate(File(root,"certificates/${level.id}/$name.json").readText())
                assertEquals(variant.forBossRound(slot).seedHex,cert.seedHex)
                assertTrue(Certificates.verify(level,cert).isEmpty())
                cert
            }
            assertTrue(certs.map { it.humanMoves }.sortedDescending().take(2).sum()<=variant.stars.three)
            }
        }
    }
    @Test fun `round regeneration can target only selected bosses`() {
        val parent=java.nio.file.Files.createTempDirectory("boss-target").toFile()
        val output=File(parent,"stage")
        val frozen=File(root,"levels/stop-01/level-0050.json").readBytes()
        try {
            BossContent.run(root,output,onlyOrdinals=setOf(999))
            assertArrayEquals(frozen,File(output,"levels/stop-01/level-0050.json").readBytes())
        } finally { parent.deleteRecursively() }
    }
    @Test fun `validator rejects missing rounds and repeated starts`() {
        for(level in bosses()) {
            assertTrue(Validator.validateLevel(level.copy(variants=level.variants.mapValues { (_,v)->v.copy(bossRounds=emptyList()) })).any { it.contains("three rounds") })
            assertTrue(Validator.validateLevel(level.copy(variants=level.variants.mapValues { (_,v)->v.copy(bossRounds=v.bossRounds.map { it.copy(firstToMove="P1") }) })).any { it.contains("alternate") })
        }
    }
}
