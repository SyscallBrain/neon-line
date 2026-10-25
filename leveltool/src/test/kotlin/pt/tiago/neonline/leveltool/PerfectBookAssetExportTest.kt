package pt.tiago.neonline.leveltool

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectOpeningBook

class PerfectBookAssetExportTest {
    @Test
    fun `missing opening book remains an optional asset`() {
        val root = Files.createTempDirectory("book-export-root").toFile()
        val output = Files.createTempDirectory("book-export-output").toFile()
        val stale = java.io.File(output, "book/book-7x6.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

        assertNull(copyPerfectBookIfPresent(root, output))
        assertFalse(stale.exists())
    }

    @Test
    fun `validated opening book is copied byte-for-byte into packaged assets`() {
        val root = Files.createTempDirectory("book-export-root").toFile()
        val output = Files.createTempDirectory("book-export-output").toFile()
        val state = initial().let { GameEngine.apply(GameEngine.apply(it, Move.Drop(0, PieceKind.NORMAL)).state,
            Move.Drop(1, PieceKind.NORMAL)).state }
        val source = java.io.File(root, "book/book-7x6.bin").apply {
            parentFile.mkdirs()
            outputStream().use { PerfectOpeningBook.create(2, listOf(PerfectOpeningBook.Seed(state, 2, 1))).writeTo(it) }
        }
        val expected = source.readBytes()

        val copied = copyPerfectBookIfPresent(root, output)

        assertNotNull(copied)
        assertEquals("book/book-7x6.bin", copied!!.relativeTo(output).invariantSeparatorsPath)
        assertEquals(expected.toList(), copied.readBytes().toList())
        assertEquals(Move.Drop(2), copied.inputStream().use(PerfectOpeningBook::read).lookup(state)?.move)
    }

    private fun initial() = GameState.initial(
        levelId = "book-export-test", difficulty = Difficulty.HARD,
        engineVersion = GameEngine.ENGINE_VERSION, seedHex = "0000000000000001",
        width = 7, height = 6, connect = 4, playable = List(42) { true }, pieces = List(42) { null },
    )
}
