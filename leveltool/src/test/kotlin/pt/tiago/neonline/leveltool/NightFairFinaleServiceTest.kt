package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NightFairFinaleServiceTest {
    @Test fun `finale recipe carries the connect-five duel cadence through dawn`() {
        val recipes = NightFairFinaleService.load(File("content"))
        assertEquals((491..500).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.kind == "duel" && it.width == 9 && it.height == 7 && it.connect == 5 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((491..500).map { it == 500 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `published finale variants are certified wheel connect-five duels`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 491..500 }
        assertEquals((491..500).toList(), levels.map { it.ordinal })
        assertTrue(levels.filter { it.ordinal < 500 }.all { level -> level.variants.values.all { it.kind.type == "duel" } })
        assertTrue(levels.last().variants.values.all { it.kind.type == "boss" && it.kind.bestOf == 3 })
        assertTrue(levels.last().isDoubleNeon)
        assertTrue(levels.all { level -> level.variants.values.all { it.width == 9 && it.height == 7 && it.connect == 5 && it.rival == "wheel" } })
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            if (level.ordinal == 500) for (round in 2..3) {
                val roundCertificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key-round-$round.json").readText())
                assertTrue("${level.id}/$key-round-$round", Certificates.verify(level, roundCertificate).isEmpty())
            }
        }
    }

    @Test fun `night fair boss promotion keeps the final level double neon`() {
        val published = Files.levels(File("content")).single { it.ordinal == 500 }
        val source = published.copy(variants = published.variants.mapValues { (_, variant) ->
            variant.copy(kind = pt.tiago.neonline.core.content.KindDto("duel"), bossRounds = emptyList())
        })
        val boss = NightFairFinaleBoss.toBoss(source)
        assertTrue(boss.isDoubleNeon)
        assertEquals("night-fair-boss", boss.familyId)
        assertTrue(boss.variants.values.all { it.kind.type == "boss" && it.rival == "wheel" })
    }
}
