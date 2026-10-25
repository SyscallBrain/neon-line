package pt.tiago.neonline.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty

class PauseFeedbackSettingsTest {
    @Test fun `individual pause changes retain latest preferences journal and progress`() {
        val initial = Profile(settings = Settings(soundVolume = .35f, reducedMotion = true,
            confirmMove = true, lastDifficulty = Difficulty.HARD))
        val muted = ProfileReducer.withFeedbackSettings(initial, sound = false)
        val checkpointed = ProfileReducer.recordWin(muted, 1, Difficulty.HARD, 3, 8, 0)
            .copy(savedMatch = SavedMatch(levelId = "level-0002", difficulty = Difficulty.HARD, stateHash = "latest"))
        val quiet = ProfileReducer.withFeedbackSettings(checkpointed, vibration = false)
        assertEquals(checkpointed.copy(revision = checkpointed.revision + 1,
            settings = checkpointed.settings.copy(vibration = false)), quiet)
        assertEquals(initial.settings.copy(sound = false, vibration = false), quiet.settings)
        assertEquals(quiet.settings.copy(sound = true), ProfileReducer.withFeedbackSettings(quiet, sound = true).settings)
        assertEquals(quiet.settings.copy(vibration = true), ProfileReducer.withFeedbackSettings(quiet, vibration = true).settings)
    }

    @Test fun `unchanged pause preferences do not create a revision`() {
        val profile = Profile(revision = 25, settings = Settings(sound = false))
        assertSame(profile, ProfileReducer.withFeedbackSettings(profile))
        assertSame(profile, ProfileReducer.withFeedbackSettings(profile, sound = false))
        assertSame(profile, ProfileReducer.withFeedbackSettings(profile, vibration = true))
    }

    @Test fun `pause preferences persist and backup preserves the entire current profile`() {
        val directory = Files.createTempDirectory("neonline-pause-feedback").toFile()
        try {
            val progressed = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 3, 8, 0)
            val profile = progressed.copy(settings = progressed.settings.copy(soundVolume = .35f, colorPalette = "deuteranopia"),
                savedMatch = SavedMatch(levelId = "level-0002", difficulty = Difficulty.EASY))
            val changed = ProfileReducer.withFeedbackSettings(profile, sound = false, vibration = false)
            assertEquals(profile.copy(revision = profile.revision + 1,
                settings = profile.settings.copy(sound = false, vibration = false)), changed)
            ProfileStore(directory).save(changed)
            assertEquals(changed, ProfileStore(directory).load())
            assertEquals(changed, ProfileBackup.decode(ProfileBackup.encode(changed)))
        } finally { directory.deleteRecursively() }
    }
}
