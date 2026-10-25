package pt.tiago.neonline.game

import pt.tiago.neonline.data.Settings

/** System ringer policy and user preferences are independent of sound resource loading. */
data class FeedbackPolicy(val volume: Float, val vibration: Boolean) {
    companion object {
        fun resolve(settings: Settings, audible: Boolean, silent: Boolean) = FeedbackPolicy(
            if (settings.sound && audible) settings.soundVolume.coerceIn(0f, 1f) else 0f,
            settings.vibration && !silent,
        )
    }
}
