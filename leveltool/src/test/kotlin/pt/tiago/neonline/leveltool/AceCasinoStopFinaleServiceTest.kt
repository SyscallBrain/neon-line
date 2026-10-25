package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.KindDto

class AceCasinoStopFinaleServiceTest {
    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }

    @Test fun `final Ace Casino recipe has nine combo duels and a Double Neon boss base`() {
        val recipes = AceCasinoStopFinaleService.load(File("content"))
        assertEquals((541..550).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.take(9).all { it.kind == "duel" && !it.isDoubleNeon })
        assertEquals("duel", recipes.lessons.last().kind)
        assertTrue(recipes.lessons.last().isDoubleNeon)
        assertEquals(10, recipes.lessons.map(::mechanics).toSet().size)
        assertTrue(recipes.lessons.all { mechanics(it).size == 2 })
        assertTrue(recipes.lessons.take(2).all { it.openingPairsMax <= 4 })
    }

    @Test fun `Ace boss promotion keeps the last hand Double Neon and both special rules`() {
        val published = Files.levels(File("content")).single { it.ordinal == 550 }
        val source = published.copy(variants = published.variants.mapValues { (_, variant) ->
            variant.copy(kind = KindDto("duel"), bossRounds = emptyList())
        })
        val boss = AceCasinoBoss.toBoss(source)
        assertEquals("ace-casino-boss", boss.familyId)
        assertEquals("A última mão do Ás", boss.titlePt)
        assertEquals("Ace's Last Hand", boss.titleEn)
        assertTrue(boss.isDoubleNeon)
        assertTrue(boss.variants.values.all { it.kind.type == "boss" && it.kind.bestOf == 3 && it.rival == "ace" })
        assertTrue(boss.variants.values.all { it.inventory.values.flatMap { it.keys }.toSet() == setOf("wild", "lock") })
    }

    @Test fun `published Casino finale has replayable certificates for all three Ace boss rounds`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 541..550 }
        assertEquals((541..550).toList(), levels.map { it.ordinal })
        assertTrue(levels.take(9).all { level -> level.variants.values.all { it.kind.type == "duel" && it.rival == "ace" } })
        assertTrue(levels.last().variants.values.all { it.kind.type == "boss" && it.kind.bestOf == 3 && it.rival == "ace" })
        assertTrue(levels.last().isDoubleNeon)
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            if (level.ordinal == 550) for (round in 2..3) {
                val roundCertificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key-round-$round.json").readText())
                assertTrue("${level.id}/$key-round-$round", Certificates.verify(level, roundCertificate).isEmpty())
            }
        }
    }
}
