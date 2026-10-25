package pt.tiago.neonline.game

import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure presentation of engine events. No rules are resolved here. */
object AnimationTimeline {
    const val FIRST_CELL_MS = 70L
    const val BOUNCE_MS = 90L
    const val BOUNCE_FRACTION = 0.14f
    const val POP_MS = 220L
    const val ROTATE_MS = 450L
    const val BOMB_MS = 320L
    const val LOCK_MS = 200L
    const val BUZZ_MS = 120L
    const val FADE_MS = 600L
    const val DIM_MS = 500L
    const val AI_MIN_THINK_MS = 450L

    data class Board(val width: Int, val height: Int, val playable: List<Boolean>, val pieces: List<Piece?>, val targets: Set<Int>)
    data class Motion(val piece: Piece, val source: Int?, val col: Float, val fromRow: Float, val toRow: Float)
    enum class Effect { FALL, POP, ROTATE, BOMB, LOCK, LINE, DIM, FADE }
    data class Phase(
        val startMs: Long, val durationMs: Long, val board: Board,
        val effect: Effect, val motions: List<Motion> = emptyList(),
        val cells: Set<Int> = emptySet(), val center: Int? = null,
        val litCells: Set<Int> = emptySet(),
    )
    data class Timeline(val phases: List<Phase>, val totalMs: Long, val before: GameState, val after: GameState, val finalBoard: Board, val winningCells: Set<Int>)
    data class PiecePose(val piece: Piece, val col: Float, val row: Float, val alpha: Float, val lit: Boolean)
    data class Frame(
        val board: Board, val pieces: List<PiecePose>, val rotationDegrees: Float,
        val litCells: Set<Int>, val explosionCell: Int?, val effectProgress: Float,
        val lockedColumn: Int?, val phaseIndex: Int, val finished: Boolean,
    )

    fun fallMs(cells: Int): Long = (FIRST_CELL_MS * sqrt(cells.coerceAtLeast(1).toDouble())).toLong()

    fun build(before: GameState, after: GameState, events: List<GameEvent>): Timeline {
        var board = before.board()
        var cursor = 0L
        val phases = mutableListOf<Phase>()
        fun phase(duration: Long, effect: Effect, motions: List<Motion> = emptyList(), cells: Set<Int> = emptySet(), center: Int? = null, lit: Set<Int> = emptySet()) {
            phases += Phase(cursor, duration, board, effect, motions, cells, center, lit)
            cursor += duration
        }
        fun movePieces(motions: List<Motion>) {
            val pieces = board.pieces.toMutableList()
            motions.forEach { it.source?.let { source -> pieces[source] = null } }
            motions.forEach { motion ->
                val row = motion.toRow.toInt()
                if (row in 0 until board.height) pieces[row * board.width + motion.col.toInt()] = motion.piece
            }
            board = board.copy(pieces = pieces)
        }
        val fell = events.filterIsInstance<GameEvent.Fell>()
        val clockwise = before.engineVersion >= GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION
        val pendingFalls = mutableListOf<GameEvent.Fell>()
        fun settleFalls(falls: List<GameEvent.Fell>) {
            val motions = falls.mapNotNull { event ->
                board.pieces[event.from]?.let { piece -> Motion(piece, event.from, (event.to % board.width).toFloat(),
                    (event.from / board.width).toFloat(), (event.to / board.width).toFloat()) }
            }
            if (motions.isNotEmpty()) {
                phase(motions.maxOf { fallMs((it.toRow - it.fromRow).toInt()) } + BOUNCE_MS, Effect.FALL, motions)
                movePieces(motions)
            }
        }
        var rotated = false
        fun rotateAndFall() {
            if (rotated) return
            rotated = true
            phase(ROTATE_MS, Effect.ROTATE)
            val pieces = MutableList<Piece?>(board.pieces.size) { null }
            val mask = MutableList(board.playable.size) { false }
            fun rotatedIndex(index: Int): Int = if (clockwise) (index % board.width) * board.height + board.height - 1 - index / board.width
                else (board.width - 1 - index % board.width) * board.height + index / board.width
            board.pieces.indices.forEach { index ->
                val target = rotatedIndex(index)
                pieces[target] = board.pieces[index]
                mask[target] = board.playable[index]
            }
            board = Board(board.height, board.width, mask, pieces, after.targets)
            if (!clockwise) settleFalls(fell)
        }
        events.forEach { event ->
            // Engine4 marks the rotation before its falls. Finish the previous geometry first.
            if (clockwise && event !is GameEvent.Fell && pendingFalls.isNotEmpty()) {
                settleFalls(pendingFalls)
                pendingFalls.clear()
            }
            when (event) {
                is GameEvent.Dropped -> {
                    val motion = Motion(event.piece, null, event.col.toFloat(), event.fromRow.toFloat(), event.row.toFloat())
                    phase(fallMs(event.row - event.fromRow) + BOUNCE_MS, Effect.FALL, listOf(motion))
                    movePieces(listOf(motion))
                }
                is GameEvent.Popped -> {
                    val source = board.pieces.indices.lastOrNull { it % board.width == event.col && board.pieces[it] != null }
                    if (source != null) {
                        val motion = Motion(event.piece, source, event.col.toFloat(), (source / board.width).toFloat(), board.height.toFloat())
                        phase(POP_MS, Effect.POP, listOf(motion))
                        movePieces(listOf(motion))
                    }
                }
                is GameEvent.Shifted -> {
                    val motions = event.fromRows.mapIndexedNotNull { index, row ->
                        val source = row * board.width + event.col
                        board.pieces[source]?.let { Motion(it, source, event.col.toFloat(), row.toFloat(), event.toRows[index].toFloat()) }
                    }
                    if (motions.isNotEmpty()) {
                        phase(motions.maxOf { fallMs((it.toRow - it.fromRow).toInt()) } + BOUNCE_MS, Effect.FALL, motions)
                        movePieces(motions)
                    }
                }
                is GameEvent.BombExploded -> {
                    val cleared = event.cleared.toSet() + event.center
                    phase(BOMB_MS, Effect.BOMB, cells = cleared, center = event.center)
                    board = board.copy(pieces = board.pieces.mapIndexed { index, piece -> if (index in cleared) null else piece })
                }
                is GameEvent.Fell -> if (clockwise) pendingFalls += event
                    else if (events.any { it is GameEvent.Rotated }) rotateAndFall()
                is GameEvent.Rotated -> rotateAndFall()
                is GameEvent.ColumnLocked -> phase(LOCK_MS, Effect.LOCK, center = event.col)
                else -> Unit
            }
        }
        if (clockwise) settleFalls(pendingFalls)
        // Use the authoritative final board at the boundary, including all special-rule effects.
        board = after.board()
        val fade = after.fadeAfter
        if (fade != null && after.pieces.filterNotNull().any { before.ply - it.placedAtPly < fade && after.ply - it.placedAtPly >= fade }) {
            phase(FADE_MS, Effect.FADE)
        }
        val winning = events.filterIsInstance<GameEvent.LineFormed>().flatMap { it.line.cells }.toSet()
        val lit = mutableSetOf<Int>()
        winning.forEach { cell ->
            lit += cell
            phase(BUZZ_MS, Effect.LINE, lit = lit.toSet())
        }
        if (winning.isNotEmpty()) phase(DIM_MS, Effect.DIM, lit = winning)
        return Timeline(phases, cursor, before, after, board, winning)
    }

