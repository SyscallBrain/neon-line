package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NightFairFollowupServiceTest {
    @Test fun `followup recipe opens the next connect-five duel block`() {
        val recipes = NightFairFollowupService.load(File("content"))
        assertEquals((461..470).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.kind == "duel" && it.width == 9 && it.height == 7 && it.connect == 5 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((461..470).map { it == 470 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `followup rejects an early double neon`() {
        val recipes = NightFairFollowupService.load(File("content"))
        assertThrows(IllegalArgumentException::class.java) {
            NightFairFollowupService.validate(recipes.copy(lessons = recipes.lessons.map {
                if (it.ordinal == 469) it.copy(isDoubleNeon = true) else it
            }))
        }
    }

    @Test fun `published followup variants are certified wheel connect-five duels`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 461..470 }
        assertEquals((461..470).toList(), levels.map { it.ordinal })
        assertTrue(levels.all { level -> level.variants.values.all { it.kind.type == "duel" } })
        assertTrue(levels.all { level -> level.variants.values.all { it.width == 9 && it.height == 7 && it.connect == 5 && it.rival == "wheel" } })
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
