package pt.tiago.neonline.core.solver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Status

/** A checked book hit, expressed in the caller's (possibly mirrored) board orientation. */
class PerfectOpeningBook private constructor(
    val maxPly: Int,
    private val entries: List<Record>,
) {
    private data class Key(val mine: Long, val occupied: Long)
    private data class Record(val key: Key, val bestColumn: Int, val score: Int)

    data class Seed(val state: GameState, val bestColumn: Int, val score: Int)
    data class BookMove(val move: Move.Drop, val score: Int)

    val size: Int get() = entries.size
    private val byKey = entries.associateBy { it.key }

    /** Returns a stored exact answer, or null when the position is outside this book. */
    fun lookup(state: GameState): BookMove? {
        val canonical = keyOf(state) ?: return null
        if (canonical.ply > maxPly) return null
        val record = byKey[canonical.key] ?: return null
        val column = if (canonical.mirrored) WIDTH - 1 - record.bestColumn else record.bestColumn
        if (column !in 0 until WIDTH || !state.canDropIn(column)) return null
        return BookMove(Move.Drop(column), record.score)
    }

    /** Writes stable big-endian bytes without closing [output]. */
    fun writeTo(output: OutputStream) {
        val payload = ByteArrayOutputStream(HEADER_BYTES + entries.size * RECORD_BYTES)
        DataOutputStream(payload).use { data ->
            data.write(MAGIC)
            data.writeByte(FORMAT_VERSION)
            data.writeByte(WIDTH)
            data.writeByte(HEIGHT)
            data.writeByte(CONNECT)
            data.writeByte(maxPly)
            data.writeInt(entries.size)
            for (record in entries) {
                data.writeLong(record.key.mine)
                data.writeLong(record.key.occupied)
                data.writeByte(record.bestColumn)
                data.writeByte(record.score)
            }
        }
        val bytes = payload.toByteArray()
        require(bytes.size + CHECKSUM_BYTES <= MAX_FILE_BYTES) { "opening book exceeds 6 MiB" }
        val checksum = CRC32().apply { update(bytes) }.value
        val data = DataOutputStream(output)
        data.write(bytes)
        data.writeInt(checksum.toInt())
        data.flush()
    }

    companion object {
        private const val WIDTH = 7
        private const val HEIGHT = 6
        private const val CONNECT = 4
        private const val FORMAT_VERSION = 1
        private const val HEADER_BYTES = 13
        private const val RECORD_BYTES = 18
        private const val CHECKSUM_BYTES = 4
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        const val MAX_FILE_BYTES: Int = 6 * 1024 * 1024
        private const val MAX_ENTRIES = (MAX_FILE_BYTES - HEADER_BYTES - CHECKSUM_BYTES) / RECORD_BYTES

        /** Builds a deterministic book and folds every position together with its horizontal mirror. */
        fun create(maxPly: Int, entries: List<Seed>): PerfectOpeningBook {
            require(maxPly in 0..(WIDTH * HEIGHT)) { "maxPly must be in 0..42" }
            require(entries.size <= MAX_ENTRIES) { "too many opening book entries" }
            val records = entries.map { seed ->
                require(seed.bestColumn in 0 until WIDTH) { "bestColumn must be in 0..6" }
                require(seed.score in -42..42) { "score must be in -42..42" }
                val canonical = keyOf(seed.state)
                    ?: throw IllegalArgumentException("opening book seeds must be nonterminal classic 7x6 positions")
                require(canonical.ply <= maxPly) { "seed position is deeper than maxPly" }
                require(seed.state.canDropIn(seed.bestColumn)) { "bestColumn is full" }
                val column = if (canonical.mirrored) WIDTH - 1 - seed.bestColumn else seed.bestColumn
                Record(canonical.key, column, seed.score)
            }.sortedWith(RECORD_ORDER)
            require(records.zipWithNext().none { (a, b) -> a.key == b.key }) {
                "duplicate opening book position (including its mirror)"
            }
            return PerfectOpeningBook(maxPly, records)
        }

        /** Reads a bounded, checksummed version-1 book without closing [input]. */
        fun read(input: InputStream): PerfectOpeningBook {
            val bytes = readBounded(input)
            require(bytes.size >= HEADER_BYTES + CHECKSUM_BYTES) { "truncated opening book" }
            val payloadSize = bytes.size - CHECKSUM_BYTES
            val expectedChecksum = DataInputStream(ByteArrayInputStream(bytes, payloadSize, CHECKSUM_BYTES)).readInt().toLong() and 0xffffffffL
            val actualChecksum = CRC32().apply { update(bytes, 0, payloadSize) }.value
            require(actualChecksum == expectedChecksum) { "opening book checksum mismatch" }

            val data = DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize))
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            require(magic.contentEquals(MAGIC)) { "invalid opening book magic" }
            require(data.readUnsignedByte() == FORMAT_VERSION) { "unsupported opening book format" }
            require(data.readUnsignedByte() == WIDTH && data.readUnsignedByte() == HEIGHT && data.readUnsignedByte() == CONNECT) {
                "opening book must target classic 7x6 connect-4"
            }
            val maxPly = data.readUnsignedByte()
            require(maxPly <= WIDTH * HEIGHT) { "invalid opening book maxPly" }
            val count = data.readInt()
            require(count in 0..MAX_ENTRIES) { "invalid opening book entry count" }
            require(payloadSize == HEADER_BYTES + count * RECORD_BYTES) { "opening book length does not match its entry count" }

            val records = ArrayList<Record>(count)
            repeat(count) {
                val key = Key(data.readLong(), data.readLong())
                val column = data.readUnsignedByte()
                val score = data.readByte().toInt()
                require(column in 0 until WIDTH) { "invalid opening book column" }
                require(score in -42..42) { "invalid opening book score" }
                validateKey(key, maxPly)
                require(columnHeight(key.occupied, column) < HEIGHT) { "opening book move points to a full column" }
                records += Record(key, column, score)
            }
            require(records.zipWithNext().all { (a, b) -> RECORD_ORDER.compare(a, b) < 0 }) {
                "opening book records must be sorted and unique"
            }
            return PerfectOpeningBook(maxPly, records)
        }

        private data class Canonical(val key: Key, val mirrored: Boolean, val ply: Int)

        private fun keyOf(state: GameState): Canonical? {
            if (state.width != WIDTH || state.height != HEIGHT || state.connect != CONNECT ||
                state.status != Status.InProgress || state.popOut || state.rotateEvery != null ||
                state.playable.any { !it } || state.shapeRule != null || state.targets.isNotEmpty() ||
                state.fadeAfter != null || state.moveClockSec != null || state.lockedCol != null || state.lockedFor != null ||
                state.pieces.any { it != null && it.kind != PieceKind.NORMAL } ||
                state.inventory.values.any { kinds -> kinds.any { (kind, count) -> kind != PieceKind.NORMAL && count != 0 } }
            ) return null

            var mine = 0L
            var occupied = 0L
            var ply = 0
            for (column in 0 until WIDTH) {
                var reachedGap = false
                var local = 0
                for (row in HEIGHT - 1 downTo 0) {
                    val piece = state.pieceAt(row, column)
                    if (piece == null) {
                        reachedGap = true
                    } else {
                        if (reachedGap) return null
                        val bit = 1L shl (column * (HEIGHT + 1) + local)
                        occupied = occupied or bit
                        if (piece.owner == state.toMove) mine = mine or bit
                        local++
                        ply++
                    }
                }
            }
            if (state.ply != ply) return null
            if (mine.countOneBits() != ply / 2) return null
            if (hasLine(mine) || hasLine(occupied xor mine)) return null
            val direct = Key(mine, occupied)
            val reflected = Key(mirror(mine), mirror(occupied))
            val useMirror = compareKeys(reflected, direct) < 0
            return Canonical(if (useMirror) reflected else direct, useMirror, ply)
        }

        private fun validateKey(key: Key, maxPly: Int) {
            val validBits = (1L shl (WIDTH * (HEIGHT + 1))) - 1L
            require(key.occupied and validBits.inv() == 0L) { "opening book occupancy has bits outside the board" }
            require(key.mine and key.occupied == key.mine) { "current-player bits are outside occupancy" }
            val ply = key.occupied.countOneBits()
            require(ply in 0 until WIDTH * HEIGHT && ply <= maxPly) { "invalid opening book position depth" }
            for (column in 0 until WIDTH) {
                val columnBits = (key.occupied ushr (column * (HEIGHT + 1))) and 0x7f
                require(columnBits and (1L shl HEIGHT) == 0L) { "sentinel bit set in opening book position" }
                val height = columnBits.countOneBits()
                require(columnBits == ((1L shl height) - 1L)) { "opening book column has a gravity gap" }
            }
            require(key.mine.countOneBits() == ply / 2) { "opening book side-to-move parity is invalid" }
            require(!hasLine(key.mine) && !hasLine(key.occupied xor key.mine)) { "terminal position in opening book" }
            val reflected = Key(mirror(key.mine), mirror(key.occupied))
            require(compareKeys(key, reflected) <= 0) { "opening book position is not mirror-canonical" }
        }

        private fun columnHeight(occupied: Long, column: Int): Int =
            ((occupied ushr (column * (HEIGHT + 1))) and 0x3f).countOneBits()

        private fun mirror(bits: Long): Long {
            var reflected = 0L
            for (column in 0 until WIDTH) {
                val source = (bits ushr (column * (HEIGHT + 1))) and 0x7f
                reflected = reflected or (source shl ((WIDTH - 1 - column) * (HEIGHT + 1)))
            }
            return reflected
        }

        private fun hasLine(bits: Long): Boolean {
            val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
            for (row in 0 until HEIGHT) for (column in 0 until WIDTH) for ((dr, dc) in directions) {
                var line = true
                for (step in 0 until CONNECT) {
                    val r = row + dr * step
                    val c = column + dc * step
                    if (r !in 0 until HEIGHT || c !in 0 until WIDTH ||
                        bits and (1L shl (c * (HEIGHT + 1) + r)) == 0L
                    ) {
                        line = false
                        break
                    }
                }
                if (line) return true
            }
            return false
        }

        private fun compareKeys(a: Key, b: Key): Int {
            val mine = java.lang.Long.compareUnsigned(a.mine, b.mine)
            return if (mine != 0) mine else java.lang.Long.compareUnsigned(a.occupied, b.occupied)
        }

        private val RECORD_ORDER = Comparator<Record> { a, b -> compareKeys(a.key, b.key) }

        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    require(output.size() < MAX_FILE_BYTES) { "opening book exceeds 6 MiB" }
                    output.write(next)
                    continue
                }
                require(output.size() + count <= MAX_FILE_BYTES) { "opening book exceeds 6 MiB" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

private fun GameState.canDropIn(column: Int): Boolean =
    column in 0 until width && (0 until height).any { row -> pieceAt(row, column) == null }
