package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AceCasinoFollowupServiceTest {
    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }

    @Test fun `second Ace Casino block uses ten fresh two-rule combinations`() {
        val recipes = AceCasinoFollowupService.load(File("content"))
        assertEquals((511..520).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        val combinations = recipes.lessons.map(::mechanics)
        val firstBlock = AceCasinoService.load(File("content")).lessons.map(::mechanics).toSet()
        assertEquals(10, combinations.toSet().size)
        assertTrue(combinations.none { it in firstBlock })
        assertTrue(recipes.lessons.all { mechanics(it).size == 2 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((511..520).map { it == 520 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `published followup variants replay against Ace with exactly two rules`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 511..520 }
        assertEquals((511..520).toList(), levels.map { it.ordinal })
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
