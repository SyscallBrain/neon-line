package pt.tiago.neonline.core.solver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.rules.Boards

class PerfectOpeningBookTest {
    private fun state(left: Boolean = true) = Boards.state(
        ".......",
        ".......",
        ".......",
        ".......",
        ".......",
        if (left) "..12..." else "...21..",
    ).copy(ply = 2)

    private fun oddState() = Boards.state(
        ".......",
        ".......",
        ".......",
        ".......",
        ".......",
        "...1...",
    ).copy(ply = 1, toMove = Player.P2)

    @Test
    fun `binary round trip preserves canonical entries deterministically`() {
        val book = PerfectOpeningBook.create(
            maxPly = 4,
            entries = listOf(PerfectOpeningBook.Seed(state(), bestColumn = 2, score = 7)),
        )
        val first = ByteArrayOutputStream().also(book::writeTo).toByteArray()
        val loaded = PerfectOpeningBook.read(ByteArrayInputStream(first))
        val second = ByteArrayOutputStream().also(loaded::writeTo).toByteArray()

        assertArrayEquals(first, second)
        assertEquals(4, loaded.maxPly)
        assertEquals(1, loaded.size)
        assertEquals(PerfectOpeningBook.BookMove(Move.Drop(2), 7), loaded.lookup(state()))
    }

    @Test
    fun `mirror positions share one entry and map the move back`() {
        val book = PerfectOpeningBook.create(
            maxPly = 4,
            entries = listOf(PerfectOpeningBook.Seed(state(), bestColumn = 2, score = 7)),
        )

        assertEquals(1, book.size)
        assertEquals(PerfectOpeningBook.BookMove(Move.Drop(4), 7), book.lookup(state(left = false)))
    }

    @Test
    fun `rejects duplicate mirror entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.create(
                maxPly = 4,
                entries = listOf(
                    PerfectOpeningBook.Seed(state(), bestColumn = 2, score = 7),
                    PerfectOpeningBook.Seed(state(left = false), bestColumn = 4, score = 7),
                ),
            )
        }
    }

    @Test
    fun `rejects corrupt truncated and oversized files`() {
        val bytes = ByteArrayOutputStream().also {
            PerfectOpeningBook.create(4, listOf(PerfectOpeningBook.Seed(state(), 2, 7))).writeTo(it)
        }.toByteArray()
        val corrupted = bytes.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.read(ByteArrayInputStream(corrupted))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.read(ByteArrayInputStream(ByteArray(PerfectOpeningBook.MAX_FILE_BYTES + 1)))
        }
    }

    @Test
    fun `reader makes progress when a bulk input read returns zero`() {
        val bytes = ByteArrayOutputStream().also {
            PerfectOpeningBook.create(4, listOf(PerfectOpeningBook.Seed(state(), 2, 7))).writeTo(it)
        }.toByteArray()
        var offset = 0
        var returnedZero = false
        var madeProgress = false
        val input = object : InputStream() {
            override fun read(): Int {
                madeProgress = true
                return if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff
            }

            override fun read(target: ByteArray, start: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                if (!madeProgress) throw IOException("reader retried a stalled bulk read")
                if (offset == bytes.size) return -1
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(target, start, offset, offset + count)
                offset += count
                return count
            }
        }

        val loaded = PerfectOpeningBook.read(input)

        assertEquals(PerfectOpeningBook.BookMove(Move.Drop(2), 7), loaded.lookup(state()))
    }

    @Test
    fun `unsupported and terminal positions never hit the book`() {
        val book = PerfectOpeningBook.create(
            maxPly = 4,
            entries = listOf(PerfectOpeningBook.Seed(state(), bestColumn = 2, score = 7)),
        )
        assertNull(book.lookup(Boards.blank(popOut = true)))
        val terminal = Boards.state(
            ".......", ".......", ".......", ".......", ".......", "111....",
        )
        assertNull(book.lookup(terminal))
        assertTrue(book.lookup(state(left = false)) != null)
    }

    @Test
    fun `rejects states whose ply or side to move disagrees with the board`() {
        val valid = state()
        val book = PerfectOpeningBook.create(
            maxPly = 4,
            entries = listOf(PerfectOpeningBook.Seed(valid, bestColumn = 2, score = 7)),
        )
        val wrongPly = valid.copy(ply = valid.ply + 1)
        val validOdd = oddState()
        val oddBook = PerfectOpeningBook.create(4, listOf(PerfectOpeningBook.Seed(validOdd, 3, 7)))
        val wrongTurn = validOdd.copy(toMove = Player.P1)

        assertNull(book.lookup(wrongPly))
        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.create(4, listOf(PerfectOpeningBook.Seed(wrongPly, 2, 7)))
        }
        assertEquals(PerfectOpeningBook.BookMove(Move.Drop(3), 7), oddBook.lookup(validOdd))
        assertNull(oddBook.lookup(wrongTurn))
        assertThrows(IllegalArgumentException::class.java) {
            PerfectOpeningBook.create(4, listOf(PerfectOpeningBook.Seed(wrongTurn, 2, 7)))
        }
    }
}
