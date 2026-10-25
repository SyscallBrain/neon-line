package pt.tiago.neonline.game

import java.io.File
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
import pt.tiago.neonline.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class DailyGameTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `daily journal resumes a certified puzzle and completion is counted once`() = runTest(dispatcher) {
        val root = listOf(File("content"), File("../content")).first { File(it, "catalog.json").isFile }
        val pool = Json.decodeFromString(DailyPoolDto.serializer(), File(root, "daily/pool.json").readText())
        for (difficulty in Difficulty.entries) {
            val entry = DailyChallenge.choose(pool, "2026-10-14", difficulty)!!
            val key = difficultyKey(difficulty)
            val level = Json.decodeFromString(LevelFileDto.serializer(), File(root, "levels/stop-01/${entry.levelId}.json").readText())
            val variant = level.variants.getValue(key)
            val start = variant.toState(level.id, difficulty)
            val objective = LevelObjective(LevelKind.PUZZLE, winInMoves = variant.kind.winInMoves,
                starsThree = variant.stars.three, starsTwo = variant.stars.two)
            val ai = variant.ai.let { AiProfile(it.maxDepth, it.nodeBudget, it.attack, it.defense, it.center, it.trap, it.blunderRate, blunderMayMiss = it.blunderMayMiss) }
            val cert = Json.decodeFromString(CertificateDto.serializer(), File(root, "certificates/${entry.levelId}/$key.json").readText())
            var profile = Profile(savedMatch = SavedMatch("level-0001", Difficulty.EASY), localSession = SavedLocalSession(wins2 = 5))
            val baseline = profile
            fun model(saved: SavedMatch? = null) = GameViewModel(start, objective, ai, difficulty,
                hintsAvailable = variant.hints, undoAvailable = if (difficulty == Difficulty.EASY) 3 else 0,
                animationEnabled = false, savedMatch = saved, searchDispatcher = dispatcher,
                onCheckpoint = { match, outcome, _ -> profile = DailyChallenge.checkpoint(profile, SavedDaily("2026-10-14", pool.version, entry.sha256, match), outcome) })
            val first = model(); runCurrent()
            fun playNext(game: GameViewModel) {
                val next = cert.steps[game.snapshot().steps.size]
                game.playColumn(next.move.substringAfter("col=").substringBefore(",").toInt())
            }
            playNext(first); runCurrent(); first.pause()
            assertNotNull(profile.savedDaily)
            assertTrue(profile.dailyHistory.isEmpty())
            val resumed = model(profile.savedDaily!!.match); runCurrent()
            assertEquals(first.snapshot().stateHash, resumed.snapshot().stateHash)
            while (!resumed.ui.value.finished) { playNext(resumed); runCurrent() }
            assertEquals(LevelOutcome.Won(3), resumed.ui.value.outcome)
            resumed.pause(); resumed.resume(); runCurrent(); resumed.pause()
            assertEquals(1, profile.dailyHistory.single().attempts)
            assertEquals(3, profile.dailyHistory.single().stars)
            assertNull(profile.savedDaily)
            assertEquals(baseline.savedMatch, profile.savedMatch)
            assertEquals(baseline.localSession, profile.localSession)
            assertEquals(0, profile.totalStars())
        }
    }
}
