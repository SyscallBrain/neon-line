package pt.tiago.neonline.data

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class SettingsPersistenceTest {
    @Test fun `legacy profiles retain their sound level and new settings survive a fresh store`() {
        val old = Json.decodeFromString(Profile.serializer(), "{\"settings\":{\"sound\":false}}")
        assertFalse(old.settings.sound)
        assertEquals(0.8f, old.settings.soundVolume)
        val directory = Files.createTempDirectory("neonline-settings").toFile()
        try {
            val progress = ProfileReducer.recordWin(old, 2, Difficulty.HARD, 3, 8, 0)
            val settings = Settings(soundVolume = 0.35f, vibration = false, confirmMove = true, showPreview = false,
                reducedMotion = true, largerText = true, symbolsOnPieces = false, aiSpeed = "fast", lastDifficulty = Difficulty.HARD)
            ProfileStore(directory).save(ProfileReducer.withSettings(progress, settings))
            val restored = ProfileStore(directory).load()
            assertEquals(settings, restored.settings)
            assertEquals(progress.progressByDifficulty, restored.progressByDifficulty)
            assertEquals(progress.statsByDifficulty, restored.statsByDifficulty)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `invalid sound levels and response speeds are refused`() {
        assertFalse(Validator.isValid(Profile(settings = Settings(soundVolume = Float.NaN))))
        assertFalse(Validator.isValid(Profile(settings = Settings(soundVolume = 1.1f))))
        assertFalse(Validator.isValid(Profile(settings = Settings(aiSpeed = "unknown"))))
        assertTrue(Validator.isValid(Profile(settings = Settings(soundVolume = 0f, aiSpeed = "fast"))))
    }
}
