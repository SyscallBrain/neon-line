package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

class RivalGalleryTest {
    private val easy = Difficulty.EASY
    private val levels = Difficulty.entries.flatMap { difficulty -> listOf(
        RivalLevel("level-0001", 1, difficulty, "sparky", false),
        RivalLevel("level-0002", 2, difficulty, "sparky", false),
        RivalLevel("level-0050", 50, difficulty, "sparky", true)) }
    @Test fun `roster starts hidden and unlocking a level does not reveal an opponent`() {
        val cards = RivalGallery.summarize(Profile(), levels, easy)
        assertEquals(12, cards.size)
        assertTrue(cards.none { it.encountered || it.bossBeaten })
        assertEquals(1, cards.first().challengeOrdinal)
        assertNull(cards[1].challengeOrdinal)
    }
    @Test fun `starting or losing reveals the opponent without creating wins`() {
        val active = RivalGallery.record(Profile(), "sparky", easy, null)
        assertTrue(RivalGallery.summarize(active, levels, easy).first().encountered)
        assertEquals(RivalRecord(), active.rivalRecords.getValue("sparky").getValue(easy))
        val lost = RivalGallery.record(active, "sparky", easy, LevelOutcome.Lost("out_of_moves"))
        assertEquals(RivalRecord(losses = 1), lost.rivalRecords.getValue("sparky").getValue(easy))
        assertEquals(active, RivalGallery.record(active, "sparky", easy, null))
    }
    @Test fun `old wins reveal opponents and correct boss difficulty without inventing counters`() {
        var profile = ProfileReducer.recordWin(Profile(), 1, easy, 3, 4, 0)
        var card = RivalGallery.summarize(profile, levels, easy).first()
        assertTrue(card.encountered); assertFalse(card.bossBeaten)
        assertEquals(RivalRecord(), card.record)
        assertEquals(2, card.challengeOrdinal)
        profile = ProfileReducer.recordWin(profile, 50, Difficulty.HARD, 3, 9, 0)
        card = RivalGallery.summarize(profile, levels, easy).first()
        assertTrue(card.bossBeaten)
        assertEquals(setOf(Difficulty.HARD), card.bossDifficulties)
        assertTrue(profile.rivalRecords.isEmpty())
    }
    @Test fun `saved campaign proves encounter but daily and local sessions do not`() {
        val saved = SavedMatch("level-0002", Difficulty.MEDIUM)
        assertTrue(RivalGallery.summarize(Profile(savedMatch = saved), levels, easy).first().encountered)
        val otherModes = Profile(savedDaily = SavedDaily("2026-10-15", 1, "a".repeat(64), saved), localSession = SavedLocalSession(wins1 = 10))
        assertTrue(RivalGallery.summarize(otherModes, levels, easy).none { it.encountered })
    }
    @Test fun `results are separate by difficulty and never change campaign or independent sessions`() {
        val baseline = Profile(savedMatch = SavedMatch("level-0001", easy), localSession = SavedLocalSession())
        var result = RivalGallery.record(baseline, "sparky", easy, LevelOutcome.Won(3))
        result = RivalGallery.record(result, "sparky", easy, LevelOutcome.Lost("draw"))
        result = RivalGallery.record(result, "sparky", Difficulty.HARD, LevelOutcome.Lost("rival_won_best_of"))
        assertEquals(RivalRecord(wins = 1, draws = 1), RivalGallery.summarize(result, levels, easy).first().record)
        assertEquals(RivalRecord(losses = 1), RivalGallery.summarize(result, levels, Difficulty.HARD).first().record)
        assertEquals(baseline.copy(revision = result.revision, rivalRecords = result.rivalRecords), result)
        assertEquals(0, result.totalStars())
    }
    @Test fun `campaign challenges never select locked or missing levels`() {
        val p = ProfileReducer.recordWin(Profile(), 49, easy, 1, 5, 0)
        assertEquals(1, RivalGallery.summarize(p, levels, easy).first().challengeOrdinal)
        val completed = ProfileReducer.recordWin(ProfileReducer.recordWin(p, 1, easy, 1, 5, 0), 2, easy, 1, 5, 0)
        assertEquals(50, RivalGallery.summarize(completed, levels, easy).first().challengeOrdinal)
        assertTrue(RivalGallery.summarize(completed, emptyList(), easy).all { it.challengeOrdinal == null })
    }
    @Test fun `records survive backup and storage while campaign reset removes them`() {
        val dir = Files.createTempDirectory("rivals").toFile()
        try {
            assertTrue(Json.decodeFromString(Profile.serializer(), "{}").rivalRecords.isEmpty())
            val profile = RivalGallery.record(Profile(settings = Settings(colorPalette = "protanopia"), localSession = SavedLocalSession()), "sparky", easy, LevelOutcome.Won(2))
            val store = ProfileStore(dir); store.save(profile)
            assertEquals(profile, ProfileStore(dir).load())
            assertEquals(profile, ProfileBackup.decode(ProfileBackup.encode(profile)))
            val reset = store.resetProgress()
            assertTrue(reset.rivalRecords.isEmpty())
            assertEquals(profile.settings, reset.settings)
            assertEquals(profile.localSession, reset.localSession)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `campaign checkpoint persists both result counters and clears the saved journal`() {
        val saved = SavedMatch("level-0001", easy)
        val active = ProfileReducer.checkpoint(Profile(), 1, saved, null, 0, 0, "sparky")
        assertEquals(RivalRecord(), active.rivalRecords.getValue("sparky").getValue(easy))
        val resumed = ProfileReducer.checkpoint(active, 1, saved, null, 0, 0, "sparky")
        assertEquals(active.rivalRecords, resumed.rivalRecords)
        val won = ProfileReducer.checkpoint(resumed, 1, saved, LevelOutcome.Won(3), 4, 0, "sparky")
        assertNull(won.savedMatch)
        assertEquals(1L, won.stats(easy).wins)
        assertEquals(RivalRecord(wins = 1), won.rivalRecords.getValue("sparky").getValue(easy))
    }
    @Test fun `packaged metadata matches all variants and retains the actual rivals`() {
        val root = listOf(java.io.File("build/generated/neonAssets"), java.io.File("app/build/generated/neonAssets")).first { it.isDirectory }
        val repo = CatalogRepository(object : AssetSource {
            override fun readText(path: String) = java.io.File(root, path).takeIf { it.isFile }?.readText()
        })
        val actual = repo.rivalLevels()
        assertEquals(1_800, actual.size)
        assertEquals(setOf("sparky", "dolores", "flamingo", "strike", "projectionist", "centrifuge", "skater", "diva", "pixel", "wheel", "ace", "queen"), actual.map { it.rival }.toSet())
        assertEquals(36, actual.count { it.boss })
        assertEquals(setOf(50,100,150,200,250,300,350,400,450,500,550,600),actual.filter { it.boss }.map { it.ordinal }.toSet())
        val damaged = CatalogRepository(object : AssetSource {
            override fun readText(path: String) = if (path.endsWith("level-0001.json")) "{}" else java.io.File(root, path).takeIf { it.isFile }?.readText()
        })
        assertTrue(damaged.rivalLevels().isEmpty())
    }
    @Test fun `largest accepted future roster can encounter all supported rivals without losing data on reload`() {
        val dir = Files.createTempDirectory("rival-capacity").toFile()
        try {
            val imported = (0..64).map { count -> Profile(settings = Settings(sound = false, colorPalette = "tritanopia"),
                rivalRecords = (0 until count).associate { "future-$it" to mapOf(easy to RivalRecord(wins = 3)) }) }
                .last { Validator.isValid(it) }
            val originalRecords = imported.rivalRecords
            assertEquals(imported, RivalGallery.record(imported, "another-future", easy, LevelOutcome.Won(3)))
            val store = ProfileStore(dir)
            store.importProfile(ProfileBackup.decode(ProfileBackup.encode(imported)))
            for (rival in RivalGallery.roster) {
                val checkpoint = ProfileReducer.checkpoint(store.load(), 1, SavedMatch("level-0001", easy), null, 0, 0, rival.id)
                assertTrue("Campaign checkpoint must remain valid after adding ${rival.id}", Validator.isValid(checkpoint))
                store.save(checkpoint)
                val loaded = ProfileStore(dir).load()
                assertEquals(checkpoint, loaded)
                assertEquals(imported.settings, loaded.settings)
                originalRecords.forEach { (id, records) -> assertEquals(records, loaded.rivalRecords[id]) }
            }
            assertEquals(originalRecords.size + 12, store.load().rivalRecords.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `invalid imported counts and keys are rejected while unknown future rivals survive`() {
        assertFalse(Validator.isValid(Profile(rivalRecords = mapOf("sparky" to mapOf(easy to RivalRecord(wins = -1))))))
        assertFalse(Validator.isValid(Profile(rivalRecords = mapOf("" to mapOf(easy to RivalRecord())))))
        val future = Profile(rivalRecords = mapOf("future-rival" to mapOf(easy to RivalRecord(wins = 1))))
        assertTrue(Validator.isValid(future))
        assertEquals(future, ProfileBackup.decode(ProfileBackup.encode(future)))
    }
}
