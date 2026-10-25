package pt.tiago.neonline.leveltool

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.core.solver.SolverBudgetExceeded

/** Exhaustive, exact offline generator for the NLBK v1 classic opening book. */
internal class PerfectOpeningBookBuilder(
    private val solverNodeLimit: Long = Long.MAX_VALUE,
    private val solverTranspositionCapacity: Int = 1 shl 24,
    private val onProgress: (ply: Int, solved: Int) -> Unit = { _, _ -> },
    private val onNodeProgress: (ply: Int, nodes: Long) -> Unit = { _, _ -> },
    private val replaceAtomically: (source: Path, destination: Path) -> Unit = { source, destination ->
        Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    },
    private val entryLimit: Int = (PerfectOpeningBook.MAX_FILE_BYTES - 17) / 18,
    private val nodeProgressInterval: Long = 1L shl 24,
    private val checkpointBatchSize: Int = 1024,
    private val solverSnapshotNodeInterval: Long = 1L shl 28,
) {
    init {
        require(entryLimit in 1..((PerfectOpeningBook.MAX_FILE_BYTES - 17) / 18)) {
            "entry limit must fit the NLBK v1 file size"
        }
        require(nodeProgressInterval > 0) { "node progress interval must be positive" }
        require(checkpointBatchSize > 0) { "checkpoint batch size must be positive" }
        require(solverSnapshotNodeInterval > 0) { "solver snapshot interval must be positive" }
    }

    data class Report(val maxPly: Int, val entries: Int)

    private data class Position(val state: GameState, val key: String)

    fun build(
        depth: Int,
        output: File,
        initialState: GameState = emptyClassicState(),
        checkpoint: File? = null,
        solverCheckpoint: File? = null,
    ): Report {
        require(depth in 0..42) { "depth must be in 0..42" }
        validateRoot(initialState)
        val rootPly = initialState.pieces.count { it != null }
        require(depth >= rootPly) { "depth must be at least the initial position ply ($rootPly)" }
        val checkpointPath = checkpoint?.toPath()?.toAbsolutePath()?.normalize()
        val outputPath = output.toPath().toAbsolutePath().normalize()
        val solverCheckpointPath = solverCheckpoint?.toPath()?.toAbsolutePath()?.normalize()
        val paths = listOfNotNull(outputPath, checkpointPath, solverCheckpointPath)
        require(paths.distinct().size == paths.size) { "book output and checkpoint paths must differ" }
        val existingCheckpoint = checkpoint?.takeIf(File::isFile)?.inputStream()?.use(PerfectOpeningBook::read)
        require(existingCheckpoint == null || existingCheckpoint.maxPly <= depth) {
            "checkpoint depth exceeds requested depth $depth"
        }

        val solver = PerfectSolver(
            nodeLimit = solverNodeLimit,
            transpositionCapacity = solverTranspositionCapacity,
            nodeProgressInterval = nodeProgressInterval,
            reuseTranspositionTable = true,
        )
        solverCheckpoint?.takeIf(File::isFile)?.inputStream()?.use {
            solver.restoreTranspositionTableSnapshot(it, initialState.width, initialState.height, initialState.connect)
        }
        val seeds = ArrayList<PerfectOpeningBook.Seed>()
        var frontier = listOf(position(initialState))
        val seen = hashSetOf(frontier.single().key)

        while (frontier.isNotEmpty()) {
            val ply = frontier.first().state.pieces.count { it != null }
            val checkpointFlushSize = if (frontier.size <= checkpointBatchSize) 1 else checkpointBatchSize
            var unsavedSinceCheckpoint = 0
            for ((state, _) in frontier) {
                val cached = existingCheckpoint?.lookup(state)
                if (cached != null) {
                    seeds += PerfectOpeningBook.Seed(state, cached.move.col, cached.score)
                } else {
                    var lastSolverSnapshotNode = 0L
                    val result = try {
                        solver.solveOneBestMoveWithProgress(state, onProgress = { nodes ->
                            if (solverCheckpoint != null && nodes - lastSolverSnapshotNode >= solverSnapshotNodeInterval) {
                                if (writeSolverSnapshotAtomically(solver, state, solverCheckpoint)) {
                                    lastSolverSnapshotNode = nodes
                                }
                            }
                            onNodeProgress(state.ply, nodes)
                        })
                    } catch (e: SolverBudgetExceeded) {
                        if (solverCheckpoint != null) writeSolverSnapshotAtomically(solver, state, solverCheckpoint)
                        throw IllegalStateException("exact solve budget exceeded at ply ${state.ply}; no book was written", e)
                    } ?: throw IllegalStateException("solver cannot prove the position at ply ${state.ply}; no book was written")
                    if (solverCheckpoint != null) writeSolverSnapshotAtomically(solver, state, solverCheckpoint)
                    val move = result.bestMoves.firstOrNull() as? Move.Drop
                        ?: throw IllegalStateException("solver returned no drop move at ply ${state.ply}; no book was written")
                    seeds += PerfectOpeningBook.Seed(state, move.col, result.score)
                    unsavedSinceCheckpoint++
                }
                check(seeds.size <= entryLimit) { "opening book would exceed 6 MiB; no book was written" }
                if (checkpoint != null && unsavedSinceCheckpoint >= checkpointFlushSize) {
                    writeAtomically(PerfectOpeningBook.create(depth, seeds), checkpoint)
                    unsavedSinceCheckpoint = 0
                }
            }
            if (checkpoint != null && unsavedSinceCheckpoint > 0) {
                writeAtomically(PerfectOpeningBook.create(depth, seeds), checkpoint)
            }
            onProgress(ply, seeds.size)
            if (ply >= depth) break

            val next = ArrayList<Position>()
            for ((state, _) in frontier) {
                for (move in GameEngine.validMoves(state).filterIsInstance<Move.Drop>().distinctBy { it.col }
                    .sortedBy { kotlin.math.abs(it.col - 3) }) {
                    val applied = GameEngine.apply(state, move).state
                    if (applied.status != Status.InProgress) continue
                    // Classic play has no repetition rule; keep just the current hash instead of
                    // retaining an entire path in every node of a broad breadth-first frontier.
                    val child = applied.copy(history = listOf(applied.repetitionHash()))
                    val canonical = position(child)
                    if (seen.add(canonical.key)) {
                        next += canonical
                        check(seeds.size + next.size <= entryLimit) {
                            "opening book would exceed 6 MiB; no book was written"
                        }
                    }
                }
            }
            frontier = next
        }

        val book = PerfectOpeningBook.create(depth, seeds)
        writeAtomically(book, output)
        return Report(depth, book.size)
    }

    private fun validateRoot(state: GameState) {
        require(state.width == 7 && state.height == 6 && state.connect == 4) { "build-book requires classic 7x6 connect-4" }
        require(state.status == Status.InProgress && !state.popOut && state.rotateEvery == null &&
            state.playable.all { it } && state.shapeRule == null && state.targets.isEmpty() &&
            state.fadeAfter == null && state.moveClockSec == null && state.lockedCol == null && state.lockedFor == null &&
            state.pieces.filterNotNull().all { it.kind == PieceKind.NORMAL } &&
            state.inventory.values.all { kinds -> kinds.all { (kind, count) -> kind == PieceKind.NORMAL || count == 0 } }
        ) { "build-book requires a nonterminal classic position" }
        require(state.ply == state.pieces.count { it != null }) { "initial position ply does not match its pieces" }
        val currentCount = state.pieces.filterNotNull().count { it.owner == state.toMove }
        require(currentCount == state.ply / 2) { "initial position side-to-move piece count is invalid" }
        for (col in 0 until 7) {
            var gap = false
            for (row in 5 downTo 0) {
                if (state.pieceAt(row, col) == null) gap = true
                else require(!gap) { "initial position has a gravity gap" }
            }
        }
        require(GameEngine.findLines(state.pieces, 7, 6, 4, pt.tiago.neonline.core.model.Player.P1).isEmpty() &&
            GameEngine.findLines(state.pieces, 7, 6, 4, pt.tiago.neonline.core.model.Player.P2).isEmpty()) {
            "initial position is already won"
        }
    }

    /** Board and turn together, canonicalized over a horizontal reflection. */
    private fun position(state: GameState): Position {
        fun signature(mirror: Boolean): String = buildString(43) {
            append(state.toMove.ordinal)
            for (row in 0 until 6) for (column in 0 until 7) {
                val source = if (mirror) 6 - column else column
                append(when (state.pieceAt(row, source)?.owner) {
                    null -> '.'
                    pt.tiago.neonline.core.model.Player.P1 -> '1'
                    pt.tiago.neonline.core.model.Player.P2 -> '2'
                })
            }
        }
        val direct = signature(mirror = false)
        val reflected = signature(mirror = true)
        return Position(state, minOf(direct, reflected))
    }

    private fun writeAtomically(book: PerfectOpeningBook, output: File) {
        val absolute = output.absoluteFile
        val parent = absolute.parentFile ?: error("output must have a parent directory")
        require(parent.isDirectory || parent.mkdirs()) { "cannot create output directory ${parent.path}" }
        val temporaryPrefix = "${absolute.name.take(32).padEnd(2, '_')}."
        val temporary = File.createTempFile(temporaryPrefix, ".tmp", parent)
        try {
            temporary.outputStream().buffered().use(book::writeTo)
            replaceAtomically(temporary.toPath(), absolute.toPath())
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun writeSolverSnapshotAtomically(solver: PerfectSolver, state: GameState, output: File): Boolean {
        val absolute = output.absoluteFile
        val parent = absolute.parentFile ?: error("solver checkpoint must have a parent directory")
        require(parent.isDirectory || parent.mkdirs()) { "cannot create solver checkpoint directory ${parent.path}" }
        val temporaryPrefix = "${absolute.name.take(32).padEnd(2, '_')}."
        val temporary = File.createTempFile(temporaryPrefix, ".tmp", parent)
        try {
            val written = temporary.outputStream().buffered().use {
                solver.writeTranspositionTableSnapshot(it, state.width, state.height, state.connect)
            }
            if (written) replaceAtomically(temporary.toPath(), absolute.toPath())
            return written
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    companion object {
        private fun emptyClassicState() = GameState.initial(
            levelId = "perfect-opening-book",
            difficulty = Difficulty.HARD,
            engineVersion = GameEngine.ENGINE_VERSION,
            seedHex = "0000000000000001",
            width = 7,
            height = 6,
            connect = 4,
            playable = List(42) { true },
            pieces = List(42) { null },
        )
    }
}
