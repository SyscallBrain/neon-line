package pt.tiago.neonline.core.solver

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.CRC32
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.rules.BitBoard

/** Theoretical value of a position with best play on both sides. */
enum class SolverValue { WIN, DRAW, LOSS }

/** Exact value and optimal moves; an exhausted search throws. */
data class SolverResult(
    val value: SolverValue,
    val bestMoves: List<Move>,
    /** Positive for a win, negative for a loss; magnitude ranks faster wins/slower losses. 0 is a draw. */
    val score: Int,
    val nodesExplored: Long,
    /** Matching transposition entries used (exact values or alpha-beta bounds). */
    val transpositionHits: Long = 0L,
    /** Integer null-window searches used to determine the exact root score. */
    val rootScoreProbes: Int = 0,
    /** Matching cached moves promoted to the front of their node's move order. */
    val transpositionMoveOrderingHits: Long = 0L,
)

/** Thrown when the search outgrows [PerfectSolver.nodeLimit]. */
class SolverBudgetExceeded(val nodesExplored: Long) : Exception() {
    constructor() : this(0L)
}

/**
 * Perfect solver for the classic rules (connect 4 or 5 on a rectangular board). Fail-soft
 * alpha-beta negamax over the BitBoard with the two prunings that matter for four in a line:
 * moves that would hand the opponent an immediate win are skipped, and a win that is available at
 * once ends the search.
 *
 * Design note: the root value is narrowed with integer null-window probes in the
 * same style as the Pascal Pons solver. The bounded transposition table stores collision-checked
 * exact values and alpha-beta bounds; the opening book remains a separate follow-up.
 *
 * It only accepts positions [BitBoard.supports] knows: no Pop Out, no rotating gravity, no blocked
 * cells and no specials. Those are the leveltool solver's job.
 */
