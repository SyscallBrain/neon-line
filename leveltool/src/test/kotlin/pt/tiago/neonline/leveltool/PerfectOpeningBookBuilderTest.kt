package pt.tiago.neonline.leveltool

import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.ai.Evaluator
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.solver.PerfectOpeningBook
import pt.tiago.neonline.core.solver.PerfectSolver
import pt.tiago.neonline.core.solver.SolverBudgetExceeded

class PerfectOpeningBookBuilderTest {
    @Test
    fun `default solver checkpoint uses a sixteen million entry table`() {
        val root = safeMidgame()
        val directory = Files.createTempDirectory("neonline-book-default-table")
        val solverCheckpoint = directory.resolve("solver.snapshot").toFile()

        assertThrows(IllegalStateException::class.java) {
            PerfectOpeningBookBuilder(
                solverNodeLimit = 512,
                solverSnapshotNodeInterval = Long.MAX_VALUE,
            ).build(root.ply, directory.resolve("book.bin").toFile(), initialState = root,
                solverCheckpoint = solverCheckpoint)
        }

        val capacity = DataInputStream(solverCheckpoint.inputStream()).use { data ->
            data.skipBytes(20)
            data.readInt()
        }
        assertEquals(1 shl 24, capacity)
    }

    @Test
    fun `node budget exhaustion writes a restorable solver checkpoint`() {
        val root = safeMidgame()
        val directory = Files.createTempDirectory("neonline-book-budget-snapshot")
        val output = directory.resolve("book.bin").toFile()
        val solverCheckpoint = directory.resolve("solver.snapshot").toFile()
        val originalOutput = byteArrayOf(4, 3, 2, 1)
        output.writeBytes(originalOutput)

        val failure = assertThrows(IllegalStateException::class.java) {
            PerfectOpeningBookBuilder(
                solverNodeLimit = 512,
                solverTranspositionCapacity = 16,
                solverSnapshotNodeInterval = Long.MAX_VALUE,
            ).build(root.ply, output, initialState = root, solverCheckpoint = solverCheckpoint)
        }
        assertTrue(failure.cause is SolverBudgetExceeded)
        assertTrue(solverCheckpoint.isFile && solverCheckpoint.length() > 32)
        assertArrayEquals(originalOutput, output.readBytes())

        PerfectOpeningBookBuilder().build(root.ply, output, initialState = root, solverCheckpoint = solverCheckpoint)
        val clean = directory.resolve("clean.bin").toFile()
        PerfectOpeningBookBuilder().build(root.ply, clean, initialState = root)
        assertArrayEquals(clean.readBytes(), output.readBytes())
    }

    @Test
    fun `failed solver snapshot replacement preserves the old snapshot and removes temp files`() {
        val directory = Files.createTempDirectory("neonline-book-snapshot-atomic")
        val output = directory.resolve("book.bin").toFile()
        val solverCheckpoint = directory.resolve("solver.snapshot").toFile()
        val root = safeMidgame()
        val originalOutput = byteArrayOf(7, 7, 7)
        output.writeBytes(originalOutput)
        val seedSolver = PerfectSolver(transpositionCapacity = 1 shl 20, reuseTranspositionTable = true)
        seedSolver.solve(root)
        solverCheckpoint.outputStream().use {
            assertTrue(seedSolver.writeTranspositionTableSnapshot(it, root.width, root.height, root.connect))
        }
        val originalSnapshot = solverCheckpoint.readBytes()
        var replacementAttempted = false

        assertThrows(IOException::class.java) {
            PerfectOpeningBookBuilder(
                solverNodeLimit = Long.MAX_VALUE,
                solverSnapshotNodeInterval = Long.MAX_VALUE,
                replaceAtomically = { source, destination ->
                    if (destination == solverCheckpoint.toPath()) {
                        replacementAttempted = true
                        throw IOException("simulated snapshot replacement failure")
                    }
                    Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                },
            ).build(root.ply, output, initialState = root, solverCheckpoint = solverCheckpoint)
        }

        assertTrue("valid seeded snapshot must reach the atomic replacement seam", replacementAttempted)
        assertArrayEquals(originalOutput, output.readBytes())
        assertArrayEquals(originalSnapshot, solverCheckpoint.readBytes())
        assertEquals(setOf("book.bin", "solver.snapshot"), directory.toFile().listFiles()?.map { it.name }?.toSet())
    }

