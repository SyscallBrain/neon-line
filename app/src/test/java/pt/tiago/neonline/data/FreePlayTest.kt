package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.serialization.StateHasher

class FreePlayTest {
    private val config = FreePlayConfig()
    private fun journal(c: FreePlayConfig = config): SavedMatch {
        val start = c.initial()
        val hash = StateHasher.hash(start)
        return SavedMatch(start.levelId, c.difficulty, initialHash = hash, stateHash = hash, hintsLeft = c.hints(), undosLeft = c.undos())
    }
    @Test fun `classic starts empty and preserves starter difficulty and seed`() {
        for (difficulty in Difficulty.entries) for (starter in Player.entries) {
            val c = config.copy(difficulty = difficulty, starter = starter)
            val start = c.initial()
            assertTrue(start.pieces.all { it == null }); assertTrue(start.playable.all { it })
            assertEquals(7, start.width); assertEquals(6, start.height); assertEquals(4, start.connect)
            assertEquals(starter, start.toMove); assertEquals(difficulty, start.difficulty)
            assertEquals(start, c.initial())
            assertNotEquals(StateHasher.hash(start), StateHasher.hash(c.copy(seedHex = "fedcba9876543210").initial()))
        }
    }
    @Test fun `new games require an encountered packaged supported rival`() {
        val levels = listOf(RivalLevel("level-0001", 1, Difficulty.EASY, "sparky", false))
        assertFalse(FreePlay.canStart(Profile(), levels, config))
        val known = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 3, 4, 0)
        assertTrue(FreePlay.canStart(known, levels, config.copy(difficulty = Difficulty.HARD)))
        assertFalse(FreePlay.canStart(known, emptyList(), config))
        assertFalse(FreePlay.canStart(known, levels, config.copy(rival = "future-rival")))
    }
    @Test fun `perfect mode is hard classic free play and needs no rival gallery unlock`() {
        val perfect = FreePlayConfig(version = 8, rival = "perfect", difficulty = Difficulty.HARD)

        assertTrue(perfect.isValid())
        assertTrue(perfect.isPerfectMode())
        assertNotNull(perfect.ai())
        assertEquals(0, perfect.hints())
        assertEquals(0, perfect.undos())
        assertTrue(FreePlay.canStart(Profile(), emptyList(), perfect))
        assertEquals(perfect, Json.decodeFromString(FreePlayConfig.serializer(), Json.encodeToString(FreePlayConfig.serializer(), perfect)))
        assertTrue(FreePlay.restore(SavedFreePlay(perfect, journal(perfect))) != null)

        assertFalse(perfect.copy(difficulty = Difficulty.EASY).isValid())
        assertFalse(perfect.copy(rules = FreePlayRules(popOut = true)).isValid())
        assertFalse(perfect.copy(rules = FreePlayRules(width = 9, height = 7)).isValid())
    }
    @Test fun `a caller supplied rival profile also supplies hints for tournament-only rivals`() {
        val tournamentConfig = FreePlayConfig(version = 8, rival = "diva", difficulty = Difficulty.HARD)
        val tournamentRival = pt.tiago.neonline.core.ai.AiProfile.HARD

        assertTrue(tournamentConfig.isValid())
        assertNull(tournamentConfig.ai())
        assertEquals(tournamentRival, tournamentConfig.hintAi(tournamentRival))
    }
    @Test fun `saved empty game resumes independently of campaign unlocks and damaged journals fail`() {
        val saved = SavedFreePlay(config, journal())
        assertEquals(config.initial(), FreePlay.restore(saved)?.state)
        assertNull(FreePlay.restore(saved.copy(match = saved.match.copy(stateHash = "bad"))))
        assertNull(FreePlay.restore(saved.copy(config = config.copy(version = 999))))
        assertNull(FreePlay.restore(saved.copy(config = config.copy(rival = "future-rival"))))
        assertFalse(config.copy(seedHex = "bad").isValid())
        assertFalse(config.copy(version = 0).isValid())
    }
    @Test fun `only finished games count and all other profile data stays unchanged`() {
        val baseline = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 3, 4, 0).copy(
            savedMatch = SavedMatch("level-0002", Difficulty.EASY), localSession = SavedLocalSession(wins1 = 4),
            savedDaily = SavedDaily("2026-10-15", 1, "a".repeat(64), SavedMatch("level-0002", Difficulty.HARD)))
        var profile = FreePlay.checkpoint(FreePlay.start(baseline, config), config, journal(), null)
        assertEquals(SavedFreePlay(config, journal()), profile.savedFreePlay)
        assertTrue(profile.freePlayStats.isEmpty())
        profile = FreePlay.checkpoint(profile, config, journal(), LevelOutcome.Won(3))
        profile = FreePlay.checkpoint(FreePlay.start(profile, config), config, journal(), LevelOutcome.Lost("draw"))
        profile = FreePlay.checkpoint(FreePlay.start(profile, config), config, journal(), LevelOutcome.Lost("rival_won"))
        assertNull(profile.savedFreePlay)
        assertEquals(RivalRecord(wins = 1, draws = 1, losses = 1), profile.freePlayStats[Difficulty.EASY])
        assertEquals(baseline.copy(revision = profile.revision, freePlayStats = profile.freePlayStats), profile)
    }
    @Test fun `a late checkpoint from an older free game cannot replace or finish the active game`() {
        val older = config.copy(seedHex = "1111111111111111")
        val active = config.copy(seedHex = "2222222222222222")
        val profile = FreePlay.start(Profile(), active)

        assertEquals(profile, FreePlay.checkpoint(profile, older, journal(older), null))
        assertEquals(profile, FreePlay.checkpoint(profile, older, journal(older), LevelOutcome.Won(3)))
        assertEquals(SavedFreePlay(active, journal(active)), profile.savedFreePlay)
        assertTrue(profile.freePlayStats.isEmpty())
    }
    @Test fun `a callback from a previous screen session is ignored after the active game has finished`() {
        val older = config.copy(seedHex = "1111111111111111")
        val active = config.copy(seedHex = "2222222222222222")
        val finished = FreePlay.checkpoint(FreePlay.start(Profile(), active), active, journal(active), LevelOutcome.Won(3))

        assertNull(finished.savedFreePlay)
        assertEquals(finished, FreePlay.checkpointForCurrentSession(finished, older, journal(older),
            LevelOutcome.Won(3), callbackSession = 4, currentSession = 5))
    }
    @Test fun `storage backup and campaign reset preserve independent games and results`() {
        val dir = Files.createTempDirectory("free-play").toFile()
        try {
            assertNull(Json.decodeFromString(Profile.serializer(), "{}").savedFreePlay)
            var profile = FreePlay.checkpoint(FreePlay.start(Profile(), config), config, journal(), LevelOutcome.Won(3))
            profile = FreePlay.checkpoint(FreePlay.start(profile, config), config, journal(), null)
            val store = ProfileStore(dir); store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            val reset = store.resetProgress()
            assertEquals(profile.savedFreePlay, reset.savedFreePlay)
            assertEquals(profile.freePlayStats, reset.freePlayStats)
            assertNotNull(FreePlay.restore(reset.savedFreePlay!!))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `classic rival searches respect published Sparky budgets and block immediate threats`() {
        val root = listOf(java.io.File("../content"), java.io.File("content")).first { java.io.File(it, "catalog.json").isFile }
        val level = Json.decodeFromString(pt.tiago.neonline.core.content.LevelFileDto.serializer(), java.io.File(root, "levels/stop-01/level-0003.json").readText())
        for (difficulty in Difficulty.entries) {
            val c = config.copy(difficulty = difficulty, starter = Player.P2)
            val profile = c.ai()!!
            val search = pt.tiago.neonline.core.ai.AiSearch(profile)
            val state = c.initial()
            val choice = search.chooseMove(state)!!
            assertTrue(choice.move in pt.tiago.neonline.core.rules.GameEngine.validMoves(state))
            val budget = level.variants.getValue(pt.tiago.neonline.core.content.difficultyKey(difficulty)).ai.nodeBudget
            assertTrue("$difficulty search exceeded published budget $budget: ${search.nodesExplored}", search.nodesExplored <= budget)
            val hintSearch = pt.tiago.neonline.core.ai.AiSearch(c.hintAi()!!)
            assertTrue(hintSearch.chooseMove(state)!!.move in pt.tiago.neonline.core.rules.GameEngine.validMoves(state))
            val hintBudget = level.variants.getValue("hard").ai.nodeBudget
            assertTrue("Free hint exceeded published Hard budget", hintSearch.nodesExplored <= hintBudget)
            if (difficulty != Difficulty.EASY) {
                val threat = state.copy(pieces = state.pieces.toMutableList().also { pieces -> (35..37).forEach { pieces[it] = Piece(Player.P1, PieceKind.NORMAL, 0) } })
                assertEquals(Move.Drop(3), pt.tiago.neonline.core.ai.AiSearch(profile).chooseMove(threat)!!.move)
            }
        }
    }
    @Test fun `invalid imported counters are rejected and maximum accepted counters cannot overflow`() {
        assertFalse(FreePlay.isValid(Profile(freePlayStats = mapOf(Difficulty.EASY to RivalRecord(wins = -1)))))
        val max = FreePlay.MAX_COUNTER
        val profile = Profile(freePlayStats = mapOf(Difficulty.EASY to RivalRecord(max, max, max)))
        val after = FreePlay.checkpoint(FreePlay.start(profile, config), config, journal(), LevelOutcome.Won(3))
        assertTrue(FreePlay.isValid(after))
        assertEquals(max, after.freePlayStats.getValue(Difficulty.EASY).wins)
    }
}
