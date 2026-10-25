package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NightFairCodaServiceTest {
    @Test fun `coda recipe continues the connect-five duel cadence`() {
        val recipes = NightFairCodaService.load(File("content"))
        assertEquals((481..490).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.kind == "duel" && it.width == 9 && it.height == 7 && it.connect == 5 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((481..490).map { it == 490 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `coda rejects double neon before its final level`() {
        val recipes = NightFairCodaService.load(File("content"))
        assertThrows(IllegalArgumentException::class.java) {
            NightFairCodaService.validate(recipes.copy(lessons = recipes.lessons.map {
                if (it.ordinal == 489) it.copy(isDoubleNeon = true) else it
            }))
        }
    }

    @Test fun `published coda variants are certified wheel connect-five duels`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 481..490 }
        assertEquals((481..490).toList(), levels.map { it.ordinal })
        assertTrue(levels.all { level -> level.variants.values.all { it.kind.type == "duel" } })
        assertTrue(levels.all { level -> level.variants.values.all { it.width == 9 && it.height == 7 && it.connect == 5 && it.rival == "wheel" } })
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
