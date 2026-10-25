package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class SolidFloorGameTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `new masked special duels survive real AI fade clock replay undo and restart`() = runTest(dispatcher) {
        for((width,height) in listOf(7 to 6,9 to 7)) for(shape in listOf(FreePlayBoardShape.PILLARS,FreePlayBoardShape.STEPS)) {
            val config=FreePlayConfig(version=5,rules=FreePlayRules(width=width,height=height,shape=shape,specials=true,fade=true,clockSeconds=10))
            var profile=Profile()
            fun model(saved: SavedMatch?=null)=GameViewModel(config.initial(),LevelObjective(LevelKind.DUEL),config.ai()!!,
                animationEnabled=false,searchDispatcher=dispatcher,hintsAvailable=config.hints(),undoAvailable=config.undos(),
                savedMatch=saved,hintProfile=config.hintAi()!!,maxPlies=config.maxPlies(),
                onCheckpoint={match,outcome,_->profile=FreePlay.checkpoint(profile,config,match,outcome)})
            val game=model(); runCurrent()
            game.selectPiece(PieceKind.LOCK); game.playColumn(1); runCurrent()
            game.selectPiece(PieceKind.BOMB); game.playColumn(2); runCurrent()
            assertEquals(4,game.snapshot().steps.size)
            assertEquals(3,game.ui.value.state.engineVersion)
            assertTrue(game.ui.value.state.pieces.indices.all { game.ui.value.state.pieces[it]==null || game.ui.value.state.playable[it] })
            assertEquals(0,game.ui.value.state.inventory.getValue(Player.P1).getValue(PieceKind.BOMB))
            game.pause(); val saved=profile.savedFreePlay!!
            assertEquals(3,saved.engineVersion); assertEquals(game.ui.value.state,FreePlay.restore(saved)!!.state)
            val resumed=model(saved.match); runCurrent()
            assertEquals(game.ui.value.state,resumed.ui.value.state)
            resumed.undo(); runCurrent()
            assertEquals(1,resumed.ui.value.state.inventory.getValue(Player.P1).getValue(PieceKind.BOMB))
            assertEquals(config.rules.playableMask(),resumed.ui.value.state.playable)
            resumed.restart(); runCurrent(); assertEquals(config.initial(),resumed.ui.value.state)
            assertTrue(profile.freePlayStats.isEmpty()); resumed.pause()
        }
    }
}
