package pt.tiago.neonline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfileStoreBackgroundTest {

    private fun tempDir(): File = Files.createTempDirectory("neonline-profile-bg").toFile()

    @Test
    fun `a background save is visible at once and on disk after flush`() {
        val dir = tempDir()
        val store = ProfileStore(dir, background = true)
        store.save(Profile().copy(revision = 41))
        assertEquals(41, store.load().revision)
        store.flush()
        assertEquals(41, ProfileStore(dir).load().revision)
        assertEquals(41, ProfileStore(dir).decode(File(dir, ProfileStore.RECOVERY_FILE).readText())!!.revision)
    }

    @Test
    fun `the last of many saves wins`() {
        val dir = tempDir()
        val store = ProfileStore(dir, background = true)
        repeat(200) { store.save(Profile().copy(revision = it.toLong())) }
        store.flush()
        assertEquals(199, ProfileStore(dir).load().revision)
    }

    @Test
    fun `reads come from memory and reconcile once`() {
        val store = ProfileStore(tempDir(), background = true)
        val first = store.reconcileAchievements()
        assertSame(first, store.reconcileAchievements())
        assertSame(store.load(), store.reconcileAchievements())
    }

    @Test
    fun `an import is not overwritten by an older queued save`() {
        val dir = tempDir()
        val store = ProfileStore(dir, background = true)
        store.save(Profile().copy(revision = 3))
        val imported = ProfileReducer.recordWin(Profile(), 1, pt.tiago.neonline.core.model.Difficulty.EASY, 3, 5, 0)
        store.importProfile(imported)
        store.flush()
        val onDisk = ProfileStore(dir).load()
        assertEquals(store.load(), onDisk)
        assertEquals(imported.progressByDifficulty, onDisk.progressByDifficulty)
    }
}
