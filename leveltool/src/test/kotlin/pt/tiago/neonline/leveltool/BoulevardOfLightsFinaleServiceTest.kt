package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.KindDto

class BoulevardOfLightsFinaleServiceTest {
    @Test fun `finale recipe carries the Queen mix and reserves Double Neon for the boss base`() {
        val lessons = BoulevardOfLightsFinaleService.load(File("content")).lessons
        assertEquals((591..600).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle"),
            lessons.take(9).map { it.kind })
        assertEquals(4, lessons.take(9).count { it.kind == "duel" })
        assertEquals(3, lessons.take(9).count { it.kind == "puzzle" })
        assertEquals(2, lessons.take(9).count { it.kind == "defense" })
        assertEquals(5, lessons.count { it.kind == "duel" })
        assertTrue(lessons.last().isDoubleNeon)
        assertTrue(lessons.dropLast(1).none { it.isDoubleNeon })
    }

    @Test fun `Queen boss promotion preserves final Double Neon duel rules`() {
        val published = Files.levels(File("content")).single { it.ordinal == 590 }
        val source = published.copy(
            id = "level-0600", ordinal = 600, titlePt = "A última luz da avenida",
            titleEn = "The Avenue's Last Light", familyId = "boulevard-double-neon-finale", isDoubleNeon = true,
            variants = published.variants.mapValues { (_, variant) ->
                variant.copy(kind = KindDto("duel"), bossRounds = emptyList(),
                    inventory = mapOf("P1" to mapOf("bomb" to 1, "wild" to 1), "P2" to mapOf("bomb" to 1, "wild" to 1)))
            },
        )
        val boss = BoulevardOfLightsFinaleBoss.toBoss(source)
        assertEquals("boulevard-of-lights-boss", boss.familyId)
        assertEquals("Rainha da Avenida", boss.titlePt)
        assertEquals("Queen of the Avenue", boss.titleEn)
        assertTrue(boss.isDoubleNeon)
        assertTrue(boss.variants.values.all { it.kind.type == "boss" && it.kind.bestOf == 3 && it.rival == "queen" })
        assertTrue(boss.variants.values.all { it.inventory.values.flatMap { it.keys }.toSet() == setOf("bomb", "wild") })
    }

    @Test fun `published finale has nine Queen levels and replayable three-round boss certificates`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 591..600 }
        assertEquals((591..600).toList(), levels.map { it.ordinal })
        assertTrue(levels.take(9).all { level -> level.variants.values.all { it.kind.type != "boss" && it.rival == "queen" } })
        assertTrue(levels.last().variants.values.all { it.kind.type == "boss" && it.kind.bestOf == 3 && it.rival == "queen" })
        assertTrue(levels.last().isDoubleNeon)
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            if (level.ordinal == 600) for (round in 2..3) {
                val roundCertificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key-round-$round.json").readText())
                assertTrue("${level.id}/$key-round-$round", Certificates.verify(level, roundCertificate).isEmpty())
            }
        }
    }
}
