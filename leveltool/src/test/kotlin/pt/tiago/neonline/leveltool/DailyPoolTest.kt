package pt.tiago.neonline.leveltool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*

class DailyPoolTest {
    private val root = File("content")
    private fun pool() = Json.decodeFromString(DailyPoolDto.serializer(), File(root, "daily/pool.json").readText())
    @Test fun `frozen pool contains certified nontrivial puzzles for every difficulty`() {
        val pool = pool()
        DailyPool.verify(root, pool)
        assertEquals(39, pool.entries.size)
        for (key in listOf("easy", "medium", "hard")) assertEquals(13, pool.entries.count { it.difficulty == key })
        assertTrue(pool.entries.none { it.ordinal == 1 })
    }
    @Test fun `generation is reproducible and changing a published pool requires a version bump`() {
        val dir = Files.createTempDirectory("daily-pool").toFile()
        try {
            val output = File(dir, "pool.json")
            DailyPool.generate(root, output)
            assertEquals(File(root, "daily/pool.json").readText(), output.readText())
            val changed = pool().copy(entries = pool().entries.drop(1))
            output.writeText(Json.encodeToString(DailyPoolDto.serializer(), changed))
            assertThrows(IllegalArgumentException::class.java) { DailyPool.generate(root, output) }
            DailyPool.generate(root, output, 2)
            assertEquals(2, Json.decodeFromString(DailyPoolDto.serializer(), output.readText()).version)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `verification rejects corrupt references duplicate entries and incompatible engines`() {
        val pool = pool()
        for (bad in listOf(pool.copy(engineVersion = 999), pool.copy(version = 0),
            pool.copy(entries = pool.entries + pool.entries.first()),
            pool.copy(entries = pool.entries.map { it.copy(sha256 = "0".repeat(64)) }),
            pool.copy(entries = pool.entries.filter { it.difficulty != "hard" })))
            assertThrows(IllegalArgumentException::class.java) { DailyPool.verify(root, bad) }
    }
}
