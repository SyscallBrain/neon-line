package pt.tiago.neonline.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.AudioManager
import pt.tiago.neonline.data.Settings
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import pt.tiago.neonline.R
import pt.tiago.neonline.core.model.GameEvent
import pt.tiago.neonline.core.model.MatchOutcome

/**
 * Sound effects and haptics. No music: every effect is synthesized offline
 * by tools/generate_sounds.py and played from a SoundPool. The volume and the vibration respect
 * the settings and the phone's silent mode.
 */
class AudioController(private val context: Context) {

    private var settings = Settings()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private fun policy() = FeedbackPolicy.resolve(settings,
        audible = audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
        silent = audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT)

    fun configure(value: Settings) { settings = value }

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Vibrator::class.java)
            manager?.takeIf { it.hasVibrator() }
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val sounds = mapOf(
        "drop" to R.raw.drop,
        "bounce" to R.raw.bounce,
        "buzz1" to R.raw.buzz1,
        "buzz2" to R.raw.buzz2,
        "buzz3" to R.raw.buzz3,
        "buzz4" to R.raw.buzz4,
        "pop" to R.raw.pop,
        "rotate" to R.raw.rotate,
        "bomb" to R.raw.bomb,
        "lock" to R.raw.lock,
        "wild" to R.raw.wild,
        "tick" to R.raw.tick,
        "hint" to R.raw.hint,
        "undo" to R.raw.undo,
        "star1" to R.raw.star1,
        "star2" to R.raw.star2,
        "star3" to R.raw.star3,
        "win" to R.raw.win,
        "lose" to R.raw.lose,
        "draw" to R.raw.draw,
        "sign_lit" to R.raw.sign_lit,
        "click" to R.raw.click,
    )

    private val loaded = HashMap<String, Int>()
    init {
        sounds.forEach { (name, res) ->
            loaded[name] = pool.load(context, res, 1)
        }
    }

    fun play(name: String) {
        val volume = policy().volume
        if (volume <= 0f) return
        val id = loaded[name] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    /** Maps one move's events to the effects they describe. */
    fun playEvents(events: List<GameEvent>) {
        var buzzStep = 0
        for (event in events) {
            when (event) {
                is GameEvent.Dropped -> play("drop")
                is GameEvent.Popped -> play("pop")
                is GameEvent.Shifted -> play("bounce")
                is GameEvent.Rotated -> play("rotate")
                is GameEvent.BombExploded -> play("bomb")
                is GameEvent.ColumnLocked -> play("lock")
                is GameEvent.LineFormed -> {
                    play("buzz${(buzzStep++ % 4) + 1}")
                    vibrate(double = true)
                }
                is GameEvent.MatchOver -> playForOutcome(event.outcome)
                is GameEvent.MoveRejected -> play("click")
                is GameEvent.TimeUpPlayed -> play("tick")
                else -> Unit
            }
        }
    }

    fun playForOutcome(outcome: MatchOutcome) {
        when (outcome) {
            is MatchOutcome.Win -> {
                play("win")
                repeat(3) { index -> play("star${index + 1}") }
            }
            is MatchOutcome.Draw -> play("draw")
        }
    }

    fun lineCell(index: Int) {
        play("buzz${index % 4 + 1}")
        if (index == 0) vibrate(double = true)
    }

    fun playRound(outcome: MatchOutcome) {
        play(when(outcome) { is MatchOutcome.Win -> if(outcome.player == pt.tiago.neonline.core.model.Player.P1) "win" else "lose"; is MatchOutcome.Draw -> "draw" })
    }

    fun playResult(outcome: pt.tiago.neonline.core.objectives.LevelOutcome, boss: Boolean = false) {
        when (outcome) {
            is pt.tiago.neonline.core.objectives.LevelOutcome.Won -> {
                play("win")
                repeat(outcome.stars) { play("star${it + 1}") }
                if (boss) vibrate(long = true)
            }
            is pt.tiago.neonline.core.objectives.LevelOutcome.Lost -> play(if (outcome.reason == "draw") "draw" else "lose")
        }
    }

    /** The last three seconds of a per-move clock tick. */
    fun tick() = play("tick")

    fun hint() = play("hint")

    fun release() {
        pool.release()
    }

    private fun vibrate(short: Boolean = false, double: Boolean = false, long: Boolean = false) {
        if (!policy().vibration || vibrator == null) return
        val duration = if (long) 240L else if (short) 20L else 40L
        val pattern = if (double) longArrayOf(0, duration, 60, duration) else longArrayOf(0, duration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
