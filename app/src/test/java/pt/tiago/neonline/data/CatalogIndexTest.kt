package pt.tiago.neonline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogIndexTest {

    private val root = listOf(File("build/generated/neonAssets"), File("app/build/generated/neonAssets")).first { it.isDirectory }

    private class MemoryIndex(override var buildId: String = "build-1") : CatalogIndexStore {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
    }

    private fun repository(index: CatalogIndexStore, reads: IntArray = IntArray(1)) = CatalogRepository(object : AssetSource {
        override fun readText(path: String): String? { reads[0]++; return File(root, path).takeIf { it.isFile }?.readText() }
    }, index)

    @Test
    fun `a verified catalog is restored from the index without reading every level`() {
        val index = MemoryIndex()
        val first = repository(index)
        val catalog = assertNotNull(first.load()).let { first.load()!! }
        assertFalse(first.restoredFromIndex)
        assertEquals(2, index.files.size)

        val reads = IntArray(1)
        val second = repository(index, reads)
        assertEquals(catalog, second.load())
        assertTrue(second.restoredFromIndex)
        assertEquals(1, reads[0]) // only the manifest
        // Map and rival metadata come from the second index file, still without reading levels.
        assertEquals(first.campaignLevels(), second.campaignLevels())
        assertEquals(first.rivalLevels(), second.rivalLevels())
        assertEquals(1, reads[0])
    }

    @Test
    fun `a new install or a damaged index triggers a full verification`() {
        val index = MemoryIndex()
        repository(index).load()
        index.buildId = "build-2"
        val reinstalled = repository(index)
        assertNotNull(reinstalled.load())
        assertFalse(reinstalled.restoredFromIndex)

        index.files.keys.forEach { index.files[it] = "{ broken" }
        val damaged = repository(index)
        assertNotNull(damaged.load())
        assertFalse(damaged.restoredFromIndex)
        assertTrue(repository(index).also { it.load() }.restoredFromIndex)
    }

    @Test
    fun `damaged metadata alone is rebuilt by a full verification`() {
        val index = MemoryIndex()
        val reference = repository(index)
        val expected = reference.campaignLevels()
        val metadata = index.files.keys.single { it.contains("metadata") }
        index.files[metadata] = "{ broken"

        val repo = repository(index)
        assertTrue(repo.load() != null && repo.restoredFromIndex)
        assertEquals(expected, repo.campaignLevels())
        assertEquals(reference.rivalLevels(), repo.rivalLevels())
        assertTrue(repository(index).campaignLevels().isNotEmpty())
    }
}
