package pt.tiago.neonline.data

import pt.tiago.neonline.core.content.toState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogRepositoryTest {
    @Test fun `Pixel lock introduction resolves to its own tutorial copy`() {
        assertNotEquals(
            pt.tiago.neonline.R.string.tutorial_generic,
            pt.tiago.neonline.ui.screens.tutorialTextResource("tut_420_lock"),
        )
    }

    @Test fun `Night Fair five in a row introduction resolves to its own tutorial copy`() {
        assertNotEquals(pt.tiago.neonline.R.string.tutorial_generic,
            pt.tiago.neonline.ui.screens.tutorialTextResource("tut_451_five"))
    }

    private fun source(tamper: String? = null): AssetSource {
        val root = listOf(File("../content"), File("content")).first { File(it, "catalog.json").isFile }
        val texts = root.walkTopDown().filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toMutableMap()
        texts[CatalogRepository.CATALOG_PATH] = File(root, "catalog.json").readText()
        val manifest = texts.entries.joinToString("\n") { (path, text) -> "$path=${CatalogRepository.sha256(text)}" }
        if (tamper != null) texts[tamper] = "{}"
        texts[CatalogRepository.MANIFEST_PATH] = manifest
        return object : AssetSource { override fun readText(path: String) = texts[path] }
    }
    @Test fun `published catalog loads all five hundred ninety levels using shared schema`() {
        val catalog = CatalogRepository(source()).load()!!
        assertEquals((1..600).toList(), catalog.levels.map { it.ordinal })
        assertEquals(1_800, catalog.levels.size * 3)
        assertEquals(5_400, catalog.levels.size * 3 * 3)
        assertEquals("level-0001", catalog.levels.first().id)
    }
    @Test fun `damaged catalog or level is refused`() {
        assertNull(CatalogRepository(source(CatalogRepository.CATALOG_PATH)).load())
        assertNull(CatalogRepository(source("levels/stop-01/level-0001.json")).load())
    }
    @Test fun `exported guides are hashed localized and legal in all sixty variants`() {
        val root = listOf(File("build/generated/neonAssets"), File("app/build/generated/neonAssets")).first { it.isDirectory }
        val repository = CatalogRepository(object : AssetSource {
            override fun readText(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()
        })
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val catalog = repository.load()!!
        var checked = 0
        (catalog.levels.take(10) + catalog.levels.filter { it.ordinal == 51 || it.ordinal == 101 || it.ordinal in 151..153 || it.ordinal == 201 || it.ordinal == 351 || it.ordinal == 401 || it.ordinal == 410 || it.ordinal == 420 }).forEach { entry ->
            val level = json.decodeFromString(pt.tiago.neonline.core.content.LevelFileDto.serializer(), repository.readLevel(entry.path)!!)
            pt.tiago.neonline.core.model.Difficulty.entries.forEach { difficulty ->
                val key = pt.tiago.neonline.core.content.difficultyKey(difficulty)
                val guide = json.decodeFromString(pt.tiago.neonline.core.content.TutorialDto.serializer(), repository.readLevel("tutorials/${entry.id}/$key.json")!!)
                val step = guide.steps.first()
                assertNotEquals(pt.tiago.neonline.R.string.tutorial_generic, pt.tiago.neonline.ui.screens.tutorialTextResource(step.textKey))
                val start = level.variants.getValue(key).toState(level.id, difficulty)
                if (step.textKey in setOf("tut_351_fade", "tut_401_bomb", "tut_410_wild", "tut_420_lock")) {
                    assertNull(step.forcedCol)
                    assertTrue(step.highlightCols.isEmpty())
                    val variant = level.variants.getValue(key)
                    when (step.textKey) {
                        "tut_351_fade" -> assertNotNull(variant.rules.fadeAfter)
                        "tut_401_bomb" -> assertTrue(variant.inventory.values.any { it["bomb"] == 1 })
                        "tut_410_wild" -> assertTrue(variant.inventory.values.any { it["wild"] == 1 })
                        "tut_420_lock" -> assertTrue(variant.inventory.values.any { it["lock"] == 1 })
                    }
                } else {
                    val legal = step.forcedCol?.let { pt.tiago.neonline.core.model.Move.Drop(it) in pt.tiago.neonline.core.rules.GameEngine.validMoves(start) }
                        ?: step.highlightCols.any { pt.tiago.neonline.core.model.Move.Pop(it) in pt.tiago.neonline.core.rules.GameEngine.validMoves(start) }
                    assertTrue(legal)
                }
                checked++
            }
        }
        assertEquals(60, checked)
    }
}