    @Test
    fun `solver checkpoint survives interruption and resumes to the clean exact book`() {
        val root = safeMidgame()
        val directory = Files.createTempDirectory("neonline-book-solver-resume")
        val output = directory.resolve("book.bin").toFile()
        val solverCheckpoint = directory.resolve("solver.snapshot").toFile()

        assertThrows(IllegalStateException::class.java) {
            PerfectOpeningBookBuilder(
                nodeProgressInterval = 128,
                solverSnapshotNodeInterval = 128,
                onNodeProgress = { _, nodes -> if (nodes >= 512) error("simulated interruption") },
            ).build(root.ply, output, initialState = root, solverCheckpoint = solverCheckpoint)
        }
        assertTrue(solverCheckpoint.isFile && solverCheckpoint.length() > 32)
        assertTrue(!output.exists())

        PerfectOpeningBookBuilder().build(root.ply, output, initialState = root, solverCheckpoint = solverCheckpoint)
        val clean = directory.resolve("clean.bin").toFile()
        PerfectOpeningBookBuilder().build(root.ply, clean, initialState = root)

        assertArrayEquals(clean.readBytes(), output.readBytes())
    }

    @Test
    fun `writes exact book through requested ply and can read it back`() {
        val root = position(0, 4, 1, 5, 2, 6)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()

        val report = PerfectOpeningBookBuilder(
            solverNodeLimit = Long.MAX_VALUE,
            solverTranspositionCapacity = 1 shl 20,
        )
            .build(depth = 6, output = output, initialState = root)

        val book = output.inputStream().use(PerfectOpeningBook::read)
        assertEquals(6, book.maxPly)
        assertEquals(1, report.entries)
        assertEquals(1, book.size)
        assertEquals(Move.Drop(3), book.lookup(root)?.move)
    }

    @Test
    fun `accepts a legal classic root started by player two`() {
        val root = position(0, 4, 1, 5, 2, 6, firstToMove = Player.P2)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()

        val report = PerfectOpeningBookBuilder().build(depth = 6, output = output, initialState = root)

        val book = output.inputStream().use(PerfectOpeningBook::read)
        assertEquals(1, report.entries)
        assertEquals(Move.Drop(3), book.lookup(root)?.move)
    }

    @Test
    fun `failed exact solve preserves existing output`() {
        val root = position()
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()
        val original = byteArrayOf(7, 8, 9)
        output.writeBytes(original)

        assertThrows(Exception::class.java) {
            PerfectOpeningBookBuilder(solverNodeLimit = 1L)
                .build(depth = 6, output = output, initialState = root)
        }

        assertArrayEquals(original, output.readBytes())
        assertEquals(1, output.parentFile.listFiles()?.size)
    }

    @Test
    fun `checkpoints a solved position before continuing a small frontier`() {
        val root = position(0, 4, 1, 5, 2, 6)
        val directory = Files.createTempDirectory("neonline-book-resume")
        val output = directory.resolve("book.bin").toFile()
        val checkpoint = directory.resolve("checkpoint.bin").toFile()
        val originalOutput = byteArrayOf(9, 8, 7)
        output.writeBytes(originalOutput)
        val firstChild = position(0, 4, 1, 5, 2, 6, 2)
        var checkpointWrites = 0

        assertThrows(IllegalStateException::class.java) {
            PerfectOpeningBookBuilder(
                replaceAtomically = { source, destination ->
                    val snapshot = Files.newInputStream(source).use(PerfectOpeningBook::read)
                    checkpointWrites++
                    if (checkpointWrites == 2 && snapshot.size != 2) {
                        throw IllegalStateException("checkpoint was not flushed after one solved position")
                    }
                    Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                    if (checkpointWrites == 2) error("simulated interruption")
                },
            ).build(depth = 7, output = output, initialState = root, checkpoint = checkpoint)
        }
        assertArrayEquals(originalOutput, output.readBytes())
        val partial = checkpoint.inputStream().use(PerfectOpeningBook::read)
        assertEquals(2, partial.size)
        assertEquals(Move.Drop(3), partial.lookup(root)?.move)
        assertTrue(partial.lookup(firstChild) != null)

        PerfectOpeningBookBuilder().build(depth = 7, output = output, initialState = root, checkpoint = checkpoint)

        val fresh = directory.resolve("fresh.bin").toFile()
        PerfectOpeningBookBuilder().build(depth = 7, output = fresh, initialState = root)

        assertArrayEquals(fresh.readBytes(), output.readBytes())
    }

