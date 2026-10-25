package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty

class PixelArcadeServiceTest {
    @Test fun `fourth Pixel Arcade block escalates specials and ends with a wild Double Neon`() {
        val lessons = PixelArcadeService.load(File("content")).lessons.filter { it.ordinal in 431..440 }
        assertEquals((431..440).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("puzzle", "duel", "duel", "defense", "puzzle", "duel", "duel", "defense", "puzzle", "duel"), lessons.map { it.kind })
        assertEquals(listOf(emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), emptyMap(), mapOf("wild" to 1)), lessons.map { it.specialInventory })
        assertEquals(listOf(440), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
        assertTrue(lessons[0].openingPairsMax <= 4 && lessons[1].openingPairsMax <= 4)
    }

    @Test fun `final Pixel Arcade block revisits every special and prepares the Pixel boss`() {
        val lessons = PixelArcadeService.load(File("content")).lessons.filter { it.ordinal in 441..450 }
        assertEquals((441..450).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("puzzle", "duel", "duel", "defense", "puzzle", "duel", "duel", "defense", "duel", "duel"), lessons.map { it.kind })
        assertEquals(listOf(emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), mapOf("wild" to 1), emptyMap()), lessons.map { it.specialInventory })
        assertTrue(lessons.none { it.isDoubleNeon })
        assertTrue(lessons[0].openingPairsMax <= 4 && lessons[1].openingPairsMax <= 4)
        val published = Files.levels(File("content")).single { it.ordinal == 450 }
        val source = published.copy(variants = published.variants.mapValues { (_, variant) ->
                variant.copy(kind = KindDto("duel"), bossRounds = emptyList())
            })
        val boss = PixelArcadeBoss.toBoss(source)
        assertEquals("boss", boss.variants.getValue("easy").kind.type)
        assertEquals(3, boss.variants.getValue("easy").kind.bestOf)
        assertEquals("pixel", boss.variants.getValue("easy").rival)
    }

    @Test fun `third Pixel Arcade block revisits the specials and closes with Double Neon`() {
        val lessons = PixelArcadeService.load(File("content")).lessons.filter { it.ordinal in 421..430 }
        assertEquals((421..430).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "puzzle", "duel", "defense", "duel", "puzzle", "duel", "defense", "duel"), lessons.map { it.kind })
        assertEquals(listOf(emptyMap(), emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), mapOf("bomb" to 1), emptyMap(), mapOf("wild" to 1), emptyMap(), emptyMap()), lessons.map { it.specialInventory })
        assertEquals(listOf(430), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
        assertTrue(lessons[0].openingPairsMax <= 4 && lessons[1].openingPairsMax <= 4)
    }

    @Test fun `second Pixel Arcade block introduces lock in its easy Double Neon tutorial`() {
        val lessons = PixelArcadeService.load(File("content")).lessons.filter { it.ordinal in 411..420 }
        assertEquals((411..420).toList(), lessons.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "duel", "defense", "puzzle", "duel", "defense", "puzzle", "duel", "duel"), lessons.map { it.kind })
        assertEquals(listOf(emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), mapOf("wild" to 1), emptyMap(), emptyMap(), emptyMap(), mapOf("lock" to 1)), lessons.map { it.specialInventory })
        assertEquals("tut_420_lock", lessons.last().tutorialKey)
        assertEquals(listOf(420), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
        assertEquals(listOf("duel", "duel", "duel"), lessons.last().kindByDifficulty ?: List(3) { lessons.last().kind })
        assertTrue(Calibrator.isNoveltyIntroduction(420))
    }

    @Test fun `recipe introduces bombs then the wildcard with an easy opening lesson`() {
        val lessons = PixelArcadeService.load(File("content")).lessons
        val opening = lessons.take(10)
        assertEquals((401..410).toList(), opening.map { it.ordinal })
        assertEquals(listOf("duel", "puzzle", "duel", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"), opening.map { it.kind })
        assertEquals(listOf(mapOf("bomb" to 1), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), emptyMap(), mapOf("wild" to 1)), opening.map { it.specialInventory })
        assertEquals("tut_401_bomb", opening.first().tutorialKey)
        assertEquals("tut_410_wild", opening.last().tutorialKey)
        assertEquals(List(3) { listOf(2, 2, 2) }, opening.filter { it.kind == "puzzle" }.map { it.puzzleMoves })
        assertEquals(List(3) { listOf("puzzle", "puzzle", "duel") }, opening.filter { it.kind == "puzzle" }.map { it.kindByDifficulty })
        assertTrue(Calibrator.isNoveltyIntroduction(401))
        assertTrue(Calibrator.isNoveltyIntroduction(410))
        assertEquals(listOf(410, 420, 430, 440), lessons.filter { it.isDoubleNeon }.map { it.ordinal })
    }

    @Test fun `published Pixel Arcade variants grant their planned specials and certified tutorials`() {
        val root = File("content")
        val levels = Files.levels(root)
        assertEquals((1..600).toList(), levels.map { it.ordinal })
        for (lesson in PixelArcadeService.load(root).lessons) {
            val level = levels.single { it.ordinal == lesson.ordinal }
            assertEquals("stop-09", level.stopId)
            assertEquals("pixel", level.variants.getValue("easy").rival)
            assertTrue(level.learningTags.contains("pixel-arcade"))
            assertEquals(lesson.specialInventory.keys.any { it == "bomb" }, level.learningTags.contains("bomb"))
            assertTrue(level.learningTags.containsAll(lesson.specialInventory.keys))
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val variant = level.variants.getValue(key)
                assertEquals(if (lesson.ordinal == 450) "boss" else lesson.kindByDifficulty?.get(difficulty.ordinal) ?: lesson.kind, variant.kind.type)
                assertEquals(lesson.specialInventory, variant.inventory["P1"].orEmpty())
                assertEquals(lesson.specialInventory, variant.inventory["P2"].orEmpty())
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$key.json").readText())
                assertTrue("${level.id}/$key", Certificates.verify(level, certificate).isEmpty())
            }
        }
        assertEquals(1_800, levels.size * 3)
        assertEquals(1_872, publishedCertificateCount(root))
    }
}
