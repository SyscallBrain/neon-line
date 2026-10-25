package pt.tiago.neonline.game

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class BossGameTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val root=listOf(File("content"),File("../content")).first { File(it,"catalog.json").isFile }
    private val level=Json.decodeFromString(LevelFileDto.serializer(),File(root,"levels/stop-01/level-0050.json").readText())
    private val variant=level.variants.getValue("easy")
    private val starts=(0..2).map { variant.forBossRound(it).toState(level.id,Difficulty.EASY) }
    private val objective=LevelObjective(LevelKind.BOSS,starsThree=variant.stars.three,starsTwo=variant.stars.two)
    private val ai=variant.ai.let { AiProfile(it.maxDepth,it.nodeBudget,it.attack,it.defense,it.center,it.trap,it.blunderRate,blunderMayMiss=it.blunderMayMiss) }
    private fun model(saved: SavedMatch?=null, checkpoint: (SavedMatch,LevelOutcome?,Int)->Unit={_,_,_->})=GameViewModel(
        starts[0],objective,ai,hintsAvailable=variant.hints,undoAvailable=3,animationEnabled=false,
        bossRoundStates=starts,savedMatch=saved,searchDispatcher=dispatcher,onCheckpoint=checkpoint,
    )
    private fun cert(slot: Int)=Json.decodeFromString(CertificateDto.serializer(),File(root,"certificates/${level.id}/${if(slot==0) "easy" else "easy-round-${slot+1}"}.json").readText())
    private fun TestScope.playRound(game: GameViewModel,slot: Int) {
        runCurrent()
        val path=cert(slot).steps
        while(!game.ui.value.finished) {
            val next=path[game.snapshot().steps.size]
            val col=next.move.substringAfter("col=").substringBefore(",").toInt()
            game.playColumn(col)
            runCurrent()
        }
        assertEquals(cert(slot).steps.last().afterStateHash,game.snapshot().stateHash)
    }
    @Test fun `first win stays resumable and final win commits the campaign exactly once across a fresh store`()=runTest(dispatcher) {
        val directory=Files.createTempDirectory("boss-profile").toFile()
        try {
            val store=ProfileStore(directory)
            var commits=0
            val checkpoint: (SavedMatch,LevelOutcome?,Int)->Unit={saved,result,moves ->
                if(result!=null) commits++
                val hints=variant.hints-saved.hintsLeft
                store.save(ProfileReducer.checkpoint(store.load(),50,saved,result,moves,hints,variant.rival))
            }
            val first=model(checkpoint=checkpoint)
            first.hint(); runCurrent()
            playRound(first,0)
            assertEquals(0,commits)
            assertEquals(RivalRecord(), store.load().rivalRecords.getValue(variant.rival).getValue(Difficulty.EASY))
            assertEquals(1,first.ui.value.bossSeries!!.wins)
            assertEquals(0,store.load().totalStars())
            val between=ProfileStore(directory).load().savedMatch!!
            val betweenModel=model(between,checkpoint)
            assertNotNull(betweenModel.ui.value.roundOutcome)
            betweenModel.pause(); betweenModel.resume(); betweenModel.nextBossRound(); runCurrent()
            assertEquals(Player.P1,betweenModel.ui.value.state.toMove)
            assertEquals(1,betweenModel.ui.value.bossRoundIndex)
            assertEquals(0,betweenModel.ui.value.humanMoveCount)
            assertEquals(variant.hints-1,betweenModel.ui.value.hintsLeft)
            assertNull(BossReplay.restore(starts,betweenModel.snapshot().copy(hintsLeft=variant.hints),variant.hints,3))
            betweenModel.pause()
            val resumed=model(ProfileStore(directory).load().savedMatch,checkpoint)
            resumed.hint(); runCurrent()
            playRound(resumed,1)
            assertEquals(LevelOutcome.Won(3),resumed.ui.value.outcome)
            assertEquals(1,commits)
            assertEquals(RivalRecord(wins = 1), store.load().rivalRecords.getValue(variant.rival).getValue(Difficulty.EASY))
            assertEquals(3,store.load().totalStars())
            assertEquals(1L,store.load().stats(Difficulty.EASY).wins)
            assertEquals(2L,store.load().stats(Difficulty.EASY).hintsUsed)
            assertEquals(2,resumed.ui.value.bossSeries!!.hintsUsed)
            assertNull(store.load().savedMatch)
            first.pause(); resumed.pause()
        } finally { directory.deleteRecursively() }
    }
    @Test fun `forged score journals and wrong active openings are refused`()=runTest(dispatcher) {
        val game=model(); playRound(game,0); game.nextBossRound(); runCurrent()
        val saved=game.snapshot()
        assertNotNull(BossReplay.restore(starts,saved,variant.hints,3))
        assertNull(BossReplay.restore(starts,saved.copy(bossHistory=saved.bossHistory+saved.bossHistory),variant.hints,3))
        assertNull(BossReplay.restore(starts,saved.copy(initialHash="wrong"),variant.hints,3))
        assertNull(BossReplay.restore(starts,saved.copy(bossHistory=saved.bossHistory.map { it.copy(stateHash="wrong") }),variant.hints,3))
        game.pause()
    }
    @Test fun `restarting the confrontation resets the score and starts with the original opening`()=runTest(dispatcher) {
        val game=model(); playRound(game,0); game.nextBossRound(); runCurrent(); game.restart()
        assertEquals(starts[0],game.ui.value.state)
        assertEquals(BossSeries(),game.ui.value.bossSeries)
        assertTrue(game.snapshot().bossHistory.isEmpty())
        assertEquals(variant.hints,game.ui.value.hintsLeft)
        game.pause()
    }
    @Test fun `draw repeats once then advances as a loss and can be resumed between attempts`()=runTest(dispatcher) {
        fun start(player: Player,seed: String)=GameState.initial("draw-boss",Difficulty.EASY,GameEngine.ENGINE_VERSION,seed,4,4,4,List(16){it in 12..14},List(16){null},firstToMove=player)
        val tiny=listOf(start(Player.P1,"0123456789abcdef"),start(Player.P2,"1123456789abcdef"),start(Player.P1,"2123456789abcdef"))
        fun tinyModel(saved: SavedMatch?=null)=GameViewModel(tiny[0],objective,AiProfile.EASY.copy(maxDepth=1,nodeBudget=100,blunderRate=0),
            animationEnabled=false,bossRoundStates=tiny,savedMatch=saved,searchDispatcher=dispatcher)
        val first=tinyModel(); first.playColumn(0); runCurrent(); first.playColumn(2); runCurrent()
        assertTrue(first.ui.value.bossSeries!!.repeatingDraw)
        val restored=tinyModel(first.snapshot())
        restored.nextBossRound(); restored.playColumn(0); runCurrent(); restored.playColumn(2); runCurrent()
        assertEquals(1,restored.ui.value.bossSeries!!.losses)
        assertFalse(restored.ui.value.bossSeries!!.repeatingDraw)
        assertNull(restored.ui.value.outcome)
        first.pause(); restored.pause()
    }
    @Test fun `a two one victory plays the third opening after the rival wins the second duel`()=runTest(dispatcher) {
        fun opening(owner: Player, starter: Player, seed: String) = GameState.initial("three-rounds",Difficulty.EASY,GameEngine.ENGINE_VERSION,
            seed,7,6,4,List(42){true},List(42){ index -> if(index in 35..37) Piece(owner,PieceKind.NORMAL,0) else null },firstToMove=starter)
        val openings=listOf(opening(Player.P1,Player.P1,"0123456789abcdef"),opening(Player.P2,Player.P2,"1123456789abcdef"),opening(Player.P1,Player.P1,"2123456789abcdef"))
        val game=GameViewModel(openings[0],LevelObjective(LevelKind.BOSS,starsThree=2,starsTwo=3),AiProfile.HARD,
            animationEnabled=false,bossRoundStates=openings,searchDispatcher=dispatcher)
        game.playColumn(3); game.nextBossRound(); runCurrent()
        assertEquals(1,game.ui.value.bossSeries!!.wins)
        assertEquals(1,game.ui.value.bossSeries!!.losses)
        assertNull(game.ui.value.outcome)
        game.nextBossRound(); game.playColumn(3)
        assertEquals(2,game.ui.value.bossRoundIndex)
        assertEquals(LevelOutcome.Won(3),game.ui.value.outcome)
        assertEquals(2,game.snapshot().bossHistory.size)
        game.pause()
    }

}
