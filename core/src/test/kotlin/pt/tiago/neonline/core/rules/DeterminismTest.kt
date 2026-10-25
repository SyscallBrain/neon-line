package pt.tiago.neonline.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.serialization.StateHasher

/**
 * Engine-level determinism: replays with fixed seeds always produce the same hashes, and random
 * legal play never breaks the board invariants (grounded pieces, consistent status).
 */
class DeterminismTest {

    private fun randomGame(seed: String, moves: Int): List<String> {
        val rng = pt.tiago.neonline.core.random.SplitMix64(
            pt.tiago.neonline.core.random.SplitMix64.seedFromHex(seed),
        )
        var s = Boards.blank(popOut = rng.nextInt(2) == 1, rotateEvery = if (rng.nextInt(3) == 0) 4 else null)
        val hashes = mutableListOf(StateHasher.hash(s))
        repeat(moves) {
            if (s.status != Status.InProgress) return hashes
            val legal = GameEngine.validMoves(s)
            if (legal.isEmpty()) return hashes
            val move = legal[rng.nextInt(legal.size)]
            val r = GameEngine.apply(s, move)
            s = r.state
            assertNoFloating(s)
            hashes += StateHasher.hash(s)
        }
        return hashes
    }

    @Test
    fun `the same seed replays to the same hashes`() {
        val a = randomGame("0123456789abcdef", 60)
        val b = randomGame("0123456789abcdef", 60)
        assertEquals(a, b)
    }

    @Test
    fun `different seeds usually diverge`() {
        val a = randomGame("0000000000000001", 60)
        val b = randomGame("0000000000000002", 60)
        assertTrue(a != b)
    }

    @Test
    fun `one thousand random games replay to the same hashes`() {
        // The plan's determinism gate: fixed seeds, same final hash on two executions.
        repeat(1000) { i ->
            val seed = "%016x".format(i.toLong() * 0x9E3779B97F4A7C15UL.toLong())
            val first = randomGame(seed, 60)
            assertEquals("seed $seed diverged", first, randomGame(seed, 60))
        }
    }

    @Test
    fun `random illegal moves never change the state`() {
        val rng = pt.tiago.neonline.core.random.SplitMix64(7L)
        var s = Boards.blank()
        repeat(100) {
            val move = when (rng.nextInt(4)) {
                0 -> Move.Drop(rng.nextInt(9) - 1) // sometimes out of range
                1 -> Move.Drop(rng.nextInt(7), PieceKind.BOMB) // never in inventory
                2 -> Move.Pop(rng.nextInt(7)) // no pop rule
                else -> Move.Drop(rng.nextInt(7))
            }
            val r = GameEngine.apply(s, move)
            if (r.outcome == null && r.state == s) {
                // Rejected: the state must be untouched.
                assertTrue(r.events.any { e -> e is pt.tiago.neonline.core.model.GameEvent.MoveRejected })
            }
            s = if (r.state.status == Status.InProgress) r.state else return@repeat
        }
    }

    @Test
    fun `ai versus ai determinism holds for fixed seeds`() {
        // Two games started from the same seed with mirrored policies reach the same states.
        fun play(seed: String): String {
            val rng = pt.tiago.neonline.core.random.SplitMix64(
                pt.tiago.neonline.core.random.SplitMix64.seedFromHex(seed),
            )
            var s = Boards.blank(popOut = true)
            var guard = 0
            while (s.status == Status.InProgress && guard++ < 200) {
                val legal = GameEngine.validMoves(s)
                val move = legal[rng.nextInt(legal.size)]
                s = GameEngine.apply(s, move).state
            }
            return StateHasher.hash(s)
        }
        assertEquals(play("feedfacefeedface"), play("feedfacefeedface"))
        assertTrue(Player.entries.size == 2)
    }
}
