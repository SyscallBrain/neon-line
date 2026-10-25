package pt.tiago.neonline.game

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.SavedMatch
import java.util.concurrent.CancellationException

class TacticalReviewTest {
    private fun position(vararg columns: Int, width: Int = 7, height: Int = 6, connect: Int = 4): GameState {
        var state = GameState.initial("review", Difficulty.EASY, GameEngine.ENGINE_VERSION, "0123456789abcdef",
            width, height, connect, List(width * height) { true }, List(width * height) { null })
        for (column in columns) state = GameEngine.apply(state, Move.Drop(column)).state
        return state
    }
    private fun missed() = position(0, 6, 1, 6, 2, 5)
    private fun threatened() = position(6, 0, 6, 0, 5, 0)
    private fun input(start: GameState, move: Move, puzzleLimit: Int? = null): TacticalReviewInput {
        val after = GameEngine.apply(start, move).state
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(after),
            steps = listOf(MatchReplay.step(start, move, after)),
            clockMs = start.moveClockSec?.times(1000L), elapsedMs = 1234L), 0, 0, puzzleLimit)
    }
    private fun review(input: TacticalReviewInput) = TacticalReviewAnalyzer.analyze(input)

    @Test fun `missed immediate win returns actual before and both engine after boards`() {
        val request = input(missed(), Move.Drop(4))
        val result = review(request)
        assertEquals(TacticalReviewStatus.FOUND, result.status)
        val found = result.finding!!
        assertEquals(TacticalReason.MISSED_WIN, found.reason)
        assertEquals(1, found.humanMove)
        assertEquals(Move.Drop(4), found.played)
        assertEquals(Move.Drop(3), found.alternative)
        assertEquals(request.start, found.before)
        assertEquals(GameEngine.apply(found.before, found.played).state, found.playedAfter)
        assertEquals(Status.Won(Player.P1), found.alternativeAfter.status)
        assertEquals(1234L, request.journal.elapsedMs)
    }

    @Test fun `unblocked immediate rival win recommends a legal block without promising eventual victory`() {
        val found = review(input(threatened(), Move.Drop(4))).finding!!
        assertEquals(TacticalReason.ALLOWED_WIN, found.reason)
        assertEquals(0, found.rivalWinningColumn)
        assertEquals(Move.Drop(0), found.alternative)
        assertTrue(GameEngine.validMoves(found.alternativeAfter).none {
            GameEngine.apply(found.alternativeAfter, it).state.status == Status.Won(Player.P2)
        })
    }

    @Test fun `safe play and unavoidable two ended threat have no invented correction`() {
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, review(input(threatened(), Move.Drop(0))).status)
        val unavoidable = position(6, 1, 6, 2, 5, 3)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, review(input(unavoidable, Move.Drop(0))).status)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, review(input(missed(), Move.Drop(3))).status)
    }

    @Test fun `timeout uses actual auxiliary stream column without changing the journal`() {
        val start = threatened().copy(moveClockSec = 10)
        val request = input(start, Move.TimeUp)
        val actual = GameEngine.apply(start, Move.TimeUp)
        val column = actual.events.filterIsInstance<GameEvent.TimeUpPlayed>().single().col
        assertNotEquals(0, column)
        val found = review(request).finding!!
        assertEquals(Move.TimeUp, found.played)
        assertEquals(column, found.playedColumn)
        assertEquals(request.journal.stateHash, StateHasher.hash(found.playedAfter))
        assertEquals(start.auxStateHex, request.start.auxStateHex)
    }

    @Test fun `pops and special pieces are retained as complete actions`() {
        val pop = review(input(missed().copy(popOut = true), Move.Pop(0))).finding!!
        assertEquals(Move.Pop(0), pop.played)
        assertEquals(Move.Drop(3), pop.alternative)
        val specialStart = missed().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1)))
        val bomb = review(input(specialStart, Move.Drop(6, PieceKind.BOMB))).finding!!
        assertEquals(Move.Drop(6, PieceKind.BOMB), bomb.played)
        assertEquals(GameEngine.apply(specialStart, bomb.played).state, bomb.playedAfter)
    }

    @Test fun `a pop that directly completes the rival line is distinguished from a winning reply`() {
        val pieces = MutableList<Piece?>(42) { null }
        listOf(listOf(Player.P1, Player.P2), listOf(Player.P2, Player.P2),
            listOf(Player.P1, Player.P2), listOf(Player.P1, Player.P1, Player.P2)).forEachIndexed { column, stack ->
            stack.forEachIndexed { offset, owner -> pieces[(5 - offset) * 7 + column] = Piece(owner, PieceKind.NORMAL, 0) }
        }
        val start = position().copy(pieces = pieces, popOut = true)
        val request = input(start, Move.Pop(3))
        assertEquals(Status.Won(Player.P2), GameEngine.apply(start, Move.Pop(3)).state.status)
        val found = review(request).finding!!
        assertEquals(TacticalReason.GAVE_WIN, found.reason)
        assertNull(found.rivalWinningColumn)
        assertNotEquals(Status.Won(Player.P2), found.alternativeAfter.status)
    }

    @Test fun `connect five and larger boards use their real engine objective`() {
        val start = position(0, 8, 1, 8, 2, 7, 3, 7, width = 9, height = 7, connect = 5)
        val found = review(input(start, Move.Drop(6))).finding!!
        assertEquals(Move.Drop(4), found.alternative)
        assertEquals(9, found.before.width)
        assertEquals(Status.Won(Player.P1), found.alternativeAfter.status)
    }

    @Test fun `rotation outcomes are compared after settling rather than before rotation`() {
        val start = missed().copy(rotateEvery = 1)
        val request = input(start, Move.Drop(6))
        assertEquals(Status.InProgress, GameEngine.apply(start, Move.Drop(6)).state.status)
        val found = review(request).finding!!
        assertEquals(Move.Drop(3), found.alternative)
        assertEquals(GameEngine.apply(start, found.played).state, found.playedAfter)
        assertEquals(GameEngine.apply(start, found.alternative).state, found.alternativeAfter)
        assertEquals(6, found.alternativeAfter.width)
        assertEquals(7, found.alternativeAfter.height)
        assertEquals(Status.Won(Player.P1), found.alternativeAfter.status)
    }

    @Test fun `invalid journal and fading boards are unavailable rather than evaluated or exposed`() {
        val request = input(missed(), Move.Drop(4))
        val invalid = review(request.copy(journal = request.journal.copy(stateHash = "tampered")))
        assertEquals(TacticalReviewStatus.INVALID_JOURNAL, invalid.status)
        assertNull(invalid.finding)
        val faded = review(input(missed().copy(fadeAfter = 2), Move.Drop(4)))
        assertEquals(TacticalReviewStatus.FADING_BOARD, faded.status)
        assertNull(faded.finding)
    }

    @Test fun `budget exhaustion is explicit and cooperative cancellation propagates`() {
        val request = input(missed(), Move.Drop(4))
        assertEquals(TacticalReviewStatus.LIMIT_REACHED, TacticalReviewAnalyzer.analyze(request, maxProbes = 1).status)
        var cancelled = false
        try { TacticalReviewAnalyzer.analyze(request, checkCancelled = { throw CancellationException() }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `first tactical mistake is located in the full replay rather than the final board`() {
        val start = position()
        var state = start
        val steps = listOf(0, 6, 1, 6, 2, 5, 4).map { column ->
            val move = Move.Drop(column)
            val after = GameEngine.apply(state, move).state
            MatchReplay.step(state, move, after).also { state = after }
        }
        val journal = SavedMatch(start.levelId, start.difficulty, initialHash = StateHasher.hash(start),
            stateHash = StateHasher.hash(state), steps = steps)
        val found = review(TacticalReviewInput(start, journal, 0, 0)).finding!!
        assertEquals(4, found.humanMove)
        assertEquals(missed(), found.before)
        assertEquals(Move.Drop(3), found.alternative)
    }

    @Test fun `replay validation checks cancellation inside its step loop and propagates it`() {
        val request = input(missed(), Move.Drop(4))
        var checks = 0
        var cancelled = false
        try {
            MatchReplay.restore(request.start, request.journal, 0, 0, allowTerminal = true,
                checkCancelled = { if (++checks == 2) throw CancellationException() })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(2, checks)
    }

    @Test fun `puzzle final turn recommends only a winning alternative`() {
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, review(input(threatened(), Move.Drop(4), puzzleLimit = 1)).status)
        assertEquals(TacticalReason.MISSED_WIN, review(input(missed(), Move.Drop(4), puzzleLimit = 1)).finding!!.reason)
    }
}
