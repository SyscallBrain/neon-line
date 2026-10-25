package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

class BitBoardTest {

    @Test
    fun `single word connect four recognizes four directions without short runs`() {
        fun mask(vararg bits: Int): Long = bits.fold(0L) { value, index -> value or (1L shl index) }

        assertTrue(BitBoard.hasConnectFourLine(mask(0, 1, 2, 3), stride = 7))
        assertTrue(BitBoard.hasConnectFourLine(mask(2, 9, 16, 23), stride = 7))
        assertTrue(BitBoard.hasConnectFourLine(mask(0, 8, 16, 24), stride = 7))
        assertTrue(BitBoard.hasConnectFourLine(mask(3, 9, 15, 21), stride = 7))
        assertFalse(BitBoard.hasConnectFourLine(mask(0, 1, 2, 8, 9), stride = 7))
        assertFalse(BitBoard.hasConnectFourLine(mask(0, 1, 2), stride = 7))
    }

    @Test
    fun `connect four completion mask finds each missing cell in every direction`() {
        val stride = 7
        val directions = intArrayOf(1, stride, stride + 1, stride - 1)
        for (shift in directions) {
            val line = IntArray(4) { 10 + it * shift }
            for (missing in line.indices) {
                val bits = line.indices.filter { it != missing }
                    .fold(0L) { mask, index -> mask or (1L shl line[index]) }
                val expected = 1L shl line[missing]
                assertTrue(
                    "missing offset $missing in direction $shift",
                    (BitBoard.connectFourCompletionCandidates(bits, stride) and expected) != 0L,
                )
            }
        }

        assertEquals(0L, BitBoard.connectFourCompletionCandidates(0b11L, stride))
    }

    @Test
    fun `connect four winning landing cells map to their columns`() {
        val stride = 7
        val directions = intArrayOf(1, stride, stride + 1, stride - 1)
        for (shift in directions) {
            val line = IntArray(4) { 10 + it * shift }
            for (missing in line.indices) {
                val bits = line.indices.filter { it != missing }
                    .fold(0L) { mask, index -> mask or (1L shl line[index]) }
                val landing = line[missing]
                assertEquals(
                    "missing offset $missing in direction $shift",
                    1L shl (landing / stride),
                    BitBoard.connectFourWinningColumnMask(bits, 1L shl landing, stride),
                )
            }
        }
        assertEquals(0L, BitBoard.connectFourWinningColumnMask(0b111L, 0L, stride))
    }

    @Test
    fun `hypothetical win after another piece lands in the same column matches a full board copy`() {
        val state = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            "111....",
            firstToMove = Player.P1,
        )
        val opponent = BitBoard.of(state, Player.P2)

        assertTrue(opponent.wouldWinAfterOtherPlayerDropInSameColumn(3))
        assertFalse(opponent.wouldWin(3))

