package pt.tiago.neonline.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import pt.tiago.neonline.R
import pt.tiago.neonline.data.Settings

/** Shares the settings screen's labelled switches without leaving or resuming the paused game. */
@Composable
internal fun PauseFeedbackControls(settings: Settings, onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit) {
    Column {
        SettingsToggle(R.string.settings_sound, settings.sound, onSoundChange)
        SettingsToggle(R.string.settings_vibration, settings.vibration, onVibrationChange)
    }
}