    fun frameAt(timeline: Timeline, elapsedMs: Long): Frame {
        val phaseIndex = timeline.phases.indexOfFirst { elapsedMs < it.startMs + it.durationMs }
        val phase = timeline.phases.getOrNull(phaseIndex)
        val finished = phase == null
        val board = phase?.board ?: timeline.finalBoard
        val local = if (phase == null) 0L else (elapsedMs - phase.startMs).coerceAtLeast(0)
        val progress = if (phase == null) 1f else (local.toFloat() / phase.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val lit = phase?.litCells ?: timeline.winningCells
        val dim = when { finished && lit.isNotEmpty() -> 0.35f; phase?.effect == Effect.DIM -> 1f - 0.65f * progress; else -> 1f }
        val fadeProgress = when { finished || phase?.effect in listOf(Effect.LINE, Effect.DIM) -> 1f; phase?.effect == Effect.FADE -> progress; else -> 0f }
        fun alpha(piece: Piece, cell: Int?): Float {
            val fade = timeline.after.fadeAfter
            var alpha = if (cell in lit || lit.isEmpty()) 1f else dim
            if (fade != null) {
                if (timeline.before.ply - piece.placedAtPly >= fade) alpha = 0f
                else if (timeline.after.ply - piece.placedAtPly >= fade) alpha *= 1f - fadeProgress
            }
            if (phase?.effect == Effect.BOMB && cell in phase.cells) alpha *= 1f - progress
            return alpha
        }
        val movingSources = phase?.motions.orEmpty().mapNotNull { it.source }.toSet()
        val poses = board.pieces.mapIndexedNotNull { index, piece ->
            if (piece == null || index in movingSources) null else PiecePose(piece, (index % board.width).toFloat(), (index / board.width).toFloat(), alpha(piece, index), index in lit)
        }.toMutableList()
        phase?.motions?.forEach { motion ->
            val row = if (phase.effect == Effect.POP) {
                motion.fromRow + (motion.toRow - motion.fromRow) * progress
            } else {
                val fallDuration = fallMs((motion.toRow - motion.fromRow).toInt())
                if (local < fallDuration) {
                    val fraction = local.toFloat() / fallDuration
                    motion.fromRow + (motion.toRow - motion.fromRow) * fraction * fraction
                } else {
                    val bounce = ((local - fallDuration).toFloat() / BOUNCE_MS).coerceIn(0f, 1f)
                    motion.toRow - BOUNCE_FRACTION * sin(PI * bounce).toFloat()
                }
            }
            poses += PiecePose(motion.piece, motion.col, row, alpha(motion.piece, null), false)
        }
        val rotationSign = if (timeline.before.engineVersion >= GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION) 1f else -1f
        return Frame(board, poses, if (phase?.effect == Effect.ROTATE) rotationSign * 90f * progress else 0f, lit,
            phase?.center?.takeIf { phase.effect == Effect.BOMB }, progress,
            phase?.center?.takeIf { phase.effect == Effect.LOCK }, phaseIndex, finished)
    }

    fun stillFrame(state: GameState, events: List<GameEvent> = emptyList()): Frame {
        val winning = events.filterIsInstance<GameEvent.LineFormed>().flatMap { it.line.cells }.toSet()
        return frameAt(Timeline(emptyList(), 0, state, state, state.board(), winning), 0)
    }

    private fun GameState.board() = Board(width, height, playable, pieces, targets)
}

data class BoardAnimation(val id: Long, val timeline: AnimationTimeline.Timeline)