        val fullColumn = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "1111111",
        )
        val fullColumnBoard = BitBoard.of(fullColumn, Player.P2)
        assertFalse(fullColumnBoard.wouldWinAfterOtherPlayerDropInSameColumn(3))
    }

    @Test
    fun `bitboard and engine agree on 100000 random positions`() {
        val rng = SplitMix64(20261013L)
        var checked = 0
        var winningMovesSeen = 0
        repeat(100_000) {
            val width = 4 + rng.nextInt(6) // 4..9
            val height = 4 + rng.nextInt(4) // 4..7
            if (width * (height + 1) > 128) return@repeat
            val connect = if (rng.nextInt(2) == 0) 4 else 5
            val pieces = arrayOfNulls<Piece>(width * height)
            for (c in 0 until width) {
                val count = rng.nextInt(height + 1)
                for (k in 0 until count) {
                    val r = height - 1 - k
                    pieces[r * width + c] =
                        Piece(if (rng.nextInt(2) == 0) Player.P1 else Player.P2, PieceKind.NORMAL, 0)
                }
            }
            val state = GameState.initial(
                levelId = "bb",
                difficulty = Difficulty.EASY,
                engineVersion = GameEngine.ENGINE_VERSION,
                seedHex = "0000000000000001",
                width = width,
                height = height,
                connect = connect,
                playable = List(width * height) { true },
                pieces = pieces.toList(),
            )
            checked++
            for (player in Player.entries) {
                val bb = BitBoard.of(state, player)
                var expectedWinningMask = 0L
                for (c in 0 until width) {
                    val landing = GameEngine.landingRow(state.pieces, width, height, state.playable, c)
                    assertEquals("canPlay mismatch at col $c", landing != null, bb.canPlay(c))
                    if (landing == null) continue
                    val probe = state.pieces.toMutableList()
                    probe[landing * width + c] = Piece(player, PieceKind.NORMAL, 999)
                    val expected =
                        GameEngine.findLines(probe, width, height, connect, player).isNotEmpty()
                    val actual = bb.wouldWin(c)
                    assertEquals(
                        "win mismatch at ${width}x$height connect=$connect col=$c player=$player",
                        expected,
                        actual,
                    )
                    if (expected) expectedWinningMask = expectedWinningMask or (1L shl c)
                    if (actual) winningMovesSeen++
                }
                assertEquals("winning move mask mismatch", expectedWinningMask, bb.winningMoveMask())
                assertEquals(
                    "full-board mismatch",
                    GameEngine.validMoves(state).isEmpty(),
                    bb.isFull(),
                )
            }
        }
        assertTrue("property test covered too few positions: $checked", checked > 40_000)
        assertTrue("no winning move was ever generated", winningMovesSeen > 500)
    }

    @Test
    fun `bitboard detects a known horizontal win`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val bb = BitBoard.of(s, Player.P1)
        assertTrue(bb.wouldWin(3))
        assertFalse(bb.wouldWin(4))
        assertTrue(bb.canPlay(6))
    }

    @Test
    fun `non losing columns match simulated replies on random Connect Four positions`() {
        val rng = SplitMix64(20261024L)
        repeat(300) { sample ->
            var state = Boards.blank(
                width = 4 + sample % 6,
                height = 4 + sample % 4,
                connect = 4,
            )
            repeat(rng.nextInt(state.width * state.height)) {
                if (state.status == pt.tiago.neonline.core.model.Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != pt.tiago.neonline.core.model.Status.InProgress) return@repeat

            val mine = BitBoard.of(state, state.toMove)
            val theirs = BitBoard.of(state, state.toMove.opponent())
            var expected = 0L
            for (col in 0 until state.width) {
                if (!mine.canPlay(col)) continue
                val (opponentAfterDrop, _) = mine.playAndAdvanceOpponent(col, theirs)
                if (opponentAfterDrop.winningMoveMask() == 0L) expected = expected or (1L shl col)
            }

            assertEquals("safe columns mismatch at random position $sample", expected, mine.nonLosingColumnsAgainst(theirs))
        }
    }

    @Test
    fun `potential winning spots include empty cells that gravity cannot reach yet`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            "11.....",
            "222....",
            "222....",
        )
        val mine = BitBoard.of(s, Player.P1)
        val opponent = BitBoard.of(s, Player.P2)

        assertEquals(5, GameEngine.landingRow(s.pieces, s.width, s.height, s.playable, 3))
        assertEquals(1, mine.potentialWinningSpotCountAfterDrop(2, opponent))
    }

    @Test
    fun `potential winning spot scores match a cell-window scan on random positions`() {
        val rng = SplitMix64(20261024L)
        repeat(100) {
            var state = Boards.blank()
            repeat(8 + rng.nextInt(26)) {
                if (state.status == pt.tiago.neonline.core.model.Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != pt.tiago.neonline.core.model.Status.InProgress) return@repeat

            for (player in Player.entries) {
                val mine = BitBoard.of(state, player)
                val opponent = BitBoard.of(state, player.opponent())
                for (col in 0 until state.width) {
                    if (!mine.canPlay(col)) continue
                    val expected = expectedPotentialWinningSpotCount(state, player, col)
                    assertEquals(
                        "potential spots mismatch at ply ${state.pieces.count { it != null }} col $col player $player",
                        expected,
                        mine.potentialWinningSpotCountAfterDrop(col, opponent),
                    )
                }
            }
        }
    }

    @Test
    fun `batched potential winning spot scores match per-column scores on random positions`() {
        val rng = SplitMix64(20261024L)
        val scores = IntArray(9)
        val added0 = LongArray(9)
        val added1 = LongArray(9)
        repeat(100) { sample ->
            var state = if (sample % 2 == 0) Boards.blank() else Boards.blank(width = 9, height = 7, connect = 5)
            repeat(8 + rng.nextInt(26)) {
                if (state.status == pt.tiago.neonline.core.model.Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != pt.tiago.neonline.core.model.Status.InProgress) return@repeat

            Player.entries.forEach { player ->
                val mine = BitBoard.of(state, player)
                val opponent = BitBoard.of(state, player.opponent())
                if (sample % 2 == 0) {
                    mine.fillPotentialWinningSpotCountsOneWord(opponent, scores, added0)
                } else {
                    mine.fillPotentialWinningSpotCountsAfterDrops(opponent, scores, added0, added1)
                }
                for (col in 0 until state.width) {
                    val expected = if (mine.canPlay(col)) {
                        mine.potentialWinningSpotCountAfterDrop(col, opponent)
                    } else 0
                    assertEquals("batched threat score at col $col for $player", expected, scores[col])
                }
            }
        }
    }

    @Test
    fun `combined child bitboards match separate play and height advance`() {
        val rng = SplitMix64(20261025L)
        repeat(80) { sample ->
            var state = if (sample % 2 == 0) Boards.blank() else Boards.blank(width = 9, height = 7, connect = 5)
            repeat(8 + rng.nextInt(26)) {
                if (state.status == pt.tiago.neonline.core.model.Status.InProgress) {
                    val moves = GameEngine.validMoves(state)
                    state = GameEngine.apply(state, moves[rng.nextInt(moves.size)]).state
                }
            }
            if (state.status != pt.tiago.neonline.core.model.Status.InProgress) return@repeat

            val mine = BitBoard.of(state, state.toMove)
            val theirs = BitBoard.of(state, state.toMove.opponent())
            GameEngine.validMoves(state).filterIsInstance<pt.tiago.neonline.core.model.Move.Drop>().forEach { move ->
                val expectedMine = theirs.afterOpponentDrop(move.col)
                val expectedTheirs = mine.play(move.col)
                val (actualMine, actualTheirs) = mine.playAndAdvanceOpponent(move.col, theirs)
                for (word in 0..1) {
                    assertEquals(expectedMine.keyWord(word), actualMine.keyWord(word))
                    assertEquals(expectedMine.mirroredKeyWord(word), actualMine.mirroredKeyWord(word))
                    assertEquals(expectedTheirs.keyWord(word), actualTheirs.keyWord(word))
                    assertEquals(expectedTheirs.mirroredKeyWord(word), actualTheirs.mirroredKeyWord(word))
                }
                for (col in 0 until state.width) {
                    assertEquals(expectedMine.canPlay(col), actualMine.canPlay(col))
                    assertEquals(expectedTheirs.canPlay(col), actualTheirs.canPlay(col))
                }
            }
        }
    }

    @Test
    fun `mutable search drop matches immutable child and restores both bitboards`() {
        var state = Boards.blank()
        listOf(0, 4, 1, 5, 2, 6, 3, 0, 4).forEach { col ->
            state = GameEngine.apply(state, pt.tiago.neonline.core.model.Move.Drop(col)).state
        }
        val mine = BitBoard.of(state, state.toMove)
        val theirs = BitBoard.of(state, state.toMove.opponent())
        for (col in 0 until state.width) {
            if (!mine.canPlay(col)) continue
            val expected = mine.playAndAdvanceOpponent(col, theirs)
            val mineWords = (0..1).map(mine::keyWord)
            val mineMirrored = (0..1).map(mine::mirroredKeyWord)
            val theirsWords = (0..1).map(theirs::keyWord)
            val theirsMirrored = (0..1).map(theirs::mirroredKeyWord)
            val minePlayable = (0 until state.width).map(mine::canPlay)
            val theirsPlayable = (0 until state.width).map(theirs::canPlay)

            mine.playSearchDrop(col, theirs)
            for (word in 0..1) {
                assertEquals(expected.first.keyWord(word), theirs.keyWord(word))
                assertEquals(expected.first.mirroredKeyWord(word), theirs.mirroredKeyWord(word))
                assertEquals(expected.second.keyWord(word), mine.keyWord(word))
                assertEquals(expected.second.mirroredKeyWord(word), mine.mirroredKeyWord(word))
            }
            for (column in 0 until state.width) {
                assertEquals(expected.first.canPlay(column), theirs.canPlay(column))
                assertEquals(expected.second.canPlay(column), mine.canPlay(column))
            }

            mine.undoSearchDrop(col, theirs)
            assertEquals(mineWords, (0..1).map(mine::keyWord))
            assertEquals(mineMirrored, (0..1).map(mine::mirroredKeyWord))
            assertEquals(theirsWords, (0..1).map(theirs::keyWord))
            assertEquals(theirsMirrored, (0..1).map(theirs::mirroredKeyWord))
            assertEquals(minePlayable, (0 until state.width).map(mine::canPlay))
            assertEquals(theirsPlayable, (0 until state.width).map(theirs::canPlay))
        }
    }

    @Test
    fun `board instances reuse geometry masks for the same shape`() {
        val first = BitBoard.directionMasksFor(7, 6, 4)
        val second = BitBoard.directionMasksFor(7, 6, 4)

        assertSame(first, second)
    }

    @Test
    fun `mirrored key words match horizontal reflection across both words`() {
        var state = Boards.blank(width = 9, height = 7, connect = 5)
        for (column in listOf(0, 8, 1, 7, 2, 6, 3, 5)) {
            state = GameEngine.apply(state, pt.tiago.neonline.core.model.Move.Drop(column)).state
        }
        val reflected = state.copy(pieces = List(state.pieces.size) { index ->
            val row = index / state.width
            val col = index % state.width
            state.pieceAt(row, state.width - 1 - col)
        })

        for (player in Player.entries) {
            val originalBoard = BitBoard.of(state, player)
            val reflectedBoard = BitBoard.of(reflected, player)
            for (word in 0..1) {
                assertEquals(
                    "reflected key mismatch for $player word $word",
                    reflectedBoard.keyWord(word),
                    originalBoard.mirroredKeyWord(word),
                )
            }
            val afterDrop = originalBoard.play(1)
            val reflectedAfterDrop = reflectedBoard.play(7)
            for (word in 0..1) {
                assertEquals(
                    "derived reflected key mismatch for $player word $word",
                    reflectedAfterDrop.keyWord(word),
                    afterDrop.mirroredKeyWord(word),
                )
            }
        }
    }

    private fun expectedPotentialWinningSpotCount(state: GameState, player: Player, col: Int): Int {
        val landing = GameEngine.landingRow(state.pieces, state.width, state.height, state.playable, col)
            ?: return 0
        val pieces = state.pieces.toMutableList()
        pieces[landing * state.width + col] = Piece(player, PieceKind.NORMAL, 0)
        val spots = BooleanArray(state.width * state.height)
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

        for (row in 0 until state.height) {
            for (candidateCol in 0 until state.width) {
                val candidate = row * state.width + candidateCol
                if (pieces[candidate] != null) continue
                for ((dr, dc) in directions) {
                    for (offset in 0 until state.connect) {
                        val startRow = row - offset * dr
                        val startCol = candidateCol - offset * dc
                        val endRow = startRow + (state.connect - 1) * dr
                        val endCol = startCol + (state.connect - 1) * dc
                        if (startRow !in 0 until state.height || endRow !in 0 until state.height ||
                            startCol !in 0 until state.width || endCol !in 0 until state.width
                        ) continue

                        var completesLine = true
                        for (step in 0 until state.connect) {
                            val r = startRow + step * dr
                            val c = startCol + step * dc
                            if (r == row && c == candidateCol) continue
                            if (pieces[r * state.width + c]?.owner != player) {
                                completesLine = false
                                break
                            }
                        }
                        if (completesLine) {
                            spots[candidate] = true
                            break
                        }
                    }
                    if (spots[candidate]) break
                }
            }
        }
        return spots.count { it }
    }

    @Test
    fun `derived boards inherit geometry masks from their parent`() {
        val parent = BitBoard.of(Boards.blank(), Player.P1)

        assertSame(parent.directionMasks, parent.play(3).directionMasks)
        assertSame(parent.directionMasks, parent.afterOpponentDrop(3).directionMasks)
    }

    @Test
    fun `bitboard detects a known vertical win`() {
        val s = Boards.state(
            ".......",
            ".......",
            "..1....",
            "..1....",
            "..1....",
            ".......",
        )
        val bb = BitBoard.of(s, Player.P1)
        assertTrue(bb.wouldWin(2))
    }

    @Test
    fun `bitboard detects a known diagonal win`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            "..122..",
            ".1222..",
            "11222..",
        )
        val bb = BitBoard.of(s, Player.P1)
        assertTrue(bb.wouldWin(3))
    }

    @Test
    fun `play stacks pieces and reports fullness`() {
        var bb = BitBoard.of(Boards.blank(), Player.P1)
        assertFalse(bb.isFull())
        repeat(6) { bb = bb.play(0) }
        assertTrue(bb.canPlay(1))
        assertFalse(bb.canPlay(0))
        val full = BitBoard.of(
            Boards.state(
                "1111111",
                "2222222",
                "1111111",
                "2222222",
                "1111111",
                "2222222",
            ),
            Player.P1,
        )
        assertTrue(full.isFull())
        assertFalse(full.canPlay(0))
    }

    @Test
    fun `supports only classic boards`() {
        val classic = Boards.blank()
        assertTrue(BitBoard.supports(classic))
        val popOut = Boards.blank(popOut = true)
        assertFalse(BitBoard.supports(popOut))
        val blocked = Boards.state(
            "#......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
        )
        assertFalse(BitBoard.supports(blocked))
        val wild = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "A......",
        )
        assertFalse(BitBoard.supports(wild))
    }
}
