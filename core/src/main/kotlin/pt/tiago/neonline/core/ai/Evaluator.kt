package pt.tiago.neonline.core.ai

import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

/**
 * Integer evaluation from the point of view of the player to move. It scores every window of
 * [GameState.connect] cells, the playable threats of both sides and the centre control, weighted
 * by the rival's personality. No floats, no clock: the same position always scores the same.
 *
 * Threat information is returned as a bitmask over columns, and winning cells are checked with a
 * local scan around the candidate cell, so a node costs a few dozen array reads instead of a
 * board copy per column.
 */
object Evaluator {

    /** Weights for how many of a player's pieces sit inside one window. */
    private val WINDOW = intArrayOf(0, 1, 8, 64, 4096)

    fun evaluate(state: GameState, profile: AiProfile): Int {
        val me = state.toMove
        val them = me.opponent()
        var score = 0
        val width = state.width
        val height = state.height
        val connect = state.connect

        for (r in 0 until height) {
            for (c in 0 until width) {
                val piece = state.pieceAt(r, c)
                if (piece != null && piece.kind != PieceKind.LOCK) {
                    val centerBias = centerWeight(c, width)
                    if (piece.owner == me) score += centerBias * profile.center
                    else score -= centerBias * profile.center
                }
            }
        }

        val directions = intArrayOf(0, 1, 1, 0, 1, 1, 1, -1)
        for (r in 0 until height) {
            for (c in 0 until width) {
                var d = 0
                while (d < 8) {
                    val dr = directions[d]
                    val dc = directions[d + 1]
                    d += 2
                    val endR = r + (connect - 1) * dr
                    val endC = c + (connect - 1) * dc
                    if (endR !in 0 until height || endC !in 0 until width) continue
                    var mine = 0
                    var theirs = 0
                    var blocked = false
                    var k = 0
                    while (k < connect) {
                        val cell = (r + k * dr) * width + (c + k * dc)
                        if (!state.playable[cell]) {
                            blocked = true
                            break
                        }
                        val piece = state.pieces[cell]
                        if (piece != null) {
                            when {
                                piece.kind == PieceKind.WILD -> { mine++; theirs++ }
                                piece.owner == me -> mine++
                                else -> theirs++
                            }
                        }
                        k++
                    }
                    if (blocked || (mine > 0 && theirs > 0)) continue
                    if (mine > 0) score += WINDOW[mine] * profile.attack
                    if (theirs > 0) score -= WINDOW[theirs] * profile.defense
                }
            }
        }

        // Playable threats: the side to move profits from having more of them.
        val myThreats = java.lang.Long.bitCount(threatMask(state, me))
        val theirThreats = java.lang.Long.bitCount(threatMask(state, them))
        score += myThreats * profile.trap * 8
        score -= theirThreats * profile.trap * 8
        if (myThreats > theirThreats) score += 32 * profile.trap
        if (theirThreats > myThreats) score -= 32 * profile.trap
        return score
    }

    /** Bit [col] is set when dropping a piece in that column wins for [player] immediately. */
    fun threatMask(state: GameState, player: Player): Long {
        var mask = 0L
        for (col in 0 until state.width) {
            val row = landingRow(state, col) ?: continue
            if (winsIfPlayed(state, row, col, player)) mask = mask or (1L shl col)
        }
        return mask
    }

    fun countThreats(state: GameState, player: Player): Int =
        java.lang.Long.bitCount(threatMask(state, player))

    fun hasThreat(state: GameState, player: Player): Boolean = threatMask(state, player) != 0L

    private fun landingRow(state: GameState, col: Int): Int? {
        var r = state.height - 1
        while (r >= 0) {
            val cell = r * state.width + col
            if (!state.playable[cell] || state.pieces[cell] != null) r-- else return r
        }
        return null
    }

    private fun belongs(piece: Piece?, player: Player): Boolean =
        piece != null && (piece.owner == player || piece.kind == PieceKind.WILD)

    /** True when a piece of [player] at (row, col) completes a line, assuming the cell is free. */
    fun winsIfPlayed(state: GameState, row: Int, col: Int, player: Player): Boolean {
        val width = state.width
        val height = state.height
        val connect = state.connect
        val directions = intArrayOf(0, 1, 1, 0, 1, 1, 1, -1)
        var d = 0
        while (d < 8) {
            val dr = directions[d]
            val dc = directions[d + 1]
            d += 2
            var forward = 0
            var r = row + dr
            var c = col + dc
            while (r in 0 until height && c in 0 until width && belongs(state.pieces[r * width + c], player)) {
                forward++
                r += dr
                c += dc
            }
            if (1 + forward >= connect) return true
            var backward = 0
            r = row - dr
            c = col - dc
            while (r in 0 until height && c in 0 until width && belongs(state.pieces[r * width + c], player)) {
                backward++
                r -= dr
                c -= dc
            }
            if (1 + forward + backward >= connect) return true
        }
        return false
    }

    private fun centerWeight(col: Int, width: Int): Int {
        val middle = (width - 1) / 2.0
        val distance = Math.abs(col - middle).toInt()
        return if (distance > 2) 0 else 3 - distance
    }
}

/** Difficulty to the orientation profile; the level JSON overrides it with the rival's personality. */
fun profileFor(difficulty: pt.tiago.neonline.core.model.Difficulty): AiProfile =
    when (difficulty) {
        pt.tiago.neonline.core.model.Difficulty.EASY -> AiProfile.EASY
        pt.tiago.neonline.core.model.Difficulty.MEDIUM -> AiProfile.MEDIUM
        pt.tiago.neonline.core.model.Difficulty.HARD -> AiProfile.HARD
    }
