package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json

class ProfileBackupTest {
    @Test fun `campaign content expansion has its own profile content version`() {
        assertEquals(58, CURRENT_CONTENT_VERSION)
        assertEquals("1.04.0", pt.tiago.neonline.BuildConfig.VERSION_NAME)
        assertEquals(115, pt.tiago.neonline.BuildConfig.VERSION_CODE)
    }

    private fun populated(): Profile = ProfileReducer.recordWin(Profile(), 2, Difficulty.HARD, 3, 4, 1).copy(
        settings = Settings(soundVolume = .3f, largerText = true, lastDifficulty = Difficulty.HARD),
        savedMatch = SavedMatch("level-0003", Difficulty.HARD, steps = listOf(SavedPly("drop", 2, aiStateHex = "abc", afterHash = "hash")),
            bossHistory = listOf(SavedBossRound("start", "end", emptyList(), 1, 0))),
    )
    private fun rejected(bytes: ByteArray) { assertTrue(runCatching { ProfileBackup.decode(bytes) }.isFailure) }

    @Test fun `round trip retains all progress settings statistics and journals`() {
        val expected = populated()
        assertEquals(expected, ProfileBackup.read(ByteArrayInputStream(ProfileBackup.encode(expected))))
    }
    @Test fun `legacy profile json defaults tournament fields and backup preserves tournament`() {
        val legacy = Json.decodeFromString(Profile.serializer(), "{}")
        assertNull(legacy.savedTournament)
        assertTrue(legacy.tournamentBestRounds.isEmpty())

        val tournament = Tournament.create(
            listOf("sparky", "dolores", "flamingo", "strike", "projectionist", "centrifuge", "skater", "diva"),
            RivalGallery.roster.take(8).map { it.id }.toSet(), Difficulty.MEDIUM, "1111111111111111", listOf(FreePlayRules()),
        )
        val expected = Profile(savedTournament = tournament, tournamentBestRounds = mapOf(Difficulty.EASY to 4), rivalsBeaten = tournament.bracket.toSet())
        assertEquals(expected, ProfileBackup.decode(ProfileBackup.encode(expected)))
    }
    @Test fun `invalid tournament seed and best-round counters cannot enter a profile`() {
        val tournament = Tournament.create(
            listOf("sparky", "dolores", "flamingo", "strike", "projectionist", "centrifuge", "skater", "diva"),
            RivalGallery.roster.take(8).map { it.id }.toSet(), Difficulty.MEDIUM, "2222222222222222", listOf(FreePlayRules()),
        )
        assertTrue(runCatching { ProfileBackup.encode(Profile(savedTournament = tournament.copy(seedHex = "invalid"))) }.isFailure)
        assertTrue(runCatching { ProfileBackup.encode(Profile(tournamentBestRounds = mapOf(Difficulty.HARD to 9))) }.isFailure)
    }
    @Test fun `campaign boss victory records a defeated rival for tournament eligibility`() {
        val saved = SavedMatch("level-0050", Difficulty.HARD)

        val won = ProfileReducer.checkpoint(Profile(), 50, saved, pt.tiago.neonline.core.objectives.LevelOutcome.Won(1), 12, 0, "sparky")
        val lost = ProfileReducer.checkpoint(Profile(), 50, saved, pt.tiago.neonline.core.objectives.LevelOutcome.Lost("rival"), 12, 0, "dolores")

        assertEquals(setOf("sparky"), won.rivalsBeaten)
        assertTrue(lost.rivalsBeaten.isEmpty())
    }
    @Test fun `checksum detects edited payload`() {
        val bytes = ProfileBackup.encode(populated()).toString(Charsets.UTF_8).replace("level-0003", "level-0004").toByteArray()
        rejected(bytes)
    }
    @Test fun `truncated file and arbitrary json are rejected`() {
        val bytes = ProfileBackup.encode(populated())
        rejected(bytes.copyOf(bytes.size - 10))
        rejected("{}".toByteArray())
        rejected(Json.encodeToString(Profile.serializer(), Profile()).toByteArray())
    }
    @Test fun `wrong app and future backup version are rejected`() {
        val text = ProfileBackup.encode(Profile()).toString(Charsets.UTF_8)
        rejected(text.replace("NeonLine", "OtherGame").toByteArray())
        rejected(text.replace("\"version\":1", "\"version\":2").toByteArray())
    }
    @Test fun `bounded input rejects oversized file`() {
        assertTrue(runCatching { ProfileBackup.read(ByteArrayInputStream(ByteArray(ProfileBackup.MAX_BYTES + 1))) }.isFailure)
    }
    @Test fun `future and negative profile versions cannot be exported`() {
        for (profile in listOf(Profile(schemaVersion = 2), Profile(engineVersion = 3), Profile(contentVersion = CURRENT_CONTENT_VERSION + 1), Profile(schemaVersion = -1), Profile(revision = -1))) {
            assertTrue(runCatching { ProfileBackup.encode(profile) }.isFailure)
        }
    }
    @Test fun `negative best counters and variant belonging to another difficulty are rejected`() {
        val wrong = DifficultyProgress(bestByVariantId = mapOf("level-0001:hard" to LevelBest(3)))
        assertTrue(runCatching { ProfileBackup.encode(Profile(progressByDifficulty = mapOf(Difficulty.EASY to wrong))) }.isFailure)
        val negative = DifficultyProgress(bestByVariantId = mapOf("level-0001:easy" to LevelBest(3, -1)))
        assertTrue(runCatching { ProfileBackup.encode(Profile(progressByDifficulty = mapOf(Difficulty.EASY to negative))) }.isFailure)
    }
    @Test fun `preview and invalid import leave current files untouched`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val store = ProfileStore(dir)
            val current = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 2, 3, 0)
            store.save(current)
            val before = File(dir, ProfileStore.PROFILE_FILE).readBytes()
            ProfileBackup.decode(ProfileBackup.encode(populated()))
            rejected("bad".toByteArray())
            assertArrayEquals(before, File(dir, ProfileStore.PROFILE_FILE).readBytes())
            assertFalse(File(dir, ProfileStore.BEFORE_IMPORT_FILE).exists())
            assertEquals(current, store.load())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `confirmed import replaces rather than merges and retains pre-import profile`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val store = ProfileStore(dir)
            val previous = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 1, 8, 0)
            store.save(previous)
            val candidate = populated()
            store.importProfile(candidate)
            val restored = ProfileStore(dir).load()
            assertEquals(candidate.progressByDifficulty, restored.progressByDifficulty)
            assertEquals(candidate.savedMatch, restored.savedMatch)
            assertEquals(candidate.settings, restored.settings)
            assertEquals(maxOf(previous.revision, candidate.revision) + 1, restored.revision)
            assertEquals(previous, store.decode(File(dir, ProfileStore.BEFORE_IMPORT_FILE).readText()))
            assertEquals(restored, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `invalid candidate is refused before any store mutation`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val store = ProfileStore(dir)
            val previous = Profile(revision = 5)
            store.save(previous)
            assertTrue(runCatching { store.importProfile(Profile(schemaVersion = 20)) }.isFailure)
            assertEquals(previous, store.load())
            assertFalse(File(dir, ProfileStore.BEFORE_IMPORT_FILE).exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `failed main commit leaves current profile and restores recovery`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val store = ProfileStore(dir)
            val previous = Profile(revision = 8)
            store.save(previous)
            // Make the temporary main filename unwritable without relying on Unix permissions/root.
            File(dir, ProfileStore.TEMP_FILE).mkdir()
            assertTrue(runCatching { store.importProfile(populated()) }.isFailure)
            assertEquals(previous, store.load())
            assertEquals(previous, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `legacy supported profile keeps stars while versions migrate on import`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val profile = ProfileReducer.recordWin(Profile(engineVersion = 1, contentVersion = 1), 10, Difficulty.MEDIUM, 2, 5, 0)
            val store = ProfileStore(dir)
            store.importProfile(ProfileBackup.decode(ProfileBackup.encode(profile)))
            assertEquals(profile.totalStars(), store.load().totalStars())
            assertEquals(CURRENT_ENGINE_VERSION, store.load().engineVersion)
            assertEquals(CURRENT_CONTENT_VERSION, store.load().contentVersion)
        } finally { dir.deleteRecursively() }
    }
}
