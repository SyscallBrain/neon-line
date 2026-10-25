package pt.tiago.neonline.game

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.data.*

class ForkDemonstrationTest {
    private fun input(): TacticalReviewInput {
        var start = LocalConfig().initial(Player.P1)
        for (col in listOf(6, 1, 6, 2)) start = GameEngine.apply(start, Move.Drop(col)).state
        return input(start)
    }
    private fun input(start: GameState): TacticalReviewInput {
        val move = Move.Drop(5)
        val after = GameEngine.apply(start, move).state
        return TacticalReviewInput(start, SavedMatch(start.levelId, start.difficulty,
            initialHash = StateHasher.hash(start), stateHash = StateHasher.hash(after),
            steps = listOf(MatchReplay.step(start, move, after))), 0, 0)
    }

    @Test fun `complete lesson shows every legal defense and the other winning endpoint`() {
        val request = input()
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        val result = ForkDemonstration.build(request, finding)
        assertEquals(ForkDemonstrationStatus.READY, result.status)
        val lesson = result.lesson!!
        assertEquals(Player.P1, lesson.player)
        assertEquals(Player.P2, lesson.opponent)
        assertEquals(Move.Drop(3), lesson.creatingMove)
        assertEquals(GameEngine.apply(finding.playedAfter, Move.Drop(3)).state, lesson.forkState)
        assertEquals(listOf(0, 4), lesson.winningColumns)
        assertEquals((0..6).map { Move.Drop(it) }, lesson.defenses.map { it.move })
        lesson.defenses.forEach { defense ->
            val col = (defense.move as Move.Drop).col
            assertEquals(GameEngine.apply(lesson.forkState, defense.move).state, defense.after)
            assertEquals(Move.Drop(if (col == 0) 4 else 0), defense.response)
            assertEquals(Status.Won(Player.P2), defense.finalState.status)
            assertEquals(GameEngine.apply(defense.after, defense.response!!).state, defense.finalState)
        }
    }

    @Test fun `local P2 starter lesson retains real owners and full finished journal`() {
        val config = LocalConfig(player1 = "Ana", player2 = "Bia")
        var state = config.initial(Player.P2)
        val steps = listOf(6, 1, 6, 2, 5, 3, 0, 4).map { col ->
            val move = Move.Drop(col); val next = GameEngine.apply(state, move).state
            MatchReplay.step(state, move, next).also { state = next }
        }
        assertEquals(Status.Won(Player.P1), state.status)
        val saved = SavedLocalSession(config, starter = Player.P2, wins1 = 1, steps = steps, stateHash = StateHasher.hash(state))
        val encoded = Json.encodeToString(SavedLocalSession.serializer(), saved)
        val request = LocalTacticalReviewInput(saved, Player.P2)
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        assertEquals(3, finding.humanMove)
        val lesson = ForkDemonstration.build(request, finding).lesson!!
        assertEquals(Player.P2, lesson.player)
        assertEquals(Player.P1, lesson.opponent)
        assertEquals(Player.P1, lesson.forkState.pieceAt(5, 3)!!.owner)
        assertTrue(lesson.defenses.all { it.finalState.status == Status.Won(Player.P1) })
        assertEquals(encoded, Json.encodeToString(SavedLocalSession.serializer(), saved))
    }

    @Test fun `all selectable pop defenses retain the complete action and exact response board`() {
        val request = input(input().start.copy(popOut = true))
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        val lesson = ForkDemonstration.build(request, finding).lesson!!
        val pops = lesson.defenses.filter { it.move is Move.Pop }
        assertEquals(listOf(Move.Pop(5), Move.Pop(6)), pops.map { it.move })
        pops.forEach { defense ->
            assertEquals(GameEngine.apply(lesson.forkState, defense.move).state, defense.after)
            assertEquals(Status.Won(Player.P2), defense.finalState.status)
        }
    }

    @Test fun `special rescue refutes forged lesson even when journal anchor is valid`() {
        val original = input()
        val finding = TacticalForkReviewAnalyzer.analyze(original).finding!!
        val request = input(original.start.copy(inventory = mapOf(Player.P1 to mapOf(PieceKind.BOMB to 1), Player.P2 to emptyMap())))
        val forged = finding.copy(before = request.start, playedAfter = GameEngine.apply(request.start, finding.played).state)
        assertNotNull(TacticalReviewReplay.build(request, forged))
        val result = ForkDemonstration.build(request, forged)
        assertEquals(ForkDemonstrationStatus.UNAVAILABLE, result.status)
        assertNull(result.lesson)
    }

    @Test fun `invalid anchor suffix reason and missing fork column expose no lesson`() {
        val request = input()
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        assertNull(ForkDemonstration.build(request.copy(journal = request.journal.copy(stateHash = "bad")), finding).lesson)
        assertNull(ForkDemonstration.build(request, finding.copy(humanMove = 2)).lesson)
        assertNull(ForkDemonstration.build(request, finding.copy(reason = TacticalReason.MISSED_WIN)).lesson)
        assertNull(ForkDemonstration.build(request, finding.copy(rivalWinningColumn = null)).lesson)
        assertNull(ForkDemonstration.build(request, finding.copy(rivalWinningColumn = 6)).lesson)
    }

    @Test fun `fading boards and puzzle objectives expose no hypothetical lesson`() {
        val request = input()
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        val fading = input(request.start.copy(fadeAfter = 3))
        val changed = finding.copy(before = fading.start, playedAfter = GameEngine.apply(fading.start, finding.played).state)
        assertNull(ForkDemonstration.build(fading, changed).lesson)
        assertNull(ForkDemonstration.build(request.copy(puzzleLimit = 3), finding).lesson)
    }

    @Test fun `budget and cancellation never expose partial defense lists`() {
        val request = input()
        val finding = TacticalForkReviewAnalyzer.analyze(request).finding!!
        val limited = ForkDemonstration.build(request, finding, maxProbes = 10)
        assertEquals(ForkDemonstrationStatus.LIMIT_REACHED, limited.status)
        assertNull(limited.lesson)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ForkDemonstration.build(request, finding) { if (++checks == 25) throw CancellationException() }
        }
        assertEquals(25, checks)
    }
}