    @Test
    fun `extends a shallower exact checkpoint without resolving its positions again`() {
        val root = position(0, 4, 1, 5, 2, 6)
        val directory = Files.createTempDirectory("neonline-book-extend")
        val checkpoint = directory.resolve("depth-6.bin").toFile()
        val output = directory.resolve("depth-7.bin").toFile()
        val fresh = directory.resolve("fresh-depth-7.bin").toFile()
        val rootOnlyOutput = directory.resolve("root-only.bin").toFile()
        val rootProgress = mutableListOf<Long>()

        PerfectOpeningBookBuilder().build(depth = 6, output = rootOnlyOutput, initialState = root, checkpoint = checkpoint)
        PerfectOpeningBookBuilder(
            nodeProgressInterval = 1L,
            onNodeProgress = { ply, nodes -> if (ply == root.ply) rootProgress += nodes },
        ).build(depth = 7, output = output, initialState = root, checkpoint = checkpoint)
        PerfectOpeningBookBuilder().build(depth = 7, output = fresh, initialState = root)

        assertTrue(rootProgress.isEmpty())
        assertArrayEquals(fresh.readBytes(), output.readBytes())
    }

    @Test
    fun `failed atomic replacement preserves existing output and removes temporary file`() {
        val root = position(0, 4, 1, 5, 2, 6)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()
        val original = byteArrayOf(4, 2, 1)
        output.writeBytes(original)

        assertThrows(IOException::class.java) {
            PerfectOpeningBookBuilder(
                replaceAtomically = { _, _ -> throw IOException("simulated atomic replacement failure") },
            ).build(depth = 6, output = output, initialState = root)
        }

        assertArrayEquals(original, output.readBytes())
        assertEquals(listOf(output.name), output.parentFile.list()?.toList())
    }

    @Test
    fun `book size limit preserves existing output`() {
        val root = position(3, 3, 0, 3, 6, 3)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()
        val original = byteArrayOf(6, 5, 4)
        output.writeBytes(original)

        val failure = assertThrows(IllegalStateException::class.java) {
            PerfectOpeningBookBuilder(entryLimit = 1).build(depth = 7, output = output, initialState = root)
        }

        assertTrue(failure.message.orEmpty().contains("6 MiB"))
        assertArrayEquals(original, output.readBytes())
        assertEquals(listOf(output.name), output.parentFile.list()?.toList())
    }

    @Test
    fun `forwards solver node progress with the current ply`() {
        val progress = mutableListOf<Pair<Int, Long>>()
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()

        PerfectOpeningBookBuilder(
            nodeProgressInterval = 1L,
            onNodeProgress = { ply, nodes -> progress += ply to nodes },
        ).build(depth = 6, output = output, initialState = position(0, 4, 1, 5, 2, 6))

        assertEquals(listOf(6 to 1L), progress)
    }

    @Test
    fun `writes atomically when the output file name has one character`() {
        val output = Files.createTempDirectory("neonline-book-test").resolve("a").toFile()

        PerfectOpeningBookBuilder().build(6, output, position(0, 4, 1, 5, 2, 6))

        assertTrue(output.isFile)
        output.inputStream().use(PerfectOpeningBook::read)
    }

