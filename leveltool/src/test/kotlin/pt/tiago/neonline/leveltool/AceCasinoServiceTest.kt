package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AceCasinoServiceTest {
    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }

    @Test fun `first Ace Casino block combines exactly two rules in every lesson`() {
        val recipes = AceCasinoService.load(File("content"))
        assertEquals((501..510).toList(), recipes.lessons.map { it.ordinal })
        assertTrue(recipes.lessons.all { it.width == 7 && it.height == 6 && it.connect == 4 })
        assertTrue(recipes.lessons.all { mechanics(it).size == 2 })
        assertTrue(recipes.lessons.take(3).all { it.openingPairsMax <= 4 })
        assertEquals((501..510).map { it == 510 }, recipes.lessons.map { it.isDoubleNeon })
    }

    @Test fun `Ace Casino refuses a lesson with fewer than two mechanics`() {
        val recipes = AceCasinoService.load(File("content"))
        assertThrows(IllegalArgumentException::class.java) {
            AceCasinoService.validate(recipes.copy(lessons = recipes.lessons.map {
                if (it.ordinal == 501) it.copy(moveClockSec = null) else it
            }))
        }
    }

    @Test fun `published Ace Casino variants are certified against Ace`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 501..510 }
        assertEquals((501..510).toList(), levels.map { it.ordinal })
        assertTrue(levels.all { level -> level.variants.values.all { it.rival == "ace" && it.width == 7 && it.height == 6 && it.connect == 4 } })
        assertTrue(levels.all { level -> level.variants.values.all { variant ->
            val count = (if (variant.rules.popOut) 1 else 0) + (if (variant.rules.rotateEvery != null) 1 else 0) +
                (if (variant.rules.fadeAfter != null) 1 else 0) + (if (variant.rules.moveClockSec != null) 1 else 0) +
                variant.inventory.values.flatMap { it.keys }.toSet().size
            count == 2
        } })
        for (level in levels) for (key in listOf("easy", "medium", "hard")) {
            val certificate = Certificates.decodeCertificate(File("content/certificates/${level.id}/$key.json").readText())
            assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
        }
    }
}
