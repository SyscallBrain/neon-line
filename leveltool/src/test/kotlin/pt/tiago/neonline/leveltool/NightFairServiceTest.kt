package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NightFairServiceTest {
    @Test fun `night fair introduces five in a row on the large board`() {
        val recipes = NightFairService.load(File("content"))
        assertEquals((451..460).toList(), recipes.lessons.map { it.ordinal })
        assertEquals("tut_451_five", recipes.lessons.first().tutorialKey)
        assertEquals(listOf(9, 7, 5), listOf(recipes.lessons.first().width, recipes.lessons.first().height, recipes.lessons.first().connect))
        assertTrue(recipes.lessons.all { it.kind == "duel" })
        assertEquals((451..460).map { it == 460 }, recipes.lessons.map { it.isDoubleNeon })
        assertTrue(recipes.lessons.all { it.familyId.startsWith("night-fair-") })
    }

    @Test fun `night fair rejects a block without the novelty tutorial`() {
        val recipes = NightFairService.load(File("content"))
        assertThrows(IllegalArgumentException::class.java) {
            NightFairService.validate(recipes.copy(lessons = recipes.lessons.map { if (it.ordinal == 451) it.copy(tutorialKey = null) else it }))
        }
    }

    @Test fun `published night fair levels use wheel connect-five variants and share the tutorial`() {
        val levels = Files.levels(File("content")).filter { it.ordinal in 451..460 }
        assertEquals((451..460).toList(), levels.map { it.ordinal })
        assertTrue(levels.all { level -> level.variants.values.all { it.kind.type == "duel" } })
        assertEquals("tut_451_five", levels.first().tutorial?.steps?.single()?.textKey)
        assertTrue(Calibrator.isNoveltyIntroduction(451))
        assertTrue(levels.all { level -> level.variants.values.all { it.width == 9 && it.height == 7 && it.connect == 5 && it.rival == "wheel" } })
    }

}
