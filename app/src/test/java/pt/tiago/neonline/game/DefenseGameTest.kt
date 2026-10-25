package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*

@OptIn(ExperimentalCoroutinesApi::class)
class DefenseGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun blank() = GameState.initial("defense", Difficulty.EASY, 2, "0123456789abcdef",7,6,4,List(42){true},List(42){null})
    private fun model(start: GameState = blank(), limit: Int = 1, saved: pt.tiago.neonline.data.SavedMatch? = null) =
        GameViewModel(start,LevelObjective(LevelKind.DEFENSE,surviveAiMoves=limit),AiProfile(1,100,3,4,2,1),
            animationEnabled=false,aiDelayMs=0,undoAvailable=3,savedMatch=saved,searchDispatcher=dispatcher)

    @Test fun `surviving N rival turns ends on live board without another move`() = runTest(dispatcher) {
        val game=model(blank().copy(ply=30))
        game.playColumn(0);runCurrent()
        assertEquals(1,game.ui.value.rivalMoveCount)
        assertEquals(Status.InProgress,game.ui.value.state.status)
        assertEquals(LevelOutcome.Won(1),game.ui.value.outcome)
        val hash=game.snapshot().stateHash
        game.playColumn(1);runCurrent()
        assertEquals(hash,game.snapshot().stateHash)
        game.pause()
    }
    @Test fun `survival with a remaining playable threat earns two and early win earns three`() = runTest(dispatcher) {
        val base=blank()
        val fork=base.copy(pieces=base.pieces.toMutableList().apply { this[36]=Piece(Player.P1,PieceKind.NORMAL,0);this[37]=Piece(Player.P1,PieceKind.NORMAL,0) })
        val game=model(fork);game.playColumn(3);runCurrent()
        assertEquals(LevelOutcome.Won(2),game.ui.value.outcome)
        val early=model(base.copy(pieces=base.pieces.toMutableList().apply { for(i in 35..37)this[i]=Piece(Player.P1,PieceKind.NORMAL,0) }))
        early.playColumn(3);runCurrent()
        assertEquals(LevelOutcome.Won(3),early.ui.value.outcome)
        assertEquals(0,early.ui.value.rivalMoveCount)
        game.pause();early.pause()
    }
    @Test fun `rival line on last required turn takes priority over survival`() = runTest(dispatcher) {
        val start=blank().let { it.copy(pieces=it.pieces.toMutableList().apply { for(i in 35..37)this[i]=Piece(Player.P2,PieceKind.NORMAL,0) }) }
        val game=model(start);game.playColumn(6);runCurrent()
        assertEquals(LevelOutcome.Lost(LossReasons.RIVAL_LINE),game.ui.value.outcome)
        game.pause()
    }
    @Test fun `resume and undo derive rival count from journal and never count preplaced position ply`() = runTest(dispatcher) {
        val start=blank().copy(ply=20)
        val first=model(start,2);first.playColumn(0);runCurrent();first.pause()
        val game=model(start,2,first.snapshot())
        assertEquals(1,game.ui.value.rivalMoveCount)
        game.undo();assertEquals(0,game.ui.value.rivalMoveCount)
        game.playColumn(0);runCurrent();assertNull(game.ui.value.outcome)
        game.playColumn(1);runCurrent();assertTrue(game.ui.value.outcome is LevelOutcome.Won)
        assertEquals(2,game.ui.value.rivalMoveCount)
        game.pause()
    }
    @Test fun `resume rejects defense journals at or beyond the survival boundary`() = runTest(dispatcher) {
        val start=blank()
        val game=model(start,4)
        repeat(2) { game.playColumn(it);runCurrent() }
        game.pause()
        val saved=game.snapshot()
        for(limit in listOf(1,2)) {
            assertNull(MatchReplay.restoreDefense(start,saved,0,3,limit))
            assertThrows(IllegalArgumentException::class.java) { model(start,limit,saved) }
        }
        assertNotNull(MatchReplay.restoreDefense(start,saved,0,3,3))
    }
    @Test fun `P2 starter counts its opening turn toward survival`() = runTest(dispatcher) {
        val game=model(blank().copy(toMove=Player.P2))
        runCurrent()
        assertEquals(0,game.ui.value.humanMoveCount)
        assertEquals(1,game.ui.value.rivalMoveCount)
        assertEquals(LevelOutcome.Won(1),game.ui.value.outcome)
        game.pause()
    }
}
