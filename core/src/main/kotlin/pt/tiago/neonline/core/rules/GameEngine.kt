package pt.tiago.neonline.core.rules

import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Line
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.MoveResult
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Rejections
import pt.tiago.neonline.core.model.ShapeRule
import pt.tiago.neonline.core.model.Status

/**
 * The one engine. A move is resolved entirely by [apply]: it returns the new state, the events that
 * explain the change and how the match ended. The UI only animates the events, so the frame rate
 * never affects the result. The per-move clock lives in the app and arrives as [Move.TimeUp].
 *
 * Determinism: no wall clock, no platform randomness. The only streams are the two SplitMix64
 * instances carried in the state (auxiliary for TimeUp, AI for the rival personalities).
 */
object GameEngine {
    /** Published campaign/certificate baseline; existing content remains pinned to version2. */
    const val ENGINE_VERSION = 2
    /** Opt-in engine for new free duels. State hashes pin this independently of catalog content. */
    const val SOLID_FLOOR_ENGINE_VERSION = 3
    /** Clockwise gravity and explicit rotation-before-settlement events for new free/local games. */
    const val CLOCKWISE_ROTATION_ENGINE_VERSION = 4

    // ---------------------------------------------------------------- public API

    fun apply(state: GameState, move: Move): MoveResult {
        if (state.status != Status.InProgress) return reject(state, Rejections.GAME_OVER)
        return when (move) {
            is Move.Drop -> applyDrop(state, move.col, move.kind, prepend = emptyList())
            is Move.Pop -> applyPop(state, move.col)
            Move.TimeUp -> timeUp(state)
        }
    }

    /** Every legal move for the player to move. */
    fun validMoves(state: GameState): List<Move> {
        val mover = state.toMove
        val moves = mutableListOf<Move>()
        for (col in 0 until state.width) {
            if (state.lockedCol == col && state.lockedFor == mover) continue
            if (landingRow(state.pieces, state.width, state.height, state.playable, col) == null) continue
            moves += Move.Drop(col, PieceKind.NORMAL)
            for ((kind, count) in state.inventory[mover].orEmpty()) {
                if (count > 0 && kind != PieceKind.NORMAL) moves += Move.Drop(col, kind)
            }
        }
        if (state.popOut) {
            for (col in 0 until state.width) {
                if (state.lockedCol == col && state.lockedFor == mover) continue
                if (canPopColumn(state.pieces, state.width, state.height, col, mover)) {
                    moves += Move.Pop(col)
                }
            }
        }
        return moves
    }

    /** True when the player to move has at least one legal move. */
    fun hasAnyMove(state: GameState): Boolean = validMoves(state).isNotEmpty()

