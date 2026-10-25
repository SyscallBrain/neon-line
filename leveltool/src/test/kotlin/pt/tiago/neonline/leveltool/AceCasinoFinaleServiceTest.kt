package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AceCasinoFinaleServiceTest {
    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }

    @Test fun `fourth Ace Casino block raises pressure across ten two-rule duels`() {
        val recipes = AceCasinoFinaleService.load(File("content"))
        assertEquals((531..540).toList(), recipes.lessons.map { it.ordinal })
        val combinations = recipes.lessons.map(::mechanics)
        assertEquals(listOf(
            setOf("fade", "clock"), setOf("popout", "rotation"), setOf("bomb", "wild"),
            setOf("clock", "lock"), setOf("fade", "wild"), setOf("bomb", "popout"),
            setOf("lock", "rotation"), setOf("bomb", "fade"), setOf("popout", "wild"),
            setOf("lock", "wild"),
        ), combinations)
        assertEquals(10, combinations.toSet().size)
        assertTrue(combinations.all { it.size == 2 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals(listOf(3,3,4,4,4,5,5,5,5,3), recipes.lessons.map { it.openingPairsMin })
        assertEquals(listOf(4,4,4,6,6,7,7,7,8,5), recipes.lessons.map { it.openingPairsMax })
        assertEquals((531..540).map { it == 540 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `published finale variants replay against Ace with exactly two rules`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 531..540 }
        assertEquals((531..540).toList(), levels.map { it.ordinal })
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
