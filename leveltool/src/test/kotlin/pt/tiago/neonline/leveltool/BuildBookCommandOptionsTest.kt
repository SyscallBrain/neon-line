package pt.tiago.neonline.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BuildBookCommandOptionsTest {
    @Test
    fun `rejects solver budget as a command-line option`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseBuildBookCommandOptions(arrayOf("build-book", "--depth", "0", "--node-limit", "10"))
        }

        assertEquals("unsupported build-book option: --node-limit", error.message)
    }

    @Test
    fun `parses the supported book and solver checkpoint options`() {
        assertEquals(
            BuildBookCommandOptions(depth = 7, output = "custom/book.bin", checkpoint = "build/book.partial.bin",
                solverCheckpoint = "build/search.snapshot"),
            parseBuildBookCommandOptions(arrayOf(
                "build-book", "--depth", "7", "--output", "custom/book.bin",
                "--checkpoint", "build/book.partial.bin", "--solver-checkpoint", "build/search.snapshot",
            )),
        )
    }

    @Test
    fun `rejects a checkpoint path that matches the final output path`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBuildBookCommandOptions(arrayOf(
                "build-book", "--depth", "1", "--output", "book.bin", "--checkpoint", "book.bin",
            ))
        }
    }

    @Test
    fun `rejects solver checkpoint paths that collide with book files`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBuildBookCommandOptions(arrayOf(
                "build-book", "--depth", "1", "--output", "book.bin", "--solver-checkpoint", "book.bin",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseBuildBookCommandOptions(arrayOf(
                "build-book", "--depth", "1", "--checkpoint", "partial.bin",
                "--solver-checkpoint", "partial.bin",
            ))
        }
    }

    @Test
    fun `rejects a depth outside the book format`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBuildBookCommandOptions(arrayOf("build-book", "--depth", "43"))
        }
    }
}