    /** All winning lines of [player] on the given board. A wild piece counts for both players. */
    fun findLines(pieces: List<Piece?>, width: Int, height: Int, connect: Int, player: Player): List<Line> {
        val lines = mutableListOf<Line>()
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until height) {
            for (c in 0 until width) {
                val cell = r * width + c
                val piece = pieces[cell] ?: continue
                if (!belongs(piece, player)) continue
                for ((dr, dc) in directions) {
                    val pr = r - dr
                    val pc = c - dc
                    if (pr in 0 until height && pc in 0 until width && belongs(pieces[pr * width + pc], player)) {
                        continue // continuation of a run that starts earlier
                    }
                    val cells = mutableListOf(cell)
                    var nr = r + dr
                    var nc = c + dc
                    while (nr in 0 until height && nc in 0 until width && belongs(pieces[nr * width + nc], player)) {
                        cells += nr * width + nc
                        nr += dr
                        nc += dc
                    }
                    if (cells.size >= connect) lines += Line(player, cells)
                }
            }
        }
        return lines
    }

    // ---------------------------------------------------------------- drops

    private fun applyDrop(
        state: GameState,
        col: Int,
        kind: PieceKind,
        prepend: List<GameEvent>,
    ): MoveResult {
        val mover = state.toMove
        if (col !in 0 until state.width) return reject(state, Rejections.OUT_OF_RANGE, prepend)
        if (state.lockedCol == col && state.lockedFor == mover) return reject(state, Rejections.COLUMN_LOCKED, prepend)
        val row = landingRow(state.pieces, state.width, state.height, state.playable, col)
            ?: return reject(state, Rejections.COLUMN_FULL, prepend)
        var inventory = state.inventory
        if (kind != PieceKind.NORMAL) {
            val count = inventory[mover]?.get(kind) ?: 0
            if (count <= 0) return reject(state, Rejections.NO_SUCH_PIECE, prepend)
            inventory = inventory + (mover to (inventory[mover]!!.toMutableMap().apply { put(kind, count - 1) }))
        }
        val events = mutableListOf<GameEvent>()
        events += prepend
        val pieces = state.pieces.toMutableList()
        events += GameEvent.Dropped(col, row, Piece(mover, kind, state.ply), fromRow = -1)
        if (kind == PieceKind.BOMB) {
            val cleared = explode(pieces, state.width, state.height, row, col)
            for (cell in cleared) pieces[cell] = null
            events += GameEvent.BombExploded(state.index(row, col), cleared)
            settle(pieces, state, events, cleared.map { state.colOf(it) }.toSet())
        } else {
            pieces[state.index(row, col)] = Piece(mover, kind, state.ply)
        }
        return finish(state, pieces, inventory, events, mover, kind)
    }

    private fun timeUp(state: GameState): MoveResult {
        val mover = state.toMove
        val cols = (0 until state.width).filter { col ->
            state.lockedCol != col || state.lockedFor != mover
        }.filter { col ->
            landingRow(state.pieces, state.width, state.height, state.playable, col) != null
        }
        if (cols.isEmpty()) return reject(state, Rejections.COLUMN_FULL)
        val rng = state.auxRng()
        val col = cols[rng.nextInt(cols.size)]
        // Advance the auxiliary stream as part of the move so replays match exactly.
        return applyDrop(
            state.copy(auxStateHex = rng.stateHex()),
            col,
            PieceKind.NORMAL,
            prepend = listOf(GameEvent.TimeUpPlayed(col)),
        )
    }

    // ---------------------------------------------------------------- pop out

    private fun applyPop(state: GameState, col: Int): MoveResult {
        val mover = state.toMove
        if (!state.popOut) return reject(state, Rejections.NO_POP_RULE)
        if (col !in 0 until state.width) return reject(state, Rejections.OUT_OF_RANGE)
        if (state.lockedCol == col && state.lockedFor == mover) return reject(state, Rejections.COLUMN_LOCKED)
        val row = bottomPieceRow(state.pieces, state.width, state.height, col)
            ?: return reject(state, Rejections.NOTHING_TO_POP)
        val piece = state.pieceAt(row, col)!!
        if (piece.owner != mover) return reject(state, Rejections.NOT_YOUR_PIECE)
        if (piece.kind != PieceKind.NORMAL) return reject(state, Rejections.NOT_NORMAL_PIECE)
        val events = mutableListOf<GameEvent>()
        val pieces = state.pieces.toMutableList()
        pieces[state.index(row, col)] = null
        events += GameEvent.Popped(col, piece)
        settle(pieces, state, events, setOf(col))
        return finish(state, pieces, state.inventory, events, mover, PieceKind.NORMAL)
    }

    // ---------------------------------------------------------------- shared tail of a move

    private fun finish(
        state: GameState,
        pieces: List<Piece?>,
        inventory: Map<Player, Map<PieceKind, Int>>,
        events: MutableList<GameEvent>,
        mover: Player,
        placedKind: PieceKind,
    ): MoveResult {
        var width = state.width
        var height = state.height
        var playable = state.playable
        var board = pieces
        var orientation = state.orientation
        val ply = state.ply + 1

        // Rotating gravity: every rotateEvery moves (both players counted) the board turns 90 degrees
        // clockwise and everything falls in the new direction, before lines are searched.
        val rotateEvery = state.rotateEvery
        if (rotateEvery != null && ply % rotateEvery == 0) {
            orientation = (orientation + 1) % 4
            val clockwise = state.engineVersion >= CLOCKWISE_ROTATION_ENGINE_VERSION
            if (clockwise) events += GameEvent.Rotated(orientation)
            val rotated = rotate(board, width, height, playable, events, clockwise)
            width = rotated.width
            height = rotated.height
            playable = rotated.playable
            board = rotated.pieces
            if (!clockwise) events += GameEvent.Rotated(orientation)
        }

        // Column locks from the previous move expire once the locked player has moved.
        var lockedCol = state.lockedCol
        var lockedFor = state.lockedFor
        if (lockedFor == mover && state.lockedCol != null) {
            lockedCol = null
            lockedFor = null
            events += GameEvent.ColumnUnlocked(state.lockedCol!!)
        }
        if (placedKind == PieceKind.LOCK) {
            val cell = board.indexOfFirst {
                it != null && it.kind == PieceKind.LOCK && it.owner == mover && it.placedAtPly == state.ply
            }
            val col = cell % width
            // A lock never closes the only column the opponent can play in.
            val freeColumns = (0 until width).count { c ->
                landingRow(board, width, height, playable, c) != null
            }
            if (freeColumns > 1) {
                lockedCol = col
                lockedFor = mover.opponent()
                events += GameEvent.ColumnLocked(col, mover.opponent())
            }
        }

        val moverLines = findLines(board, width, height, state.connect, mover)
        val otherLines = findLines(board, width, height, state.connect, mover.opponent())

        // Shape objectives: the objective holder (P1) only wins with a line that obeys the rule;
        // any other line of theirs keeps the match going. The rival's lines always count.
        val effectiveMoverLines =
            if (state.shapeRule != null && mover == Player.P1) moverLines.filter { conforms(it, state) } else moverLines
        val effectiveOtherLines =
            if (state.shapeRule != null && mover != Player.P1) otherLines.filter { conforms(it, state) } else otherLines

        for (line in effectiveMoverLines) events += GameEvent.LineFormed(line)
        for (line in effectiveOtherLines) events += GameEvent.LineFormed(line)

        var status: Status = Status.InProgress
        var outcome: MatchOutcome? = null
        when {
            effectiveMoverLines.isNotEmpty() -> {
                status = Status.Won(mover)
                outcome = MatchOutcome.Win(mover, effectiveMoverLines)
            }
            effectiveOtherLines.isNotEmpty() -> {
                status = Status.Won(mover.opponent())
                outcome = MatchOutcome.Win(mover.opponent(), effectiveOtherLines)
            }
            else -> {
                val next = mover.opponent()
                if (!anyLegalMove(board, width, height, playable, state.popOut, lockedCol, lockedFor, next)) {
                    val full = board.indices.none { board[it] == null && playable[it] }
                    val reason = if (full) "board_full" else "no_moves"
                    status = Status.Draw(reason)
                    outcome = MatchOutcome.Draw(reason)
                }
            }
        }

        val next = mover.opponent()
        val nextState = state.copy(
            width = width,
            height = height,
            playable = playable,
            pieces = board,
            inventory = inventory,
            lockedCol = lockedCol,
            lockedFor = lockedFor,
            orientation = orientation,
            toMove = next,
            ply = ply,
            status = status,
        )
        val repetition = nextState.repetitionHash()
        val history = state.history + repetition
        var finalState = nextState.copy(history = history)
        var finalOutcome = outcome
        if (finalOutcome == null && history.count { it == repetition } >= 3) {
            // Pop Out rule: the same position three times is a draw.
            finalState = finalState.copy(status = Status.Draw("repetition"))
            finalOutcome = MatchOutcome.Draw("repetition")
        }
        if (finalOutcome != null) {
            events += GameEvent.MatchOver(finalOutcome)
        } else {
            for (col in 0 until width) {
                val row = landingRow(finalState.pieces, width, height, playable, col) ?: continue
                for (player in Player.entries) {
                    val probe = finalState.pieces.toMutableList()
                    probe[finalState.index(row, col)] = Piece(player, PieceKind.NORMAL, ply)
                    if (findLines(probe, width, height, state.connect, player).isNotEmpty()) {
                        events += GameEvent.ThreatCreated(finalState.index(row, col), player)
                    }
                }
            }
            events += GameEvent.TurnChanged(next)
        }
        return MoveResult(finalState, events, finalOutcome)
    }

    // ---------------------------------------------------------------- board helpers

    /** Deepest playable empty cell of a column, or null when the column is full. */
    fun landingRow(pieces: List<Piece?>, width: Int, height: Int, playable: List<Boolean>, col: Int): Int? {
        var r = height - 1
        while (r >= 0) {
            val cell = r * width + col
            if (!playable[cell] || pieces[cell] != null) r-- else return r
        }
        return null
    }

    fun bottomPieceRow(pieces: List<Piece?>, width: Int, height: Int, col: Int): Int? {
        var r = height - 1
        while (r >= 0) {
            if (pieces[r * width + col] != null) return r
            r--
        }
        return null
    }

    private fun belongs(piece: Piece?, player: Player): Boolean =
        piece != null && (piece.owner == player || piece.kind == PieceKind.WILD)

    private fun explode(pieces: List<Piece?>, width: Int, height: Int, row: Int, col: Int): List<Int> {
        val cleared = mutableListOf<Int>()
        // The bomb clears itself even though it never rested on the board.
        cleared += row * width + col
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r !in 0 until height || c !in 0 until width) continue
                val cell = r * width + c
                val piece = pieces[cell] ?: continue
                if (piece.kind == PieceKind.LOCK) continue // locks are immune
                cleared += cell
            }
        }
        return cleared
    }

    /** Drops the given columns to the bottom, emitting [GameEvent.Shifted] per column that moved. */
    private fun settle(pieces: MutableList<Piece?>, state: GameState, events: MutableList<GameEvent>, columns: Set<Int>) {
        settleSized(pieces, state.width, state.height, state.playable, events, columns,
            respectMask = state.engineVersion >= SOLID_FLOOR_ENGINE_VERSION)
    }

    private fun settleSized(
        pieces: MutableList<Piece?>,
        width: Int,
        height: Int,
        playable: List<Boolean>,
        events: MutableList<GameEvent>,
        columns: Set<Int>,
        respectMask: Boolean,
    ) {
        for (col in columns) {
            var write = height - 1
            val fromRows = mutableListOf<Int>()
            val toRows = mutableListOf<Int>()
            var r = height - 1
            while (r >= 0) {
                val cell = r * width + col
                if (!playable[cell]) {
                    if (respectMask) write = r - 1
                    r--
                    continue
                }
                if (pieces[cell] != null) {
                    if (write != r) {
                        pieces[write * width + col] = pieces[cell]
                        pieces[cell] = null
                        fromRows += r
                        toRows += write
                    }
                    write--
                }
                r--
            }
            if (fromRows.isNotEmpty()) {
                events += GameEvent.Shifted(col, fromRows, toRows)
            }
        }
    }

    private class Rotated(val pieces: List<Piece?>, val playable: List<Boolean>, val width: Int, val height: Int)

    /** Turns the board 90 degrees clockwise (W x H becomes H x W) and drops everything. */
    private fun rotate(
        pieces: List<Piece?>,
        width: Int,
        height: Int,
        playable: List<Boolean>,
        events: MutableList<GameEvent>,
        clockwise: Boolean,
    ): Rotated {
        val newWidth = height
        val newHeight = width
        val board = arrayOfNulls<Piece>(newWidth * newHeight)
        val mask = BooleanArray(newWidth * newHeight)
        for (r in 0 until height) {
            for (c in 0 until width) {
                val nr = if (clockwise) c else width - 1 - c
                val nc = if (clockwise) height - 1 - r else r
                board[nr * newWidth + nc] = pieces[r * width + c]
                mask[nr * newWidth + nc] = playable[r * width + c]
            }
        }
        val playableList = mask.toList()
        // Fall in the new direction, reporting every move as a Fell event.
        for (col in 0 until newWidth) {
            var write = newHeight - 1
            var r = newHeight - 1
            while (r >= 0) {
                val cell = r * newWidth + col
                if (!playableList[cell]) {
                    r--
                    continue
                }
                if (board[cell] != null) {
                    if (write != r) {
                        board[write * newWidth + col] = board[cell]
                        board[cell] = null
                        events += GameEvent.Fell(cell, write * newWidth + col)
                    }
                    write--
                }
                r--
            }
        }
        return Rotated(board.toList(), playableList, newWidth, newHeight)
    }

    private fun canPopColumn(pieces: List<Piece?>, width: Int, height: Int, col: Int, player: Player): Boolean {
        val row = bottomPieceRow(pieces, width, height, col) ?: return false
        val piece = pieces[row * width + col]!!
        return piece.kind == PieceKind.NORMAL && piece.owner == player
    }

    private fun anyLegalMove(
        pieces: List<Piece?>,
        width: Int,
        height: Int,
        playable: List<Boolean>,
        popOut: Boolean,
        lockedCol: Int?,
        lockedFor: Player?,
        player: Player,
    ): Boolean {
        for (col in 0 until width) {
            if (lockedCol == col && lockedFor == player) continue
            if (landingRow(pieces, width, height, playable, col) != null) return true
            if (popOut && canPopColumn(pieces, width, height, col, player)) return true
        }
        return false
    }

    private fun reject(state: GameState, reason: String, prepend: List<GameEvent> = emptyList()): MoveResult =
        MoveResult(state, prepend + GameEvent.MoveRejected(reason), null)

    /** True when [line] obeys the shape rule of the level it belongs to. */
    internal fun conforms(line: Line, state: GameState): Boolean {
        val rule = state.shapeRule ?: return true
        val rows = line.cells.map { it / state.width }.toSet()
        val cols = line.cells.map { it % state.width }.toSet()
        return when (rule) {
            ShapeRule.HORIZONTAL_ONLY -> rows.size == 1
            ShapeRule.VERTICAL_ONLY -> cols.size == 1
            ShapeRule.DIAGONAL_ONLY -> rows.size > 1 && cols.size > 1
            ShapeRule.THROUGH_TARGET -> line.cells.any { it in state.targets }
        }
    }
}
