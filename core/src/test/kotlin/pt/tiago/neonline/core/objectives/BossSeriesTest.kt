package pt.tiago.neonline.core.objectives

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*

class BossSeriesTest {
    private fun win(player: Player) = MatchOutcome.Win(player,emptyList())
    private val objective=LevelObjective(LevelKind.BOSS,starsThree=20,starsTwo=30)
    @Test fun `two wins decide the confrontation and only won duel moves determine stars`() {
        var series=BossSeries().record(win(Player.P1),8,1)
        assertNull(series.result(objective))
        series=series.record(win(Player.P2),25,0).record(win(Player.P1),10,2)
        assertEquals(LevelOutcome.Won(3),series.result(objective))
        assertEquals(18,series.winningMoves)
        assertEquals(43,series.totalMoves)
        assertEquals(3,series.hintsUsed)
    }
    @Test fun `two losses decide without awarding any stars`() {
        val series=BossSeries().record(win(Player.P2),4,0).record(win(Player.P2),7,0)
        assertEquals(LevelOutcome.Lost(LossReasons.RIVAL_WON_BEST_OF),series.result(objective))
    }
    @Test fun `first draw repeats the slot and second draw counts as a loss`() {
        val draw=MatchOutcome.Draw("full")
        val first=BossSeries().record(draw,21,0)
        assertEquals(0,first.roundIndex)
        assertTrue(first.repeatingDraw)
        val second=first.record(draw,21,1)
        assertEquals(1,second.roundIndex)
        assertEquals(1,second.losses)
        assertFalse(second.repeatingDraw)
    }
    @Test fun `a win after a draw resets the replay allowance for the following slot`() {
        val draw=MatchOutcome.Draw("full")
        val series=BossSeries().record(draw,20,0).record(win(Player.P1),7,0).record(draw,20,0)
        assertEquals(1,series.roundIndex)
        assertTrue(series.repeatingDraw)
        assertEquals(0,series.losses)
    }
    @Test(expected=IllegalArgumentException::class) fun `finished confrontations cannot accept another result`() {
        BossSeries().record(win(Player.P1),4,0).record(win(Player.P1),5,0).record(win(Player.P2),6,0)
    }
}
