package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.content.CatalogEntryDto
import pt.tiago.neonline.core.content.PieceDto
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.levelJson
import pt.tiago.neonline.core.content.toState

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.rules.GameEngine
import java.io.File

class ContentPipelineTest {

    private val root = File("content")

    private fun levels(): List<LevelFileDto> = File(root, "levels").walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .map { Certificates.decodeLevel(it.readText()) }
        .sortedBy { it.ordinal }
        .toList()

    @Test
    fun `the published catalog validates strictly`() {
        val files = File(root, "levels").walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { file ->
                val path = file.invariantSeparatorsPath.removePrefix("content/")
                path to Certificates.decodeLevel(file.readText())
            }
            .toMap()
        val catalog = kotlinx.serialization.json.Json.decodeFromString(
            CatalogDto.serializer(),
            File(root, "catalog.json").readText(),
        )
        val errors = Validator.validateCatalog(catalog, files)
        assertTrue("catalog errors: $errors", errors.isEmpty())
    }

    @Test
    fun `published catalog contains the Boulevard finale through Queen boss at level 600`() {
        val catalog = kotlinx.serialization.json.Json.decodeFromString(CatalogDto.serializer(), File(root, "catalog.json").readText())
        assertEquals(600, catalog.levels.size)
        assertEquals((1..600).toList(), levels().map { it.ordinal })
        assertEquals(1_872, publishedCertificateCount(root))
    }

    @Test
    fun `strict catalog rejects a level beyond the published Boulevard boundary`() {
        val files = File(root, "levels").walkTopDown().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to Certificates.decodeLevel(it.readText()) }.toMutableMap()
        val catalog = kotlinx.serialization.json.Json.decodeFromString(CatalogDto.serializer(), File(root, "catalog.json").readText())
        val last = files.getValue("levels/stop-12/level-0600.json")
        val path = "levels/stop-12/level-0601.json"
        val extra = last.copy(id = "level-0601", ordinal = 601)
        files[path] = extra
        val entry = CatalogEntryDto(path, extra.revision, sha256(levelJson(extra)))
        val errors = Validator.validateCatalog(catalog.copy(levels = catalog.levels + entry), files)
        assertTrue("expected level 601 to be rejected: $errors", errors.any { "outside its stop range" in it })
    }

    @Test
    fun `every certificate replays to a three star win`() {
        var checked = 0
        for (level in levels()) {
            for (key in listOf("easy", "medium", "hard")) {
                val certFile = File(root, "certificates/${level.id}/$key.json")
                if (!certFile.isFile) continue
                val certificate = Certificates.decodeCertificate(certFile.readText())
                val errors = Certificates.verify(level, certificate)
                assertTrue("$key of ${level.id} failed: $errors", errors.isEmpty())
                assertEquals(3, certificate.stars)
                checked++
            }
        }
        assertTrue("checked too few certificates: $checked", checked >= 9)
    }

    @Test
    fun `each level has the three difficulties`() {
        for (level in levels()) {
            assertEquals(setOf("easy", "medium", "hard"), level.variants.keys)
            assertFalse(level.titleEn.isBlank())
            assertFalse(level.titlePt.isBlank())
        }
    }

    @Test
    fun `a solved certificate can be regenerated from the level`() {
        val level = levels().first()
        for (difficulty in Difficulty.entries) {
            val certificate = Certificates.solve(level, difficulty)
            assertNotNull("no certificate for ${level.id}/$difficulty", certificate)
            assertEquals(3, certificate!!.stars)
            assertTrue(Certificates.verify(level, certificate).isEmpty())
        }
    }

    @Test
    fun `validation catches a broken level`() {
        val level = levels().first()
        val broken = level.copy(
            variants = level.variants.mapValues { (_, variant) ->
                variant.copy(stars = variant.stars.copy(three = variant.stars.two + 1))
            },
        )
        assertTrue(Validator.validateLevel(broken).isNotEmpty())
        val floating = level.copy(
            variants = level.variants.mapValues { (_, variant) ->
                variant.copy(pieces = variant.pieces + PieceDto(r = 0, c = 0, owner = "P1"))
            },
        )
        assertTrue(Validator.validateLevel(floating).isNotEmpty())
    }
    @Test
    fun `all published tutorial guides start with their variant certified move`() {
        var count = 0
        for (level in levels().filter { it.tutorial != null }) {
            for (difficulty in level.variants.keys) {
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$difficulty.json").readText())
                val guide = certifiedTutorial(level, difficulty, certificate)!!
                val first = certificate.steps[if (level.variants.getValue(difficulty).firstToMove == "P1") 0 else 1]
                val step = guide.steps.first()
                if (step.textKey == "tut_301_clock") {
                    assertTrue(step.forcedCol == null)
                    assertTrue(step.highlightCols.isEmpty())
                } else if (step.textKey in setOf("tut_351_fade", "tut_401_bomb", "tut_410_wild", "tut_420_lock", "tut_451_five")) {
                    assertTrue(step.forcedCol == null)
                    assertTrue(step.highlightCols.isEmpty())
                    val variant = level.variants.getValue(difficulty)
                    when (step.textKey) {
                        "tut_351_fade" -> assertNotNull(variant.rules.fadeAfter)
                        "tut_401_bomb" -> assertTrue(variant.inventory.values.any { it["bomb"] == 1 })
                        "tut_410_wild" -> assertTrue(variant.inventory.values.any { it["wild"] == 1 })
                        "tut_420_lock" -> assertTrue(variant.inventory.values.any { it["lock"] == 1 })
                        "tut_451_five" -> { assertEquals(9, variant.width); assertEquals(7, variant.height); assertEquals(5, variant.connect) }
                    }
                } else if (step.forcedCol == null) {
                    assertEquals("tut_201_popout", step.textKey)
                    assertEquals(listOf(3), step.highlightCols)
                    val state = level.variants.getValue(difficulty).toState(level.id, Difficulty.valueOf(difficulty.uppercase()))
                    assertTrue(step.highlightCols.any { Move.Pop(it) in GameEngine.validMoves(state) })
                } else {
                    val col = requireNotNull(step.forcedCol)
                    assertEquals("Drop(col=$col, kind=NORMAL)", first.move)
                    assertEquals(listOf(col), step.highlightCols)
                }
                count++
            }
        }
        assertEquals(66, count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tutorial cannot use the certificate from another variant`() {
        val level = levels().first()
        val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/hard.json").readText())
        certifiedTutorial(level, "easy", certificate)
    }
}
