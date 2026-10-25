package pt.tiago.neonline.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine

/**
 * The plan's M2 gate: Medium and Hard never miss an immediate block in 10,000 positions.
 *
 * Ruling: the block is enforced by the safety rule that runs before the search, so the node
 * budget cannot change the answer. The profiles below keep the real Medium and Hard weights with
 * a deliberately small budget so the gate stays a test and not a benchmark; the search itself is
 * covered by [AiSearchTest] (double threats, determinism, budget).
 */
class AiSafetyGateTest {

    private val mediumGate = AiProfile.MEDIUM.copy(maxDepth = 2, nodeBudget = 250)
    private val hardGate = AiProfile.HARD.copy(maxDepth = 2, nodeBudget = 250)

    @Test
    fun `medium never misses an immediate block in 10000 positions`() {
        assertTrue(runGate(mediumGate, 10_000))
    }

    @Test
    fun `hard never misses an immediate block in 10000 positions`() {
        assertTrue(runGate(hardGate, 10_000))
    }

    /** Returns true when every tested position was answered with a complete block. */
    private fun runGate(profile: AiProfile, wanted: Int): Boolean {
        val rng = SplitMix64(0x5AFE7L)
        var tested = 0
        var skipped = 0
        var games = 0
        while (tested < wanted && games < wanted * 8) {
            games++
            var s = Boards.blank(popOut = rng.nextInt(6) == 0, rotateEvery = if (rng.nextInt(8) == 0) 5 else null)
            var guard = 0
            while (s.status == Status.InProgress && guard++ < 42) {
                val opponentThreats = Evaluator.countThreats(s, s.toMove.opponent())
                val myThreats = Evaluator.countThreats(s, s.toMove)
                if (opponentThreats == 1 && myThreats == 0) {
                    // Winning now is always better than blocking; those positions are covered by
                    // the immediate-win tests in AiSearchTest.
                    val canWin = GameEngine.validMoves(s).any { move ->
                        GameEngine.apply(s, move).state.status is Status.Won
                    }
                    // Only positions where a block is possible count: with rotating gravity the
                    // rotation itself can create an unavoidable threat.
                    val blockable = !canWin && GameEngine.validMoves(s).any { move ->
                        !Evaluator.hasThreat(GameEngine.apply(s, move).state, s.toMove.opponent())
                    }
                    if (!blockable) {
                        skipped++
                    } else {
                        val ai = AiSearch(profile).chooseMove(s) ?: break
                        if (!GameEngine.validMoves(s).contains(ai.move)) {
                            println("illegal move after ${s.ply} plies: $s")
                            return false
                        }
                        val after = GameEngine.apply(s.copy(aiStateHex = ai.aiStateHex), ai.move).state
                        if (Evaluator.hasThreat(after, after.toMove)) {
                            println("missed block after ${s.ply} plies, answered with ${ai.move}")
                            return false
                        }
                        tested++
                        val replies = GameEngine.validMoves(after)
                        if (replies.isEmpty()) break
                        s = GameEngine.apply(after, replies[rng.nextInt(replies.size)]).state
                        continue
                    }
                }
                val moves = GameEngine.validMoves(s)
                if (moves.isEmpty()) break
                s = GameEngine.apply(s, moves[rng.nextInt(moves.size)]).state
            }
        }
        println("gate tested $tested positions ($skipped unblockable skipped) over $games games")
        assertTrue("only tested $tested positions", tested >= wanted)
        return true
    }
}
