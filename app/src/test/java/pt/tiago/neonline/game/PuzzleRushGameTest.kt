package pt.tiago.neonline.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.*
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleRushGameTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `real first certified puzzle finishes unassisted and records only rush score once`() = runTest(dispatcher) {
        val root=listOf(java.io.File("../content"),java.io.File("content")).first { java.io.File(it,"catalog.json").isFile }
        val dto=Json.decodeFromString(LevelFileDto.serializer(),java.io.File(root,"levels/stop-01/level-0001.json").readText())
        val variant=dto.variants.getValue("easy")
        val start=variant.toState(dto.id,Difficulty.EASY)
        var run=RushRun(Difficulty.HARD,"0123456789abcdef",1000)
        val original=Profile(savedMatch=SavedMatch("level-0004",Difficulty.HARD),localSession=SavedLocalSession(wins1=2))
        var profile=original
        val model=GameViewModel(start,LevelObjective(LevelKind.PUZZLE,winInMoves=1),pt.tiago.neonline.core.ai.AiProfile.EASY,
            hintsAvailable=0,undoAvailable=0,animationEnabled=false,searchDispatcher=dispatcher,onCheckpoint={_,outcome,_->
                if(outcome!=null) { run=PuzzleRush.complete(run,0,outcome is LevelOutcome.Won,2000);profile=PuzzleRush.record(profile,run.difficulty,run.solved) }
            })
        runCurrent()
        assertEquals(0,model.ui.value.hintsLeft);assertEquals(0,model.ui.value.undosLeft)
        model.hint();model.undo();runCurrent()
        assertTrue(model.snapshot().steps.isEmpty())
        model.playColumn(3);runCurrent()
        assertTrue(model.ui.value.outcome is LevelOutcome.Won)
        assertEquals(1,run.solved)
        assertEquals(original.copy(revision=profile.revision,rushBest=mapOf(Difficulty.HARD to 1)),profile)
        model.pause();model.resume();runCurrent()
        assertEquals(1,run.solved);assertEquals(1,profile.rushBest[Difficulty.HARD])
        model.pause()
    }
    @Test fun `wrong real puzzle move counts one error without campaign or record award`() = runTest(dispatcher) {
        val root=listOf(java.io.File("../content"),java.io.File("content")).first { java.io.File(it,"catalog.json").isFile }
        val dto=Json.decodeFromString(LevelFileDto.serializer(),java.io.File(root,"levels/stop-01/level-0001.json").readText())
        val start=dto.variants.getValue("easy").toState(dto.id,Difficulty.EASY)
        var run=RushRun(Difficulty.MEDIUM,"0123456789abcdef",1000)
        val model=GameViewModel(start,LevelObjective(LevelKind.PUZZLE,winInMoves=1),pt.tiago.neonline.core.ai.AiProfile.EASY,
            hintsAvailable=0,undoAvailable=0,animationEnabled=false,searchDispatcher=dispatcher,onCheckpoint={_,outcome,_->
                if(outcome!=null) run=PuzzleRush.complete(run,0,outcome is LevelOutcome.Won,2000)
            })
        model.playColumn(6);runCurrent()
        assertTrue(model.ui.value.outcome is LevelOutcome.Lost)
        assertEquals(1,run.errors);assertEquals(0,run.solved)
        model.pause();runCurrent();assertEquals(1,run.errors)
    }
}
