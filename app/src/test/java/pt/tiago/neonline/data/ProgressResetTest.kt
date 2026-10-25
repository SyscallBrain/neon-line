package pt.tiago.neonline.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class ProgressResetTest {
    private fun populated(): Profile {
        var profile = Profile()
        Difficulty.entries.forEach { profile = ProfileReducer.recordWin(profile, 1, it, 3, 4, 1) }
        return profile.copy(settings = Settings(soundVolume = .2f, reducedMotion = true, lastDifficulty = Difficulty.HARD),
            achievements = listOf(Achievement("first-win")), rivalsBeaten = setOf("flamingo"),
            savedMatch = SavedMatch("level-0002", Difficulty.HARD))
    }
    private fun inStore(block: (ProfileStore, File, Profile) -> Unit) {
        val dir = Files.createTempDirectory("progress-reset").toFile()
        try { val store = ProfileStore(dir); val previous = populated(); store.save(previous); block(store, dir, previous) }
        finally { dir.deleteRecursively() }
    }
    @Test fun `reset clears all difficulties journals and meta but keeps every setting`() = inStore { store, dir, previous ->
        val reset = store.resetProgress()
        assertEquals(previous.settings, reset.settings)
        assertEquals(0, reset.totalStars())
        assertTrue(reset.progressByDifficulty.isEmpty())
        assertTrue(reset.statsByDifficulty.isEmpty())
        assertTrue(reset.achievements.isEmpty())
        assertTrue(reset.rivalsBeaten.isEmpty())
        assertNull(reset.savedMatch)
        assertTrue(Difficulty.entries.all { reset.continueOrdinal(it) == 1 })
        assertEquals(previous.revision + 1, reset.revision)
        assertEquals(reset, ProfileStore(dir).load())
        assertEquals(reset, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
    }
    @Test fun `previous progress is preserved separately from import recovery`() = inStore { store, dir, previous ->
        File(dir, ProfileStore.BEFORE_IMPORT_FILE).writeText("import copy")
        store.resetProgress()
        assertEquals(previous, store.decode(File(dir, ProfileStore.BEFORE_RESET_FILE).readText()))
        assertEquals("import copy", File(dir, ProfileStore.BEFORE_IMPORT_FILE).readText())
    }
    @Test fun `exported backup restores original progress after reset`() = inStore { store, _, previous ->
        val backup = ProfileBackup.encode(previous)
        store.resetProgress()
        store.importProfile(ProfileBackup.decode(backup))
        val restored = store.load()
        assertEquals(previous.progressByDifficulty, restored.progressByDifficulty)
        assertEquals(previous.statsByDifficulty, restored.statsByDifficulty)
        assertEquals(previous.savedMatch, restored.savedMatch)
        assertEquals(previous.settings, restored.settings)
    }
    @Test fun `failed reset commit leaves main and recovery unchanged`() = inStore { store, dir, previous ->
        File(dir, ProfileStore.TEMP_FILE).mkdir()
        assertTrue(runCatching { store.resetProgress() }.isFailure)
        assertEquals(previous, store.load())
        assertEquals(previous, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
    }
    @Test fun `reset cannot proceed if previous progress cannot be preserved`() = inStore { store, dir, previous ->
        File(dir, "${ProfileStore.BEFORE_RESET_FILE}.tmp").mkdir()
        assertTrue(runCatching { store.resetProgress() }.isFailure)
        assertEquals(previous, store.load())
        assertEquals(previous, store.decode(File(dir, ProfileStore.RECOVERY_FILE).readText()))
    }
    @Test fun `settings saved after opening reset screen are retained`() = inStore { store, _, previous ->
        val updated = previous.copy(settings = previous.settings.copy(largerText = true, confirmMove = true))
        store.save(updated)
        assertEquals(updated.settings, store.resetProgress().settings)
    }
}
