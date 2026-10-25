package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class TacticalForkReviewTest {
    private fun position(starter: Player = Player.P1): GameState {
        var state = LocalConfig().initial(starter)
        for (column in listOf(6, 1, 6, 2)) state = GameEngine.apply(state, Move.Drop(column)).state
        return state
    }
    private fun input(start: GameState = position(), move: Move = Move.Drop(5)): TacticalReviewInput {
        val after = GameEngine.apply(start, move).state
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(after),
            steps = listOf(MatchReplay.step(start, move, after))), 0, 0)
    }

    @Test fun `avoidable two ended fork has exact actual boards and a genuinely safe alternative`() {
        val request = input()
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalReviewAnalyzer.analyze(request).status)
        val result = TacticalForkReviewAnalyzer.analyze(request)
        val found = result.finding!!
        assertEquals(TacticalReviewStatus.FOUND, result.status)
        assertEquals(TacticalReason.ALLOWED_DOUBLE_THREAT, found.reason)
        assertEquals(1, found.humanMove)
        assertEquals(request.start, found.before)
        assertEquals(Move.Drop(5), found.played)
        assertEquals(3, found.rivalWinningColumn)
        assertEquals(GameEngine.apply(found.before, found.played).state, found.playedAfter)
        assertEquals(GameEngine.apply(found.before, found.alternative).state, found.alternativeAfter)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE,
            TacticalForkReviewAnalyzer.analyze(input(request.start, found.alternative)).status)
        assertEquals(found.playedAfter, TacticalReviewReplay.build(request, found)!!.frames.last().state)
    }

    @Test fun `every real defense to the reported fork loses to a known other endpoint`() {
        val finding = TacticalForkReviewAnalyzer.analyze(input()).finding!!
        val fork = GameEngine.apply(finding.playedAfter, Move.Drop(3)).state
        assertEquals(Player.P1, fork.toMove)
        for (column in 0..6) {
            val defense = GameEngine.apply(fork, Move.Drop(column)).state
            val winningColumn = if (column == 0) 4 else 0
            assertEquals(Status.Won(Player.P2), GameEngine.apply(defense, Move.Drop(winningColumn)).state.status)
        }
    }

    @Test fun `P2 starter uses actual owners and counts their own moves`() {
        val config = LocalConfig(player1 = "Ana", player2 = "Bia")
        var state = config.initial(Player.P2)
        val steps = listOf(6, 1, 6, 2, 5).map { col ->
            val move = Move.Drop(col); val next = GameEngine.apply(state, move).state
            MatchReplay.step(state, move, next).also { state = next }
        }
        val saved = SavedLocalSession(config, starter = Player.P2, steps = steps, stateHash = StateHasher.hash(state))
        val encoded = kotlinx.serialization.json.Json.encodeToString(SavedLocalSession.serializer(), saved)
        val request = LocalTacticalReviewInput(saved, Player.P2)
        val found = TacticalForkReviewAnalyzer.analyze(request).finding!!
        assertEquals(3, found.humanMove)
        assertEquals(Player.P2, found.before.toMove)
        assertEquals(Player.P2, found.playedAfter.pieceAt(5, 5)!!.owner)
        assertEquals(5, TacticalReviewReplay.build(request, found)!!.frames.last().moveNumber)
        assertEquals(encoded, kotlinx.serialization.json.Json.encodeToString(SavedLocalSession.serializer(), saved))
    }

    @Test fun `bomb defense refutes a supposed unavoidable double threat`() {
        val start = position().copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1), Player.P2 to emptyMap()))
        val request = input(start)
        val fork = GameEngine.apply(GameEngine.apply(start, Move.Drop(5)).state, Move.Drop(3)).state
        val rescue = GameEngine.apply(fork, Move.Drop(2, PieceKind.BOMB)).state
        assertEquals(Status.InProgress, rescue.status)
        assertTrue(GameEngine.validMoves(rescue).none { GameEngine.apply(rescue, it).state.status == Status.Won(Player.P2) })
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(request).status)
    }

    @Test fun `safe turn and already terminal move have no invented correction`() {
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(input(move = Move.Drop(0))).status)
        var start = LocalConfig().initial(Player.P1)
        for (col in listOf(0, 6, 1, 6, 2, 5)) start = GameEngine.apply(start, Move.Drop(col)).state
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(input(start, Move.Drop(3))).status)
    }

    @Test fun `an immediate winning defense refutes the opponent fork`() {
        val base = position()
        val pieces = base.pieces.toMutableList()
        pieces[base.index(3, 6)] = Piece(Player.P1, PieceKind.NORMAL, 0)
        val start = base.copy(pieces = pieces)
        val request = input(start)
        val fork = GameEngine.apply(GameEngine.apply(start, Move.Drop(5)).state, Move.Drop(3)).state
        assertEquals(Status.Won(Player.P1), GameEngine.apply(fork, Move.Drop(6)).state.status)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(request).status)
    }

    @Test fun `already unavoidable two ended threat has no invented safe alternative`() {
        var start = position()
        for (col in listOf(5, 3)) start = GameEngine.apply(start, Move.Drop(col)).state
        val request = input(start, Move.Drop(0))
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(request).status)
    }

    @Test fun `invalid suffix rejected and fading or puzzle goals not evaluated as strategic duels`() {
        val request = input()
        assertEquals(TacticalReviewStatus.INVALID_JOURNAL,
            TacticalForkReviewAnalyzer.analyze(request.copy(journal = request.journal.copy(stateHash = "bad"))).status)
        assertEquals(TacticalReviewStatus.FADING_BOARD, TacticalForkReviewAnalyzer.analyze(input(position().copy(fadeAfter = 3))).status)
        assertEquals(TacticalReviewStatus.NO_IMMEDIATE_MISTAKE, TacticalForkReviewAnalyzer.analyze(request.copy(puzzleLimit = 3)).status)
    }

    @Test fun `exhausted search stays explicit and cancellation propagates during probing`() {
        assertEquals(TacticalReviewStatus.LIMIT_REACHED, TacticalForkReviewAnalyzer.analyze(input(), maxProbes = 1).status)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            TacticalForkReviewAnalyzer.analyze(input()) { if (++checks == 20) throw CancellationException() }
        }
        assertEquals(20, checks)
    }
}