class PerfectSolver(
    private val nodeLimit: Long = 2_000_000L,
    private val transpositionCapacity: Int = 1 shl 16,
    private val nodeProgressInterval: Long = 1L shl 20,
    private val reuseTranspositionTable: Boolean = false,
) {
    init {
        require(nodeProgressInterval > 0) { "nodeProgressInterval must be positive" }
        require(transpositionCapacity == 0 || (transpositionCapacity > 0 && transpositionCapacity.countOneBits() == 1)) {
            "transpositionCapacity must be zero or a power of two"
        }
    }

    /** Position: both players' bitboards, whose turn it is and how many plies were played. */
    private class Position(
        val width: Int,
        val height: Int,
        var mine: BitBoard,
        var theirs: BitBoard,
        var ply: Int,
        val searchDepth: Int,
    ) {
        val cells = width * height

        fun canPlay(col: Int): Boolean = mine.canPlay(col)

        /** Mutates this frame's boards and initializes the already allocated child frame. */
        fun pushTo(child: Position, col: Int) {
            mine.playSearchDrop(col, theirs)
            child.mine = theirs
            child.theirs = mine
            child.ply = ply + 1
        }

        /** Restores both bitboards after the child search returns. */
        fun pop(col: Int) {
            mine.undoSearchDrop(col, theirs)
        }

        /** The player to move wins at once by playing in [col]. */
        fun winsNow(col: Int): Boolean = mine.wouldWin(col)

        /** Any column where the player to move wins at once. */
        fun winsInAnyColumn(): Boolean = mine.winningMoveMask() != 0L

        /** Fills per-column threat scores using reusable storage for this search ply. */
        fun fillThreatScores(scores: IntArray, added0: LongArray, added1: LongArray) =
            mine.fillPotentialWinningSpotCountsAfterDrops(theirs, scores, added0, added1)

        /** The player who just moved already has a line: the game is over. */
        fun previousMoverWon(): Boolean = theirs.hasLine()

        /** Playable columns that do not hand the opponent an immediate win, centre first. */
        fun nonLosingColumns(): Long = mine.nonLosingColumnsAgainst(theirs)
    }

    /** Solves [state]; returns null when the position is not a classic board or is already over. */
    @JvmOverloads
    fun solve(state: GameState, checkCancelled: () -> Unit = {}): SolverResult? =
        solveInternal(state, checkCancelled, null, allBestMoves = true)

    /** Solves [state] and returns one exact optimal move, avoiding enumeration of other ties. */
    @JvmOverloads
    fun solveOneBestMove(state: GameState, checkCancelled: () -> Unit = {}): SolverResult? =
        solveInternal(state, checkCancelled, null, allBestMoves = false)

    /** Solves [state] and reports the number of visited nodes at [nodeProgressInterval] steps. */
    fun solveWithProgress(
        state: GameState,
        checkCancelled: () -> Unit = {},
        onProgress: (Long) -> Unit,
    ): SolverResult? = solveInternal(state, checkCancelled, onProgress, allBestMoves = true)

    /** Progress-reporting exact solve that returns one optimal move instead of all tied moves. */
    fun solveOneBestMoveWithProgress(
        state: GameState,
        checkCancelled: () -> Unit = {},
        onProgress: (Long) -> Unit,
    ): SolverResult? = solveInternal(state, checkCancelled, onProgress, allBestMoves = false)

    private fun solveInternal(
        state: GameState,
        checkCancelled: () -> Unit,
        onProgress: ((Long) -> Unit)?,
        allBestMoves: Boolean,
    ): SolverResult? {
        checkCancelled()
        if (!BitBoard.supports(state) || state.shapeRule != null || state.targets.isNotEmpty() ||
            state.fadeAfter != null || state.moveClockSec != null || state.lockedCol != null || state.lockedFor != null ||
            state.inventory.values.any { kinds -> kinds.any { (kind, count) -> kind != PieceKind.NORMAL && count != 0 } }) return null
        for (col in 0 until state.width) {
            var emptyBelow = false
            for (row in state.height - 1 downTo 0) {
                if (state.pieceAt(row, col) == null) emptyBelow = true
                else if (emptyBelow) return null
            }
        }
        if (state.status != Status.InProgress) return null
        val rootMine = BitBoard.of(state, state.toMove)
        val rootTheirs = BitBoard.of(state, state.toMove.opponent())
        if (rootMine.hasLine() || rootTheirs.hasLine()) return null
        if ((0 until state.width).none(rootMine::canPlay)) return null
        val rootPly = state.pieces.count { it != null }
        positionFrames = Array(state.width * state.height - rootPly + 1) { depth ->
            Position(state.width, state.height, rootMine, rootTheirs, rootPly, depth)
        }
        val root = positionFrames[0]
        nodes = 0L
        this.onProgress = onProgress
        nextProgressNode = nodeProgressInterval
        transpositionHits = 0L
        transpositionMoveOrderingHits = 0L
        rootScoreProbes = 0
        forcedMovesThatSkippedThreatScoring = 0L
        if (!reuseTranspositionTable ||
            tableWidth != root.width || tableHeight != root.height || tableConnect != state.connect
        ) {
            table = null
            tableWidth = root.width
            tableHeight = root.height
            tableConnect = state.connect
        }
        moveOrderColumns = Array(root.cells + 1) { IntArray(root.width) }
        moveOrderScores = Array(root.cells + 1) { IntArray(root.width) }
        moveThreatScores = Array(root.cells + 1) { IntArray(root.width) }
        moveThreatScratch0 = Array(root.cells + 1) { LongArray(root.width) }
        moveThreatScratch1 = Array(root.cells + 1) { LongArray(root.width) }
        // Exact short proofs avoid spending the move-enumeration budget on irrelevant long branches.
        visit(checkCancelled)
        val legal = (0 until root.width).filter { root.canPlay(it) }
        val immediate = legal.filter { root.winsNow(it) }
        if (immediate.isNotEmpty()) return SolverResult(SolverValue.WIN,
            (if (allBestMoves) immediate else immediate.take(1)).map { Move.Drop(it) },
            (root.cells + 1 - root.ply) / 2, nodes, transpositionHits)
        val child = positionFrames.getOrNull(1)
        val nextTurnWins = legal.filter { col ->
            visit(checkCancelled)
            val childPosition = checkNotNull(child)
            root.pushTo(childPosition, col)
            try {
                val replies = (0 until childPosition.width).filter { childPosition.canPlay(it) }
                !childPosition.winsInAnyColumn() && replies.isNotEmpty() && replies.all { reply ->
                    visit(checkCancelled)
                    val grandchild = positionFrames[childPosition.searchDepth + 1]
                    childPosition.pushTo(grandchild, reply)
                    try {
                        grandchild.winsInAnyColumn()
                    } finally {
                        childPosition.pop(reply)
                    }
                }
            } finally {
                root.pop(col)
            }
        }
        if (nextTurnWins.isNotEmpty()) return SolverResult(SolverValue.WIN,
            (if (allBestMoves) nextTurnWins else nextTurnWins.take(1)).map { Move.Drop(it) },
            (root.cells - root.ply - 1) / 2, nodes, transpositionHits)
        if (transpositionCapacity == 0) {
            table = null
        } else if (table == null) {
            table = TranspositionTable(
                transpositionCapacity,
                wordCount = if (root.width * (root.height + 1) <= 64) 1 else 2,
            )
        }
        val score = exactRootScore(root, checkCancelled)
        val value = when {
            score > 0 -> SolverValue.WIN
            score < 0 -> SolverValue.LOSS
            else -> SolverValue.DRAW
        }
        // The exact root score s guarantees every child's score is >= -s. A null-window
        // fail-low at -s therefore proves equality (including distance-optimal ties);
        // a fail-high proves the move is worse without finding its exact child score.
        val moves = mutableListOf<Move>()
        val rootMoves = (0 until root.width).filter { root.canPlay(it) }.toMutableList()
        if (!allBestMoves) {
            val cache = table
            if (cache != null) {
                val rootKey = cache.keyFor(root.mine, root.theirs, root.ply)
                if (cache.readAtKey(rootKey, root.mine, root.theirs, root.ply, transpositionProbe)) {
                    transpositionHits++
                    val preferredMove = transpositionProbe.bestMove
                    if (preferredMove in rootMoves) {
                        rootMoves.remove(preferredMove)
                        rootMoves.add(0, preferredMove)
                        transpositionMoveOrderingHits++
                    }
                }
            }
        }
        for (col in rootMoves) {
            val childPosition = checkNotNull(child)
            root.pushTo(childPosition, col)
            val childScore = try {
                negamax(childPosition, -score, -score + 1, checkCancelled)
            } finally {
                root.pop(col)
            }
            if (childScore <= -score) {
                moves += Move.Drop(col)
                if (!allBestMoves) break
            }
        }
        return SolverResult(
            value, moves, score, nodes, transpositionHits, rootScoreProbes,
            transpositionMoveOrderingHits,
        )
    }

    /** The move to play in Perfect mode, or null when the position is not solvable here. */
    @JvmOverloads
    fun bestMove(state: GameState, checkCancelled: () -> Unit = {}): Move? =
        solve(state, checkCancelled)?.bestMoves?.firstOrNull()

    /** Restores a completed-entry cache before a solve; only enabled for reusable tables. */
    fun restoreTranspositionTableSnapshot(input: InputStream, width: Int, height: Int, connect: Int) {
        require(reuseTranspositionTable) { "transposition snapshot restore requires table reuse" }
        require(table == null) { "transposition table has already been initialized" }
        val restored = TranspositionTable.readSnapshot(
            input,
            width,
            height,
            connect,
            targetCapacity = transpositionCapacity,
        )
        table = restored
        tableWidth = width
        tableHeight = height
        tableConnect = connect
    }

    /** Writes completed entries for the matching board; returns false before table allocation. */
    fun writeTranspositionTableSnapshot(output: OutputStream, width: Int, height: Int, connect: Int): Boolean {
        val current = table ?: return false
        require(tableWidth == width && tableHeight == height && tableConnect == connect) {
            "transposition table board metadata does not match"
        }
        current.writeSnapshot(output, width, height, connect)
        return true
    }

    private var nodes = 0L
    private var onProgress: ((Long) -> Unit)? = null
    private var nextProgressNode = nodeProgressInterval
    private var tableWidth = -1
    private var tableHeight = -1
    private var tableConnect = -1
    private var transpositionHits = 0L
    private var transpositionMoveOrderingHits = 0L
    internal var forcedMovesThatSkippedThreatScoring = 0L
        private set
    private var rootScoreProbes = 0
    private var table: TranspositionTable? = null
    private val transpositionProbe = TranspositionTable.Probe()
    private var positionFrames: Array<Position> = emptyArray()
    private var moveOrderColumns: Array<IntArray> = emptyArray()
    private var moveOrderScores: Array<IntArray> = emptyArray()
    private var moveThreatScores: Array<IntArray> = emptyArray()
    private var moveThreatScratch0: Array<LongArray> = emptyArray()
    private var moveThreatScratch1: Array<LongArray> = emptyArray()

    /** Finds an exact integer score by repeatedly asking whether the root value exceeds a midpoint. */
    private fun exactRootScore(root: Position, checkCancelled: () -> Unit): Int {
        var lower = -((root.cells + 2 - root.ply) / 2)
        var upper = (root.cells + 1 - root.ply) / 2
        while (lower < upper) {
            val midpoint = lower + (upper - lower) / 2
            rootScoreProbes++
            val probe = negamax(root, midpoint, midpoint + 1, checkCancelled)
            if (probe > midpoint) lower = midpoint + 1 else upper = midpoint
        }
        return lower
    }

    /**
     * Fixed-size direct-mapped cache; all stored key words are checked after indexing to reject
     * collisions. Use one word only for board shapes whose playable cells fit in the low word.
     */
    internal class TranspositionTable(internal val capacity: Int, internal val wordCount: Int = 2) {
        init {
            require(capacity > 0 && capacity.countOneBits() == 1)
            require(wordCount in 1..2)
        }

        internal val mirrorFlag = 1L shl 62
        private val mask = capacity - 1
        private val slotBits = Integer.numberOfTrailingZeros(capacity)
        private val slotMask = mask.toLong()
        private val signatureMask = 0xffff_ffffL
        private val mine0 = LongArray(capacity)
        private val mine1 = if (wordCount == 2) LongArray(capacity) else LongArray(0)
        private val theirs0 = if (wordCount == 2) LongArray(capacity) else LongArray(0)
        private val theirs1 = if (wordCount == 2) LongArray(capacity) else LongArray(0)
        private val signatures = IntArray(capacity)
        private val plies = IntArray(capacity)
        private val scores = IntArray(capacity)
        private val bestMoves = IntArray(capacity) { -1 }
        // 0 = upper bound, 1 = exact value, 2 = lower bound.
        private val bounds = IntArray(capacity)
        private val occupied = BooleanArray(capacity)

        /** Writes only completed entries; callers should atomically replace the destination file. */
        internal fun writeSnapshot(output: OutputStream, width: Int, height: Int, connect: Int) {
            validateSnapshotBoard(width, height, connect)
            val checksum = CRC32()
            val data = DataOutputStream(CheckedOutputStream(output, checksum))
            data.writeInt(SNAPSHOT_MAGIC)
            data.writeInt(SNAPSHOT_VERSION)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(connect)
            data.writeInt(capacity)
            data.writeInt(wordCount)
            val count = occupied.count { it }
            data.writeInt(count)
            for (slot in occupied.indices) if (occupied[slot]) {
                data.writeInt(slot)
                data.writeLong(mine0[slot])
                data.writeLong(if (wordCount == 2) mine1[slot] else 0L)
                data.writeLong(if (wordCount == 2) theirs0[slot] else 0L)
                data.writeLong(if (wordCount == 2) theirs1[slot] else 0L)
                data.writeInt(signatures[slot])
                data.writeInt(plies[slot])
                data.writeInt(scores[slot])
                data.writeInt(bestMoves[slot])
                data.writeInt(bounds[slot])
            }
            data.flush()
            DataOutputStream(output).also {
                it.writeInt(checksum.value.toInt())
                it.flush()
            }
        }

        fun get(mine: BitBoard, theirs: BitBoard, ply: Int): Entry? {
            return getAtKey(keyFor(mine, theirs, ply), mine, theirs, ply)
        }

        internal fun getAt(slot: Int, mine: BitBoard, theirs: BitBoard, ply: Int): Entry? {
            val key = replaceSlot(keyFor(mine, theirs, ply), slot)
            return getAtKey(key, mine, theirs, ply)
        }

        internal fun getAtKey(key: Long, mine: BitBoard, theirs: BitBoard, ply: Int): Entry? {
            val probe = Probe()
            return if (readAtKey(key, mine, theirs, ply, probe)) {
                Entry(probe.score, probe.bound, probe.bestMove)
            } else {
                null
            }
        }

        internal fun readAtKey(
            key: Long,
            mine: BitBoard,
            theirs: BitBoard,
            ply: Int,
            result: Probe,
        ): Boolean {
            val slot = slotFromKey(key)
            val mirrored = key and mirrorFlag != 0L
            if (!occupied[slot] || plies[slot] != ply) return false
            if (signatures[slot] != signatureFromKey(key)) return false
            if (wordCount == 1) {
                if (mine0[slot] != compactPositionKey(mine, theirs, mirrored)) return false
            } else {
                val mineWord0 = if (mirrored) mine.mirroredKeyWord(0) else mine.keyWord(0)
                val theirsWord0 = if (mirrored) theirs.mirroredKeyWord(0) else theirs.keyWord(0)
                if (mine0[slot] != mineWord0 || theirs0[slot] != theirsWord0) return false
                val mineWord1 = if (mirrored) mine.mirroredKeyWord(1) else mine.keyWord(1)
                val theirsWord1 = if (mirrored) theirs.mirroredKeyWord(1) else theirs.keyWord(1)
                if (mine1[slot] != mineWord1 || theirs1[slot] != theirsWord1) return false
            }
            val bestMove = bestMoves[slot].takeIf { it >= 0 }?.let {
                if (mirrored) mine.width - 1 - it else it
            } ?: -1
            result.score = scores[slot]
            result.bound = bounds[slot]
            result.bestMove = bestMove
            return true
        }

        fun put(mine: BitBoard, theirs: BitBoard, ply: Int, score: Int, bound: Int, bestMove: Int) {
            putAtKey(keyFor(mine, theirs, ply), mine, theirs, ply, score, bound, bestMove)
        }

        internal fun putAt(
            slot: Int,
            mine: BitBoard,
            theirs: BitBoard,
            ply: Int,
            score: Int,
            bound: Int,
            bestMove: Int,
        ) {
            val key = replaceSlot(keyFor(mine, theirs, ply), slot)
            putAtKey(key, mine, theirs, ply, score, bound, bestMove)
        }

        internal fun putAtKey(
            key: Long,
            mine: BitBoard,
            theirs: BitBoard,
            ply: Int,
            score: Int,
            bound: Int,
            bestMove: Int,
        ) {
            val slot = slotFromKey(key)
            val mirrored = key and mirrorFlag != 0L
            // An alpha-beta bound from a colliding position cannot improve an exact value.
            if (occupied[slot] && bounds[slot] == 1 && bound != 1) return
            if (wordCount == 1) {
                mine0[slot] = compactPositionKey(mine, theirs, mirrored)
            } else {
                mine0[slot] = if (mirrored) mine.mirroredKeyWord(0) else mine.keyWord(0)
                theirs0[slot] = if (mirrored) theirs.mirroredKeyWord(0) else theirs.keyWord(0)
                mine1[slot] = if (mirrored) mine.mirroredKeyWord(1) else mine.keyWord(1)
                theirs1[slot] = if (mirrored) theirs.mirroredKeyWord(1) else theirs.keyWord(1)
            }
            signatures[slot] = signatureFromKey(key)
            plies[slot] = ply
            scores[slot] = score
            bestMoves[slot] = if (bestMove >= 0 && mirrored) mine.width - 1 - bestMove else bestMove
            bounds[slot] = bound
            occupied[slot] = true
        }

        data class Entry(val score: Int, val bound: Int, val bestMove: Int)

        internal class Probe {
            var score: Int = 0
            var bound: Int = 0
            var bestMove: Int = -1
        }

        companion object {
            private const val SNAPSHOT_MAGIC = 0x4e4c5454 // NLTT
            private const val SNAPSHOT_VERSION = 1
            private const val MAX_SNAPSHOT_CAPACITY = 1 shl 24

            /** Loads a snapshot only when its board rules match the requested classic solver. */
            internal fun readSnapshot(
                input: InputStream,
                expectedWidth: Int,
                expectedHeight: Int,
                expectedConnect: Int,
                targetCapacity: Int? = null,
            ): TranspositionTable {
                validateSnapshotBoard(expectedWidth, expectedHeight, expectedConnect)
                val checksum = CRC32()
                val data = DataInputStream(CheckedInputStream(input, checksum))
                require(data.readInt() == SNAPSHOT_MAGIC) { "invalid transposition snapshot magic" }
                require(data.readInt() == SNAPSHOT_VERSION) { "unsupported transposition snapshot version" }
                val width = data.readInt()
                val height = data.readInt()
                val connect = data.readInt()
                require(width == expectedWidth && height == expectedHeight && connect == expectedConnect) {
                    "transposition snapshot board metadata does not match"
                }
                val capacity = data.readInt()
                val wordCount = data.readInt()
                val count = data.readInt()
                require(capacity in 1..MAX_SNAPSHOT_CAPACITY && capacity.countOneBits() == 1) {
                    "invalid transposition snapshot capacity"
                }
                val restoredCapacity = targetCapacity ?: capacity
                require(
                    restoredCapacity in capacity..MAX_SNAPSHOT_CAPACITY &&
                        restoredCapacity.countOneBits() == 1,
                ) {
                    "target transposition snapshot capacity must be a power of two no smaller than the snapshot"
                }
                require(wordCount == if (width * (height + 1) <= 64) 1 else 2) {
                    "invalid transposition snapshot key width"
                }
                require(count in 0..capacity) { "invalid transposition snapshot entry count" }
                val table = TranspositionTable(restoredCapacity, wordCount)
                val seenSourceSlots = BooleanArray(capacity)
                repeat(count) {
                    val sourceSlot = data.readInt()
                    val mine0 = data.readLong()
                    val mine1 = data.readLong()
                    val theirs0 = data.readLong()
                    val theirs1 = data.readLong()
                    val signature = data.readInt()
                    val ply = data.readInt()
                    val score = data.readInt()
                    val bestMove = data.readInt()
                    val bound = data.readInt()
                    require(sourceSlot in 0 until capacity && !seenSourceSlots[sourceSlot]) {
                        "invalid or duplicate transposition snapshot slot"
                    }
                    seenSourceSlots[sourceSlot] = true
                    require(ply in 0..(width * height) && score in -(width * height)..(width * height)) {
                        "invalid transposition snapshot score or ply"
                    }
                    require(bestMove in -1 until width && bound in 0..2) {
                        "invalid transposition snapshot move or bound"
                    }
                    val hash = if (wordCount == 1) {
                        hashCompact(mine0, ply)
                    } else {
                        hash(mine0, mine1, theirs0, theirs1, ply)
                    }
                    require(
                        sourceSlot == (hash.toInt() and (capacity - 1)) &&
                            signature == (hash ushr 32).toInt(),
                    ) {
                        "invalid transposition snapshot key"
                    }
                    val slot = hash.toInt() and table.mask
                    require(!table.occupied[slot]) { "duplicate transposition snapshot position after resize" }
                    table.mine0[slot] = mine0
                    if (wordCount == 2) {
                        table.mine1[slot] = mine1
                        table.theirs0[slot] = theirs0
                        table.theirs1[slot] = theirs1
                    }
                    table.signatures[slot] = signature
                    table.plies[slot] = ply
                    table.scores[slot] = score
                    table.bestMoves[slot] = bestMove
                    table.bounds[slot] = bound
                    table.occupied[slot] = true
                }
                val expectedChecksum = DataInputStream(input).readInt()
                require(checksum.value.toInt() == expectedChecksum) { "transposition snapshot checksum mismatch" }
                require(input.read() == -1) { "trailing data in transposition snapshot" }
                return table
            }

            private fun validateSnapshotBoard(width: Int, height: Int, connect: Int) {
                require(width in 1..16 && height in 1..16 && connect in 3..maxOf(width, height)) {
                    "invalid transposition snapshot board metadata"
                }
            }

            private fun hash(mine0: Long, mine1: Long, theirs0: Long, theirs1: Long, ply: Int): Long {
                var hash = mine0 * -7046029254386353131L
                hash = (hash xor mine1) * -4658895280553007687L
                hash = (hash xor theirs0) * -7723592293110705685L
                hash = (hash xor theirs1) * -7046029254386353131L
                hash = (hash xor ply.toLong()) * -4658895280553007687L
                return hash xor (hash ushr 32)
            }

            private fun hashCompact(positionKey: Long, ply: Int): Long {
                var hash = positionKey * -7046029254386353131L
                hash = (hash xor ply.toLong()) * -4658895280553007687L
                return hash xor (hash ushr 32)
            }
        }

        internal fun slotFor(mine: BitBoard, theirs: BitBoard, ply: Int): Int {
            return slotFromKey(keyFor(mine, theirs, ply))
        }

        internal fun keyFor(mine: BitBoard, theirs: BitBoard, ply: Int): Long {
            val mirrored: Boolean
            val hash: Long
            if (wordCount == 1) {
                val forward = compactPositionKey(mine, theirs, mirrored = false)
                val reflected = compactPositionKey(mine, theirs, mirrored = true)
                mirrored = java.lang.Long.compareUnsigned(forward, reflected) > 0
                val canonical = if (mirrored) reflected else forward
                hash = hashCompact(canonical, ply)
            } else {
                mirrored = compareCanonicalWords(mine, theirs) > 0
                val mineWord0 = if (mirrored) mine.mirroredKeyWord(0) else mine.keyWord(0)
                val mineWord1 = if (mirrored) mine.mirroredKeyWord(1) else mine.keyWord(1)
                val theirsWord0 = if (mirrored) theirs.mirroredKeyWord(0) else theirs.keyWord(0)
                val theirsWord1 = if (mirrored) theirs.mirroredKeyWord(1) else theirs.keyWord(1)
                hash = hash(mineWord0, mineWord1, theirsWord0, theirsWord1, ply)
            }
            val slot = hash.toInt() and mask
            val signature = (hash ushr 32) and signatureMask
            return slot.toLong() or (signature shl slotBits) or if (mirrored) mirrorFlag else 0L
        }

        /** Compact exact board identity for one-word gravity boards and their reflections. */
        internal fun compactCanonicalKey(mine: BitBoard, theirs: BitBoard): Long {
            require(wordCount == 1) { "compact position keys require a one-word table" }
            val forward = compactPositionKey(mine, theirs, mirrored = false)
            val reflected = compactPositionKey(mine, theirs, mirrored = true)
            return if (java.lang.Long.compareUnsigned(forward, reflected) <= 0) forward else reflected
        }

        private fun compactPositionKey(mine: BitBoard, theirs: BitBoard, mirrored: Boolean): Long {
            val player = if (mirrored) mine.mirroredKeyWord(0) else mine.keyWord(0)
            val occupied = player or if (mirrored) theirs.mirroredKeyWord(0) else theirs.keyWord(0)
            return player + occupied
        }

        private fun slotFromKey(key: Long): Int = (key and slotMask).toInt()

        private fun signatureFromKey(key: Long): Int = ((key ushr slotBits) and signatureMask).toInt()

        private fun replaceSlot(key: Long, slot: Int): Long =
            (key and slotMask.inv()) or (slot.toLong() and slotMask)

        /** Hashes only the lexicographically canonical orientation of a position and its mirror. */
        internal fun canonicalHash(mine: BitBoard, theirs: BitBoard, ply: Int): Long {
            if (wordCount == 1) {
                return hashCompact(compactCanonicalKey(mine, theirs), ply)
            }
            val comparison = compareCanonicalWords(mine, theirs)
            return if (comparison <= 0) {
                hash(mine.keyWord(0), mine.keyWord(1), theirs.keyWord(0), theirs.keyWord(1), ply)
            } else {
                hash(
                    mine.mirroredKeyWord(0), mine.mirroredKeyWord(1),
                    theirs.mirroredKeyWord(0), theirs.mirroredKeyWord(1), ply,
                )
            }
        }

        private fun compareCanonicalWords(mine: BitBoard, theirs: BitBoard): Int {
            var comparison = java.lang.Long.compareUnsigned(mine.keyWord(0), mine.mirroredKeyWord(0))
            if (comparison != 0) return comparison
            comparison = java.lang.Long.compareUnsigned(mine.keyWord(1), mine.mirroredKeyWord(1))
            if (comparison != 0) return comparison
            comparison = java.lang.Long.compareUnsigned(theirs.keyWord(0), theirs.mirroredKeyWord(0))
            if (comparison != 0) return comparison
            return java.lang.Long.compareUnsigned(theirs.keyWord(1), theirs.mirroredKeyWord(1))
        }

    }

    private fun visit(checkCancelled: () -> Unit) {
        checkCancelled()
        nodes++
        val progress = onProgress
        if (progress != null && nodes >= nextProgressNode) {
            progress(nodes)
            nextProgressNode += nodeProgressInterval
        }
        if (nodes > nodeLimit) throw SolverBudgetExceeded(nodes)
    }

    /**
     * Fail-soft alpha-beta negamax. Score sign gives win/draw/loss; magnitude ranks
     * distance to the terminal board, with faster wins and slower losses preferred.
     */
    private fun negamax(position: Position, alphaIn: Int, betaIn: Int, checkCancelled: () -> Unit): Int {
        visit(checkCancelled)
        if (position.previousMoverWon()) return -(position.cells + 2 - position.ply) / 2
        if (position.winsInAnyColumn()) return (position.cells + 1 - position.ply) / 2

        val cache = table
        val originalAlpha = alphaIn
        val originalBeta = betaIn
        var alpha = alphaIn
        var beta = betaIn
        var preferredMove = -1
        val cacheKey = if (cache != null) {
            cache.keyFor(position.mine, position.theirs, position.ply)
        } else {
            0L
        }
        if (cache != null) {
            if (cache.readAtKey(cacheKey, position.mine, position.theirs, position.ply, transpositionProbe)) {
                transpositionHits++
                val cachedScore = transpositionProbe.score
                preferredMove = transpositionProbe.bestMove
                when (transpositionProbe.bound) {
                    1 -> return cachedScore
                    2 -> {
                        if (cachedScore >= beta) return cachedScore
                        if (cachedScore > alpha) alpha = cachedScore
                    }
                    else -> {
                        if (cachedScore <= alpha) return cachedScore
                        if (cachedScore < beta) beta = cachedScore
                    }
                }
            }
        }

        val playable = position.nonLosingColumns()
        if (playable == 0L) return -(position.cells - position.ply) / 2

        // With at least one non-losing move, the opponent cannot win on their next turn.
        val worstPossible = -((position.cells - position.ply - 2) / 2)
        if (alpha < worstPossible) {
            alpha = worstPossible
            if (alpha >= beta) return alpha
        }

        // The player to move cannot win at once, so their best score is winning on their next turn.
        val bestPossible = (position.cells - position.ply - 1).coerceAtLeast(0) / 2
        if (beta > bestPossible) {
            beta = bestPossible
            if (alpha >= beta) return beta
        }
        // With at most two cells left, a non-losing move reaches a draw (immediate wins were checked above).
        if (position.ply >= position.cells - 2) return 0

        var best = Int.MIN_VALUE / 4
        val middle = (position.width - 1) / 2
        val columns = moveOrderColumns[position.ply]
        val scores = moveOrderScores[position.ply]
        var moveCount = 0
        var candidates = playable
        if (java.lang.Long.bitCount(candidates) == 1) {
            columns[0] = java.lang.Long.numberOfTrailingZeros(candidates)
            moveCount = 1
            forcedMovesThatSkippedThreatScoring++
        } else {
            val threatScores = moveThreatScores[position.ply]
            position.fillThreatScores(
                threatScores,
                moveThreatScratch0[position.ply],
                moveThreatScratch1[position.ply],
            )
            while (candidates != 0L) {
                val bit = candidates and -candidates
                val col = java.lang.Long.numberOfTrailingZeros(bit)
                val moveScore = threatScores[col]
                var insertion = moveCount
                while (insertion > 0 &&
                    (moveScore > scores[insertion - 1] ||
                        (moveScore == scores[insertion - 1] &&
                            (kotlin.math.abs(col - middle) < kotlin.math.abs(columns[insertion - 1] - middle) ||
                                (kotlin.math.abs(col - middle) == kotlin.math.abs(columns[insertion - 1] - middle) &&
                                    col < columns[insertion - 1]))))
                ) {
                    columns[insertion] = columns[insertion - 1]
                    scores[insertion] = scores[insertion - 1]
                    insertion--
                }
                columns[insertion] = col
                scores[insertion] = moveScore
                moveCount++
                candidates = candidates xor bit
            }
        }
        if (preferredMove in 0 until position.width && (playable and (1L shl preferredMove)) != 0L) {
            var preferredIndex = -1
            for (index in 0 until moveCount) {
                if (columns[index] == preferredMove) {
                    preferredIndex = index
                    break
                }
            }
            if (preferredIndex > 0) {
                val preferredScore = scores[preferredIndex]
                for (index in preferredIndex downTo 1) {
                    columns[index] = columns[index - 1]
                    scores[index] = scores[index - 1]
                }
                columns[0] = preferredMove
                scores[0] = preferredScore
                transpositionMoveOrderingHits++
            }
        }
        var bestMove = -1
        for (index in 0 until moveCount) {
            if (alpha >= beta) break
            val child = positionFrames[position.searchDepth + 1]
            position.pushTo(child, columns[index])
            val score = try {
                -negamax(child, -beta, -alpha, checkCancelled)
            } finally {
                position.pop(columns[index])
            }
            if (score > best) {
                best = score
                bestMove = columns[index]
            }
            if (best > alpha) alpha = best
        }
        val bound = when {
            best <= originalAlpha -> 0
            best >= originalBeta -> 2
            else -> 1
        }
        if (cache != null) {
            cache.putAtKey(cacheKey, position.mine, position.theirs, position.ply, best, bound, bestMove)
        }
        return best
    }
}
