package pt.tiago.neonline.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.Boards
import pt.tiago.neonline.core.rules.GameEngine

class AiSearchTest {

    private val strong = AiProfile(
        maxDepth = 8,
        nodeBudget = 400_000,
        attack = 6,
        defense = 5,
        center = 3,
        trap = 4,
        blunderRate = 0,
    )

    private val easy = strong.copy(maxDepth = 2, nodeBudget = 5_000, blunderRate = 0)

    private fun wins(state: pt.tiago.neonline.core.model.GameState, move: Move): Boolean =
        GameEngine.apply(state, move).outcome is MatchOutcome.Win

    @Test
    fun `takes an immediate win`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val choice = AiSearch(strong).chooseMove(s)
        assertNotNull(choice)
        assertTrue(wins(s, choice!!.move))
        assertEquals(3, (choice.move as Move.Drop).col)
    }

    @Test
    fun `deep search checks cooperative cancellation between nodes`() {
        var checks = 0

        org.junit.Assert.assertThrows(CancellationException::class.java) {
            AiSearch(strong).chooseMove(Boards.blank(), checkCancelled = {
                if (++checks == 3) throw CancellationException()
            })
        }
        assertTrue(checks >= 3)
    }

    @Test
    fun `blocks an immediate loss`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
            firstToMove = Player.P2,
        )
        val choice = AiSearch(strong).chooseMove(s)
        assertNotNull(choice)
        // The only move that stops P1's four is the same column.
        assertEquals(3, (choice!!.move as Move.Drop).col)
        assertNull(GameEngine.apply(s, choice.move).outcome)
    }

    @Test
    fun `blocks an immediate vertical loss`() {
        val s = Boards.state(
            ".......",
            ".......",
            "..1....",
            "..1....",
            "..1....",
            ".......",
            firstToMove = Player.P2,
        )
        val choice = AiSearch(strong).chooseMove(s)
        assertEquals(2, (choice!!.move as Move.Drop).col)
    }

    @Test
    fun `prefers the center on an empty board`() {
        val choice = AiSearch(strong).chooseMove(Boards.blank())
        assertEquals(3, (choice!!.move as Move.Drop).col)
    }

    @Test
    fun `is deterministic for the same state`() {
        val s = Boards.state(
            ".......",
            ".......",
            "..1....",
            "..2....",
            "..1....",
            "..2.1..",
        )
        val first = AiSearch(strong).chooseMove(s)
        val second = AiSearch(strong).chooseMove(s)
        assertEquals(first, second)
        assertEquals(first!!.aiStateHex, second!!.aiStateHex)
    }

    @Test
    fun `respects the node budget`() {
        val profile = strong.copy(maxDepth = 40, nodeBudget = 200)
        val search = AiSearch(profile)
        val choice = search.chooseMove(Boards.blank())
        assertNotNull(choice)
        assertTrue("nodes ${search.nodesExplored} exceed budget", search.nodesExplored <= 200 + 64)
    }

    @Test
    fun `always returns a legal move on random positions`() {
        val rng = SplitMix64(4242L)
        var checked = 0
        repeat(120) {
            var s = Boards.blank(
                popOut = rng.nextInt(2) == 1,
                rotateEvery = if (rng.nextInt(4) == 0) 5 else null,
            )
            var guard = 0
            while (s.status == Status.InProgress && guard++ < 30) {
                // The opponent plays a random legal move, then the AI answers.
                val opponentMoves = GameEngine.validMoves(s)
                if (opponentMoves.isEmpty()) break
                s = GameEngine.apply(s, opponentMoves[rng.nextInt(opponentMoves.size)]).state
                if (s.status != Status.InProgress) break
                val choice = AiSearch(easy).chooseMove(s)
                assertNotNull(choice)
                assertTrue(
                    "illegal move ${choice!!.move}",
                    GameEngine.validMoves(s).contains(choice.move),
                )
                checked++
                s = GameEngine.apply(s.copy(aiStateHex = choice.aiStateHex), choice.move).state
            }
        }
        assertTrue("checked too few positions: $checked", checked > 100)
    }

    @Test
    fun `finds a double threat that a shallow search cannot see`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..11...",
        )
        val choice = AiSearch(strong).chooseMove(s)
        assertNotNull(choice)
        val after = GameEngine.apply(s, choice!!.move).state
        assertEquals(Player.P2, after.toMove)
        // Whatever P2 answers, P1 wins immediately afterwards.
        for (reply in GameEngine.validMoves(after)) {
            val afterReply = GameEngine.apply(after, reply).state
            assertTrue(
                "no winning move after reply $reply",
                GameEngine.validMoves(afterReply).any { wins(afterReply, it) },
            )
        }
    }

    @Test
    fun `the mirror profile answers the symmetric column`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "..1....",
            firstToMove = Player.P2,
        )
        val mirrorProfile = easy.copy(mirror = true)
        val choice = AiSearch(mirrorProfile).chooseMove(s)
        assertEquals(4, (choice!!.move as Move.Drop).col)
    }

    @Test
    fun `blunder rate is drawn from the ai stream and advances it`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val choice = AiSearch(strong.copy(blunderRate = 999)).chooseMove(s)
        assertNotNull(choice)
        assertTrue(choice!!.aiStateHex != s.aiStateHex)
        // A second search from the advanced state keeps consuming the stream.
        val next = s.copy(aiStateHex = choice.aiStateHex)
        val again = AiSearch(strong.copy(blunderRate = 999)).chooseMove(next)
        assertTrue(again!!.aiStateHex != choice.aiStateHex)
    }

    @Test
    fun `the easy profile may blunder but the strong one never does`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "222....",
            firstToMove = Player.P2,
        )
        // P2 (the AI) has three in a row at cols 0..2 and can win at col 3.
        val strongChoice = AiSearch(strong).chooseMove(s)
        assertTrue(wins(s, strongChoice!!.move))
        // With the maximum blunder rate on Easy the AI may pick a different move; it must stay legal.
        val sloppy = AiSearch(easy.copy(blunderRate = 1000, blunderMayMiss = true)).chooseMove(s)
        assertTrue(GameEngine.validMoves(s).contains(sloppy!!.move))
    }

    @Test
    fun `no move is offered once the match is over`() {
        val s = Boards.state(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "111....",
        )
        val won = GameEngine.apply(s, Move.Drop(3)).state
        assertNull(AiSearch(strong).chooseMove(won))
    }
    @Test
    fun `easy blunder remains legal after its search budget is exhausted`() {
        val state = pt.tiago.neonline.core.rules.Boards.blank()
        val profile = easy.copy(nodeBudget = 1, maxDepth = 6, blunderRate = 1000, blunderMayMiss = true)
        val search = AiSearch(profile)
        val choice = search.chooseMove(state)!!
        assertTrue(choice.move in GameEngine.validMoves(state))
        assertTrue(search.nodesExplored <= profile.nodeBudget + 1)
        assertEquals(choice, AiSearch(profile).chooseMove(state))
    }
    @Test
    fun `does not mistake a pop giving the opponent a line for its own win`() {
        val state = Boards.state(
            ".......", ".......", ".......", ".......", "...2...", "2221...",
            popOut = true,
        )
        val losingPop = Move.Pop(3)
        assertEquals(Player.P2, (GameEngine.apply(state, losingPop).state.status as Status.Won).player)
        val choice = AiSearch(strong.copy(prefersPop = true)).chooseMove(state)!!
        assertTrue(choice.move != losingPop)
    }

}
