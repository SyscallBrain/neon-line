package pt.tiago.neonline.core.rules

import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-Long bitboard for the classic rules (connect 4 or 5, no blocked cells, no specials, no Pop
 * Out). This is the fast representation used by the AI and the perfect solver; it is tested against
 * the general engine on random positions.
 *
 * Layout: column-major with one sentinel row above every column. The bit of the engine cell
 * (row, col) is `col * (height + 1) + (height - 1 - row)`, so the bottom row of a column holds the
 * low bits and the sentinel keeps runs from leaking into the next column. Boards up to
 * `width * (height + 1) <= 128` bits fit (up to 9x7); anything bigger falls back to the engine.
 */
class BitBoard private constructor(
    val width: Int,
    val height: Int,
    val connect: Int,
    private val position: LongArray,
    private val mirroredPosition: LongArray,
    private val heights: IntArray,
    internal val directionMasks: List<Pair<Int, LongArray>>,
    private val winningWindows: LongArray,
) {
    /** Would dropping a piece in [col] complete a line for the owner of this board? */
    fun wouldWin(col: Int): Boolean {
        if (!canPlay(col)) return false
        val index = col * (height + 1) + heights[col]
        val bit = 1L shl (index % 64)
        return if (index < 64) wins(position[0] or bit, position[1]) else wins(position[0], position[1] or bit)
    }

    fun canPlay(col: Int): Boolean = heights[col] < height

    /** Bit mask of columns where this player can win on the next drop. */
    internal fun winningMoveMask(): Long {
        if (connect == 4 && width * (height + 1) <= 64 && position[1] == 0L) {
            val stride = height + 1
            if (hasConnectFourLine(position[0], stride)) {
                var playable = 0L
                for (col in 0 until width) {
                    if (canPlay(col)) playable = playable or (1L shl col)
                }
                return playable
            }
            var landingCells = 0L
            for (col in 0 until width) {
                if (canPlay(col)) landingCells = landingCells or (1L shl (col * stride + heights[col]))
            }
            return connectFourWinningColumnMask(position[0], landingCells, stride)
        }
        var result = 0L
        for (col in 0 until width) {
            if (canPlay(col) && wouldWin(col)) result = result or (1L shl col)
        }
        return result
    }

    /** Number of distinct empty cells that complete a line after [col], even if unsupported. */
    internal fun potentialWinningSpotCountAfterDrop(col: Int, opponent: BitBoard): Int {
        require(canPlay(col))
        require(width == opponent.width && height == opponent.height && connect == opponent.connect)
        val index = col * (height + 1) + heights[col]
        val mine0 = position[0] or if (index < 64) (1L shl index) else 0L
        val mine1 = position[1] or if (index >= 64) (1L shl (index - 64)) else 0L
        var spots0 = 0L
        var spots1 = 0L
        var window = 0
        while (window < winningWindows.size) {
            val mask0 = winningWindows[window]
            val mask1 = winningWindows[window + 1]
            val ownCount = java.lang.Long.bitCount(mine0 and mask0) +
                java.lang.Long.bitCount(mine1 and mask1)
            if (ownCount == connect - 1 &&
                (opponent.position[0] and mask0) == 0L && (opponent.position[1] and mask1) == 0L
            ) {
                spots0 = spots0 or (mask0 and (mine0 or opponent.position[0]).inv())
                spots1 = spots1 or (mask1 and (mine1 or opponent.position[1]).inv())
            }
            window += 2
        }
        return java.lang.Long.bitCount(spots0) + java.lang.Long.bitCount(spots1)
    }

    /** Allocation-free form for the solver, which supplies scratch storage per search ply. */
    internal fun fillPotentialWinningSpotCountsAfterDrops(
        opponent: BitBoard,
        scores: IntArray,
        added0: LongArray,
        added1: LongArray,
    ) {
        require(width == opponent.width && height == opponent.height && connect == opponent.connect)
        require(scores.size >= width && added0.size >= width && added1.size >= width)
        if (width * (height + 1) <= 64 && position[1] == 0L && opponent.position[1] == 0L) {
            fillPotentialWinningSpotCountsOneWord(opponent, scores, added0)
            java.util.Arrays.fill(added1, 0L)
            return
        }
        java.util.Arrays.fill(scores, 0)
        java.util.Arrays.fill(added0, 0L)
        java.util.Arrays.fill(added1, 0L)
        var existing0 = 0L
        var existing1 = 0L
        val occupied0 = position[0] or opponent.position[0]
        val occupied1 = position[1] or opponent.position[1]
        val stride = height + 1
        var window = 0
        while (window < winningWindows.size) {
            val mask0 = winningWindows[window]
            val mask1 = winningWindows[window + 1]
            val ownCount = java.lang.Long.bitCount(position[0] and mask0) +
                java.lang.Long.bitCount(position[1] and mask1)
            if ((opponent.position[0] and mask0) == 0L && (opponent.position[1] and mask1) == 0L) {
                val empty0 = mask0 and (occupied0.inv())
                val empty1 = mask1 and (occupied1.inv())
                when (ownCount) {
                    connect - 1 -> {
                        existing0 = existing0 or empty0
                        existing1 = existing1 or empty1
                    }
                    connect - 2 -> {
                        var candidates0 = empty0
                        while (candidates0 != 0L) {
                            val bit = candidates0 and -candidates0
                            val cell = java.lang.Long.numberOfTrailingZeros(bit)
                            val col = cell / stride
                            if (col < width && heights[col] == cell % stride) {
                                added0[col] = added0[col] or (empty0 xor bit)
                                added1[col] = added1[col] or empty1
                            }
                            candidates0 = candidates0 xor bit
                        }
                        var candidates1 = empty1
                        while (candidates1 != 0L) {
                            val bit = candidates1 and -candidates1
                            val cell = java.lang.Long.numberOfTrailingZeros(bit) + 64
                            val col = cell / stride
                            if (col < width && heights[col] == cell % stride) {
                                added0[col] = added0[col] or empty0
                                added1[col] = added1[col] or (empty1 xor bit)
                            }
                            candidates1 = candidates1 xor bit
                        }
                    }
                }
            }
            window += 2
        }

        for (col in 0 until width) {
            if (!canPlay(col)) continue
            val landing = col * stride + heights[col]
            val landing0 = if (landing < 64) 1L shl landing else 0L
            val landing1 = if (landing >= 64) 1L shl (landing - 64) else 0L
            scores[col] = java.lang.Long.bitCount((existing0 and landing0.inv()) or added0[col]) +
                java.lang.Long.bitCount((existing1 and landing1.inv()) or added1[col])
        }
    }

    /** Fast window scan when the whole board currently fits in one bitboard word. */
    internal fun fillPotentialWinningSpotCountsOneWord(
        opponent: BitBoard,
        scores: IntArray,
        added: LongArray,
    ) {
        require(width == opponent.width && height == opponent.height && connect == opponent.connect)
        require(width * (height + 1) <= 64 && position[1] == 0L && opponent.position[1] == 0L)
        require(scores.size >= width && added.size >= width)
        java.util.Arrays.fill(scores, 0)
        java.util.Arrays.fill(added, 0L)

        if (connect == 4) {
            val stride = height + 1
            val occupied = position[0] or opponent.position[0]
            val columnMask = (1L shl height) - 1L
            var validCells = 0L
            for (col in 0 until width) validCells = validCells or (columnMask shl (col * stride))
            val availableSpots = validCells and occupied.inv()
            for (col in 0 until width) {
                if (!canPlay(col)) continue
                val landing = col * stride + heights[col]
                val landingBit = 1L shl landing
                val winningSpots = connectFourCompletionCandidates(position[0] or landingBit, stride)
                val emptySpots = winningSpots and availableSpots
                scores[col] = java.lang.Long.bitCount(emptySpots) -
                    if ((emptySpots and landingBit) != 0L) 1 else 0
            }
            return
        }

        val occupied = position[0] or opponent.position[0]
        val stride = height + 1
        var existing = 0L
        var window = 0
        while (window < winningWindows.size) {
            val mask = winningWindows[window]
            val ownCount = java.lang.Long.bitCount(position[0] and mask)
            if ((opponent.position[0] and mask) == 0L) {
                val empty = mask and occupied.inv()
                when (ownCount) {
                    connect - 1 -> existing = existing or empty
                    connect - 2 -> {
                        var candidates = empty
                        while (candidates != 0L) {
                            val bit = candidates and -candidates
                            val cell = java.lang.Long.numberOfTrailingZeros(bit)
                            val col = cell / stride
                            if (col < width && heights[col] == cell % stride) {
                                added[col] = added[col] or (empty xor bit)
                            }
                            candidates = candidates xor bit
                        }
                    }
                }
            }
            window += 2
        }

        for (col in 0 until width) {
            if (!canPlay(col)) continue
            val landing = col * stride + heights[col]
            scores[col] = java.lang.Long.bitCount((existing and (1L shl landing).inv()) or added[col])
        }
    }

    /** True if this player's next turn wins after another piece is dropped in [col]. */
    internal fun wouldWinAfterDropInSameColumn(col: Int): Boolean =
        wouldWinAfterOtherPlayerDropInSameColumn(col)

    /** Tests the next landing cell directly without copying this board or its column heights. */
    internal fun wouldWinAfterOtherPlayerDropInSameColumn(col: Int): Boolean {
        if (!canPlay(col) || heights[col] + 1 >= height) return false
        val index = col * (height + 1) + heights[col] + 1
        return if (index < 64) {
            wins(position[0] or (1L shl index), position[1])
        } else {
            wins(position[0], position[1] or (1L shl (index - 64)))
        }
    }

    /**
     * Bit mask of playable columns that do not give [opponent] an immediate win.
     * Only a drop column changes the opponent's next landing cell; threats in other columns
     * remain unchanged, so they can be resolved without constructing every child position.
     */
    internal fun nonLosingColumnsAgainst(opponent: BitBoard): Long {
        require(width == opponent.width && height == opponent.height && connect == opponent.connect)
        var playableMask = 0L
        for (col in 0 until width) {
            if (!canPlay(col)) continue
            playableMask = playableMask or (1L shl col)
        }
        val opponentThreatMask = opponent.winningMoveMask() and playableMask
        if (opponentThreatMask.countOneBits() > 1) return 0L
        if (opponentThreatMask != 0L) {
            val block = java.lang.Long.numberOfTrailingZeros(opponentThreatMask)
            return if (opponent.wouldWinAfterDropInSameColumn(block)) 0L else opponentThreatMask
        }

        if (connect == 4 && width * (height + 1) <= 64 && position[1] == 0L && opponent.position[1] == 0L) {
            val stride = height + 1
            var nextLandingCells = 0L
            for (col in 0 until width) {
                if (canPlay(col) && heights[col] + 1 < height) {
                    nextLandingCells = nextLandingCells or (1L shl (col * stride + heights[col] + 1))
                }
            }
            var unsafeColumns = 0L
            var threats = connectFourCompletionCandidates(opponent.position[0], stride) and nextLandingCells
            while (threats != 0L) {
                val bit = threats and -threats
                unsafeColumns = unsafeColumns or (1L shl (java.lang.Long.numberOfTrailingZeros(bit) / stride))
                threats = threats xor bit
            }
            return playableMask and unsafeColumns.inv()
        }

        var safeMask = playableMask
        var candidates = playableMask
        while (candidates != 0L) {
            val bit = candidates and -candidates
            val col = java.lang.Long.numberOfTrailingZeros(bit)
            if (opponent.wouldWinAfterOtherPlayerDropInSameColumn(col)) safeMask = safeMask and bit.inv()
            candidates = candidates xor bit
        }
        return safeMask
    }

    fun isFull(): Boolean = (0 until width).none { canPlay(it) }

    fun hasAnyMove(): Boolean = (0 until width).any { canPlay(it) }

    /** Read-only words for collision-checked solver keys. */
    internal fun keyWord(index: Int): Long = position[index]

    /** Read-only words for the horizontally reflected solver key. */
    internal fun mirroredKeyWord(index: Int): Long = mirroredPosition[index]

    /** True when the owner of this board already has a line of [connect] pieces. */
    fun hasLine(): Boolean = wins(position[0], position[1])

    /** Plays in [col] for this board's owner and returns the new board. */
    fun play(col: Int): BitBoard {
        val index = col * (height + 1) + heights[col]
        val next = position.copyOf()
        next[index / 64] = next[index / 64] or (1L shl (index % 64))
        val nextMirrored = mirroredPosition.copyOf()
        val mirroredIndex = (width - 1 - col) * (height + 1) + heights[col]
        nextMirrored[mirroredIndex / 64] =
            nextMirrored[mirroredIndex / 64] or (1L shl (mirroredIndex % 64))
        val nextHeights = heights.copyOf()
        nextHeights[col] = nextHeights[col] + 1
        return BitBoard(width, height, connect, next, nextMirrored, nextHeights, directionMasks, winningWindows)
    }

    /** Advances both search perspectives with one shared copy of the column heights. */
    internal fun playAndAdvanceOpponent(col: Int, opponent: BitBoard): Pair<BitBoard, BitBoard> {
        require(canPlay(col))
        require(width == opponent.width && height == opponent.height && connect == opponent.connect)
        val stride = height + 1
        val nextHeights = heights.copyOf()
        nextHeights[col]++

        val index = col * stride + heights[col]
        val nextPosition = position.copyOf()
        nextPosition[index / 64] = nextPosition[index / 64] or (1L shl (index % 64))
        val mirroredIndex = (width - 1 - col) * stride + heights[col]
        val nextMirrored = mirroredPosition.copyOf()
        nextMirrored[mirroredIndex / 64] = nextMirrored[mirroredIndex / 64] or
            (1L shl (mirroredIndex % 64))

        val nextMine = BitBoard(
            width, height, connect, opponent.position, opponent.mirroredPosition,
            nextHeights, directionMasks, winningWindows,
        )
        val nextTheirs = BitBoard(
            width, height, connect, nextPosition, nextMirrored,
            nextHeights, directionMasks, winningWindows,
        )
        return nextMine to nextTheirs
    }

    /** Mutable drop for the solver's depth-first search; [opponent] shares the updated heights. */
    internal fun playSearchDrop(col: Int, opponent: BitBoard) {
        val stride = height + 1
        val row = heights[col]
        val index = col * stride + row
        position[index / 64] = position[index / 64] or (1L shl (index % 64))
        val mirroredIndex = (width - 1 - col) * stride + row
        mirroredPosition[mirroredIndex / 64] =
            mirroredPosition[mirroredIndex / 64] or (1L shl (mirroredIndex % 64))
        heights[col] = row + 1
        opponent.heights[col] = row + 1
    }

    /** Undoes the most recent [playSearchDrop] on this board and its opponent. */
    internal fun undoSearchDrop(col: Int, opponent: BitBoard) {
        val stride = height + 1
        val row = heights[col] - 1
        val index = col * stride + row
        position[index / 64] = position[index / 64] and (1L shl (index % 64)).inv()
        val mirroredIndex = (width - 1 - col) * stride + row
        mirroredPosition[mirroredIndex / 64] =
            mirroredPosition[mirroredIndex / 64] and (1L shl (mirroredIndex % 64)).inv()
        heights[col] = row
        opponent.heights[col] = row
    }

    /** Advance shared occupancy after the other player drops, without changing this owner's bits. */
    fun afterOpponentDrop(col: Int): BitBoard {
        require(col in 0 until width && canPlay(col))
        val nextHeights = heights.copyOf()
        nextHeights[col]++
        return BitBoard(width, height, connect, position, mirroredPosition, nextHeights, directionMasks, winningWindows)
    }

    private fun wins(p0: Long, p1: Long): Boolean {
        if (connect == 4 && p1 == 0L) return hasConnectFourLine(p0, height + 1)
        for ((stride, mask) in directionMasks) {
            var m0 = p0
            var m1 = p1
            repeat(connect - 1) {
                val amount = kotlin.math.abs(stride)
                var shifted0 = 0L
                var shifted1 = 0L
                if (stride > 0) {
                    when {
                        amount < 64 -> {
                            shifted0 = (m0 ushr amount) or (m1 shl (64 - amount))
                            shifted1 = m1 ushr amount
                        }
                        amount == 64 -> shifted0 = m1
                        else -> shifted0 = m1 ushr (amount - 64)
                    }
                } else {
                    when {
                        amount < 64 -> {
                            shifted0 = m0 shl amount
                            shifted1 = (m1 shl amount) or (m0 ushr (64 - amount))
                        }
                        amount == 64 -> shifted1 = m0
                        else -> shifted1 = m0 shl (amount - 64)
                    }
                }
                m0 = m0 and shifted0
                m1 = m1 and shifted1
            }
            if ((m0 and mask[0]) != 0L || (m1 and mask[1]) != 0L) return true
        }
        return false
    }

    /** (dl, dc) steps in local coordinates, where local row 0 is the bottom row. */
    companion object {
        /** Fast Connect Four test for bitboards whose occupied cells fit in the low word. */
        internal fun hasConnectFourLine(bits: Long, stride: Int): Boolean =
            hasRun(bits, 1) || hasRun(bits, stride) || hasRun(bits, stride + 1) || hasRun(bits, stride - 1)

        /** Empty cells that complete a four-piece line when added to [bits]. */
        internal fun connectFourCompletionCandidates(bits: Long, stride: Int): Long =
            completionCandidates(bits, 1) or
                completionCandidates(bits, stride) or
                completionCandidates(bits, stride + 1) or
                completionCandidates(bits, stride - 1)

        /** Winning cells restricted to current landings, mapped from cell bits to column bits. */
        internal fun connectFourWinningColumnMask(bits: Long, landingCells: Long, stride: Int): Long {
            var winningCells = connectFourCompletionCandidates(bits, stride) and landingCells
            var result = 0L
            while (winningCells != 0L) {
                val bit = winningCells and -winningCells
                val cell = java.lang.Long.numberOfTrailingZeros(bit)
                result = result or (1L shl (cell / stride))
                winningCells = winningCells xor bit
            }
            return result
        }

        private fun completionCandidates(bits: Long, shift: Int): Long {
            val at0 = bits ushr shift and (bits ushr (2 * shift)) and (bits ushr (3 * shift))
            val at1 = bits and (bits ushr (2 * shift)) and (bits ushr (3 * shift))
            val at2 = bits and (bits ushr shift) and (bits ushr (3 * shift))
            val at3 = bits and (bits ushr shift) and (bits ushr (2 * shift))
            return at0 or (at1 shl shift) or (at2 shl (2 * shift)) or (at3 shl (3 * shift))
        }

        private fun hasRun(bits: Long, shift: Int): Boolean {
            val adjacent = bits and (bits ushr shift)
            return adjacent and (adjacent ushr (2 * shift)) != 0L
        }

        private data class BoardShape(val width: Int, val height: Int, val connect: Int)

        private val directionMaskCache = ConcurrentHashMap<BoardShape, List<Pair<Int, LongArray>>>()
        private val winningWindowCache = ConcurrentHashMap<BoardShape, LongArray>()

        /** Shares immutable geometry masks across bitboards with the same shape. */
        internal fun directionMasksFor(width: Int, height: Int, connect: Int): List<Pair<Int, LongArray>> =
            directionMaskCache.computeIfAbsent(BoardShape(width, height, connect)) {
                buildMasks(width, height, connect)
            }

        private fun winningWindowsFor(width: Int, height: Int, connect: Int): LongArray =
            winningWindowCache.computeIfAbsent(BoardShape(width, height, connect)) {
                val windows = ArrayList<Long>()
                for ((dl, dc) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                    for (col in 0 until width) {
                        for (localRow in 0 until height) {
                            var mask0 = 0L
                            var mask1 = 0L
                            var fits = true
                            for (offset in 0 until connect) {
                                val row = localRow + offset * dl
                                val c = col + offset * dc
                                if (row !in 0 until height || c !in 0 until width) {
                                    fits = false
                                    break
                                }
                                val bit = c * (height + 1) + row
                                if (bit < 64) mask0 = mask0 or (1L shl bit)
                                else mask1 = mask1 or (1L shl (bit - 64))
                            }
                            if (fits) {
                                windows += mask0
                                windows += mask1
                            }
                        }
                    }
                }
                windows.toLongArray()
            }

        private fun buildMasks(width: Int, height: Int, connect: Int): List<Pair<Int, LongArray>> {
            val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1) // up, right, up-right, up-left
            return directions.map { (dl, dc) ->
                val mask = LongArray(2)
                for (col in 0 until width) {
                    for (local in 0 until height) {
                        var fits = true
                        for (k in 0 until connect) {
                            val r = local + k * dl
                            val c = col + k * dc
                            if (r !in 0 until height || c !in 0 until width) {
                                fits = false
                                break
                            }
                        }
                        if (!fits) continue
                        val index = col * (height + 1) + local
                        mask[index / 64] = mask[index / 64] or (1L shl (index % 64))
                    }
                }
                val stride = when (dl to dc) {
                    1 to 0 -> 1
                    0 to 1 -> height + 1
                    1 to 1 -> height + 2
                    else -> -height // up-left
                }
                stride to mask
            }
        }

        /** True when [state] can be represented by a bitboard: classic rules, rectangular board, normal pieces. */
        fun supports(state: GameState): Boolean =
            !state.popOut &&
                state.rotateEvery == null &&
                state.playable.all { it } &&
                state.pieces.all { it == null || it.kind == PieceKind.NORMAL } &&
                state.width * (state.height + 1) <= 128

        /** The bitboard of [player]: only that player's pieces are set. */
        fun of(state: GameState, player: Player): BitBoard {
            val position = LongArray(2)
            val mirroredPosition = LongArray(2)
            val heights = IntArray(state.width)
            for (col in 0 until state.width) {
                var count = 0
                var r = state.height - 1
                while (r >= 0) {
                    val piece = state.pieceAt(r, col)
                    if (piece != null) {
                        if (piece.owner == player) {
                            val index = col * (state.height + 1) + count
                            position[index / 64] = position[index / 64] or (1L shl (index % 64))
                            val mirroredIndex = (state.width - 1 - col) * (state.height + 1) + count
                            mirroredPosition[mirroredIndex / 64] =
                                mirroredPosition[mirroredIndex / 64] or (1L shl (mirroredIndex % 64))
                        }
                        count++
                    }
                    r--
                }
                heights[col] = count
            }
            return BitBoard(
                state.width,
                state.height,
                state.connect,
                position,
                mirroredPosition,
                heights,
                directionMasksFor(state.width, state.height, state.connect),
                winningWindowsFor(state.width, state.height, state.connect),
            )
        }
    }
}