    @Test
    fun `mirror roots produce byte-identical books`() {
        val first = Files.createTempDirectory("neonline-book-test").resolve("first.bin").toFile()
        val mirrored = Files.createTempDirectory("neonline-book-test").resolve("mirrored.bin").toFile()

        PerfectOpeningBookBuilder().build(6, first, position(0, 4, 1, 5, 2, 6))
        PerfectOpeningBookBuilder().build(6, mirrored, position(6, 2, 5, 1, 4, 0))

        assertArrayEquals(first.readBytes(), mirrored.readBytes())
    }

    @Test
    fun `traverses only legal nonterminal children and stores mirror pairs once`() {
        val root = position(3, 3, 0, 3, 6, 3)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()

        PerfectOpeningBookBuilder().build(depth = 7, output = output, initialState = root)

        val book = output.inputStream().use(PerfectOpeningBook::read)
        assertEquals(5, book.size) // Root plus four mirror-canonical child positions.
        assertEquals(Move.Drop(3), book.lookup(root)?.move)
        val legalChildren = (0 until 7).map { column ->
            GameEngine.apply(root, Move.Drop(column, PieceKind.NORMAL)).state
        }.filter { it.status == pt.tiago.neonline.core.model.Status.InProgress }
        assertEquals(7, legalChildren.size)
        val replies = legalChildren.map { child ->
            val answer = book.lookup(child)?.move
            assertTrue("every reachable child must have an exact book answer", answer != null)
            val reply = checkNotNull(answer)
            assertTrue("book answer must be legal in the child", GameEngine.validMoves(child).contains(reply))
            reply.col
        }
        for (column in 0..2) assertEquals(6 - replies[column], replies[6 - column])
    }

    @Test
    fun `does not add terminal or full-column children to the book`() {
        val root = position(0, 6, 1, 5, 2, 6, 6, 4, 6, 6, 6, 5)
        val output = Files.createTempDirectory("neonline-book-test").resolve("book.bin").toFile()

        PerfectOpeningBookBuilder().build(depth = 13, output = output, initialState = root)

        val book = output.inputStream().use(PerfectOpeningBook::read)
        val legalMoves = GameEngine.validMoves(root).filterIsInstance<Move.Drop>()
        assertTrue(legalMoves.none { it.col == 6 })
        val children = legalMoves.map { move ->
            move.col to GameEngine.apply(root, move).state
        }
        val winningChild = checkNotNull(children.single { it.first == 3 }.second)
        assertEquals(Status.Won(Player.P1), winningChild.status)
        assertEquals(null, book.lookup(winningChild))

        val continuingChildren = children.filter { it.second.status == Status.InProgress }
        assertEquals(5, continuingChildren.size)
        for ((_, child) in continuingChildren) {
            val answer = checkNotNull(book.lookup(child))
            assertTrue(GameEngine.validMoves(child).contains(answer.move))
        }
    }

    private fun position(vararg columns: Int, firstToMove: Player = Player.P1): GameState {
        var state = GameState.initial(
            levelId = "opening-book-test",
            difficulty = Difficulty.HARD,
            engineVersion = GameEngine.ENGINE_VERSION,
            seedHex = "0000000000000001",
            width = 7,
            height = 6,
            connect = 4,
            playable = List(42) { true },
            pieces = List(42) { null },
            firstToMove = firstToMove,
        )
        columns.forEach { column ->
            state = GameEngine.apply(state, Move.Drop(column, PieceKind.NORMAL)).state
        }
        return state
    }

    private fun safeMidgame(): GameState {
        val rng = SplitMix64(5L)
        var state = position()
        repeat(26) {
            if (state.status == Status.InProgress) {
                val safe = GameEngine.validMoves(state).filter { move ->
                    val next = GameEngine.apply(state, move).state
                    next.status == Status.InProgress && !Evaluator.hasThreat(next, state.toMove.opponent())
                }
                val choices = safe.ifEmpty { GameEngine.validMoves(state) }
                state = GameEngine.apply(state, choices[rng.nextInt(choices.size)]).state
            }
        }
        return state
    }
}
