package pt.tiago.neonline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("neonline-profile").toFile()

    @Test
    fun `a round trip keeps the profile`() {
        val dir = tempDir()
        val store = ProfileStore(dir)
        var profile = ProfileReducer.recordWin(Profile(), 3, pt.tiago.neonline.core.model.Difficulty.EASY, 2, 11, 1)
        profile = ProfileReducer.withSettings(profile, profile.settings.copy(symbolsOnPieces = false))
        store.save(profile)
        val loaded = store.load()
        assertEquals(profile.progress(profile.settings.lastDifficulty).completedVariantIds, loaded.progress(pt.tiago.neonline.core.model.Difficulty.EASY).completedVariantIds)
        assertFalse(loaded.settings.symbolsOnPieces)
    }

    @Test
    fun `an empty directory starts a fresh profile`() {
        val store = ProfileStore(tempDir())
        assertEquals(Profile(), store.load())
    }

    @Test
    fun `an unreadable profile is set aside, not overwritten`() {
        val dir = tempDir()
        File(dir, ProfileStore.PROFILE_FILE).writeText("{ not json at all")
        val store = ProfileStore(dir)
        assertEquals(Profile(), store.load())
        val quarantined = dir.listFiles { file -> file.name.startsWith(ProfileStore.CORRUPT_PREFIX) }!!
        assertEquals(1, quarantined.size)
    }

    @Test
    fun `a write leaves a recovery copy`() {
        val dir = tempDir()
        ProfileStore(dir).save(Profile().copy(revision = 7))
        val recovery = File(dir, ProfileStore.RECOVERY_FILE)
        assertTrue(recovery.isFile)
        assertEquals(7, ProfileStore(dir).load().revision)
    }

    @Test
    fun `the validator rejects a profile from the future`() {
        val store = ProfileStore(tempDir())
        val future = Profile().copy(schemaVersion = CURRENT_PROFILE_SCHEMA + 5)
        assertNull(store.decode(kotlinx.serialization.json.Json.encodeToString(Profile.serializer(), future)))
        assertNotNull(store.decode(kotlinx.serialization.json.Json.encodeToString(Profile.serializer(), Profile())))
        assertTrue(Validator.isValid(Profile()))
        assertFalse(Validator.isValid(Profile().copy(revision = -1)))
    }
    @Test
    fun `damaged main profile restores progress from recovery`() {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val expected = ProfileReducer.recordWin(Profile(), 1, pt.tiago.neonline.core.model.Difficulty.HARD, 3, 5, 0)
        store.save(expected)
        File(dir, ProfileStore.PROFILE_FILE).writeText("{broken")
        assertEquals(expected, store.load())
        assertEquals(expected, store.load())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith(ProfileStore.CORRUPT_PREFIX) })
    }
    @Test
    fun `content update preserves existing completed levels and stars`() {
        val dir = tempDir()
        val store = ProfileStore(dir)
        val old = ProfileReducer.recordWin(Profile(contentVersion = 1, engineVersion = 1), 12, pt.tiago.neonline.core.model.Difficulty.HARD, 3, 4, 0)
        File(dir, ProfileStore.PROFILE_FILE).writeText(kotlinx.serialization.json.Json.encodeToString(Profile.serializer(), old))
        val loaded = store.load()
        store.save(loaded)
        val updated = store.load()
        assertEquals(CURRENT_CONTENT_VERSION, updated.contentVersion)
        assertEquals(CURRENT_ENGINE_VERSION, updated.engineVersion)
        assertEquals(old.progressByDifficulty, updated.progressByDifficulty)
        assertEquals(old.totalStars(), updated.totalStars())
        dir.deleteRecursively()
    }
}
