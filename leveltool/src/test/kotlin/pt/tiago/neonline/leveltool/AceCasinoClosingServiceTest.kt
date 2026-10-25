package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AceCasinoClosingServiceTest {
    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }

    @Test fun `third Ace Casino block uses varied two-rule duels and closes at 530`() {
        val recipes = AceCasinoClosingService.load(File("content"))
        assertEquals((521..530).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        val combinations = recipes.lessons.map(::mechanics)
        assertEquals(10, combinations.toSet().size)
        assertTrue(combinations.all { it.size == 2 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((521..530).map { it == 530 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `published closing variants replay against Ace with exactly two rules`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 521..530 }
        assertEquals((521..530).toList(), levels.map { it.ordinal })
        assertTrue(levels.all { level -> level.variants.values.all { it.rival == "ace" && it.width == 7 && it.height == 6 && it.connect == 4 } })
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val variant = level.variants.getValue(key)
            val count = (if (variant.rules.popOut) 1 else 0) + (if (variant.rules.rotateEvery != null) 1 else 0) +
                (if (variant.rules.fadeAfter != null) 1 else 0) + (if (variant.rules.moveClockSec != null) 1 else 0) +
                variant.inventory.values.flatMap { it.keys }.toSet().size
            assertEquals("${level.id}/$key mechanics", 2, count)
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
