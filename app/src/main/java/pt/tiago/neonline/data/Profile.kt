package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.Difficulty

const val CURRENT_PROFILE_SCHEMA = 1
const val CURRENT_CONTENT_VERSION = 58
const val CURRENT_ENGINE_VERSION = 2

/** Total levels of the campaign; each has three difficulties. */
const val CAMPAIGN_LEVELS = 600

/** Star totals that unlock the extra board themes. */
object ThemeUnlocks {
    const val SYNTHWAVE = 400
    const val CIRCUIT = 1_000
    const val AURORA = 1_800
}

@Serializable
data class Settings(
    val sound: Boolean = true,
    val soundVolume: Float = 0.8f,
    val vibration: Boolean = true,
    val symbolsOnPieces: Boolean = true,
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val colorPalette: String = "normal",
    val skin: String = "neon_road",
    val confirmMove: Boolean = false,
    val showPreview: Boolean = true,
    val aiSpeed: String = "normal",
    val lastDifficulty: Difficulty = Difficulty.EASY,
)

@Serializable
data class LevelBest(
    val stars: Int = 0,
    val fewestMoves: Int? = null,
    val hintsUsed: Int = 0,
)

@Serializable
data class DifficultyProgress(
    /** Variant ids ("level-0001:easy") the player has won at least once. */
    val completedVariantIds: Set<String> = emptySet(),
    val bestByVariantId: Map<String, LevelBest> = emptyMap(),
    /** Ordinals of the levels the player started; the next playable one is derived. */
    val highestReached: Int = 0,
)

@Serializable
data class DifficultyStats(
    val wins: Long = 0,
    val losses: Long = 0,
    val draws: Long = 0,
    val moves: Long = 0,
    val hintsUsed: Long = 0,
    val decisiveMovesReviewed: Long = 0,
    val timeMs: Long = 0,
    /** False for legacy counters that may omit moves or hints from losses and draws. */
    val allResultsTracked: Boolean = false,
)

@Serializable
data class Achievement(val id: String, val unlockedAtRevision: Long = 0)

/** A saved match: the level reference and the moves, replayed against the engine to restore it. */
@Serializable
data class SavedMatch(
    val levelId: String,
    val difficulty: Difficulty,
    val moves: List<String> = emptyList(),
    val formatVersion: Int = 1,
    val initialHash: String = "",
    val stateHash: String = "",
    val steps: List<SavedPly> = emptyList(),
    val hintsLeft: Int = 0,
    val undosLeft: Int = 0,
    val clockMs: Long? = null,
    val tutorialDismissed: Boolean = false,
    val bossHistory: List<SavedBossRound> = emptyList(),
    val peekCount: Int = 0,
    /** Active attempt duration; legacy journals have no recorded time. */
    val elapsedMs: Long = 0L,
)

/** Completed boss duels are kept as replay journals, never as trusted score counters. */
@Serializable
data class SavedBossRound(
    val initialHash: String,
    val stateHash: String,
    val steps: List<SavedPly>,
    val hintsLeft: Int,
    val undosLeft: Int,
)

@Serializable
data class SavedPly(
    val action: String,
    val column: Int? = null,
    val kind: pt.tiago.neonline.core.model.PieceKind = pt.tiago.neonline.core.model.PieceKind.NORMAL,
    val aiStateHex: String,
    val afterHash: String,
)

@Serializable
data class Profile(
    val schemaVersion: Int = CURRENT_PROFILE_SCHEMA,
    val contentVersion: Int = CURRENT_CONTENT_VERSION,
    val engineVersion: Int = CURRENT_ENGINE_VERSION,
    val revision: Long = 0,
    val settings: Settings = Settings(),
    val progressByDifficulty: Map<Difficulty, DifficultyProgress> = emptyMap(),
    val statsByDifficulty: Map<Difficulty, DifficultyStats> = emptyMap(),
    val achievements: List<Achievement> = emptyList(),
    val savedMatch: SavedMatch? = null,
    val localSession: SavedLocalSession? = null,
    val savedDaily: SavedDaily? = null,
    val dailyHistory: List<DailyRecord> = emptyList(),
    val rivalsBeaten: Set<String> = emptySet(),
    val savedFreePlay: SavedFreePlay? = null,
    val freePlayStats: Map<Difficulty, RivalRecord> = emptyMap(),
    val freePlayPeeks: Map<Difficulty, Long> = emptyMap(),
    val rivalRecords: Map<String, Map<Difficulty, RivalRecord>> = emptyMap(),
    val rushBest: Map<Difficulty, Int> = emptyMap(),
    val savedTournament: SavedTournament? = null,
    val tournamentBestRounds: Map<Difficulty, Int> = emptyMap(),
) {
    fun progress(difficulty: Difficulty): DifficultyProgress =
        progressByDifficulty[difficulty] ?: DifficultyProgress()

    fun stats(difficulty: Difficulty): DifficultyStats = statsByDifficulty[difficulty] ?: DifficultyStats()

    /** The level the Continue button opens: one past the highest reached, capped at the campaign. */
    fun continueOrdinal(difficulty: Difficulty): Int =
        (progress(difficulty).highestReached + 1).coerceIn(1, CAMPAIGN_LEVELS)

    /** Stars across the three difficulties, used by the theme unlocks. */
    fun totalStars(): Int = Difficulty.entries.sumOf { difficulty ->
        progress(difficulty).bestByVariantId.values.sumOf { it.stars }
    }

    fun unlockedSkin(): String = when {
        totalStars() >= ThemeUnlocks.AURORA -> "aurora"
        totalStars() >= ThemeUnlocks.CIRCUIT -> "circuit"
        totalStars() >= ThemeUnlocks.SYNTHWAVE -> "synthwave"
        else -> "neon_road"
    }
}

/** The pure rules of progression: no Android, no clock, easy to test. */
object ProfileReducer {

    /** Completion and clearing the active match must be persisted as a single profile transaction. */
    fun checkpoint(
        profile: Profile,
        ordinal: Int,
        saved: SavedMatch,
        outcome: pt.tiago.neonline.core.objectives.LevelOutcome?,
        humanMoves: Int,
        hintsUsed: Int,
        rival: String? = null,
    ): Profile {
        require(saved.elapsedMs >= 0L)
        val updated = when (outcome) {
            is pt.tiago.neonline.core.objectives.LevelOutcome.Won -> recordWin(profile, ordinal, saved.difficulty, outcome.stars, humanMoves, hintsUsed)
            is pt.tiago.neonline.core.objectives.LevelOutcome.Lost -> if (outcome.reason == "draw") recordDraw(profile, saved.difficulty, humanMoves, hintsUsed)
                else recordLoss(profile, saved.difficulty, humanMoves, hintsUsed)
            null -> profile
        }
        val timed = if (outcome != null && saved.elapsedMs > 0L) {
            val stats = updated.stats(saved.difficulty)
            updated.copy(statsByDifficulty = updated.statsByDifficulty + (saved.difficulty to stats.copy(
                timeMs = stats.timeMs + minOf(saved.elapsedMs, Long.MAX_VALUE - stats.timeMs))))
        } else updated
        val tracked = if (rival != null) RivalGallery.record(timed, rival, saved.difficulty, outcome) else timed
        val beaten = if (rival != null && ordinal % 50 == 0 && outcome is pt.tiago.neonline.core.objectives.LevelOutcome.Won)
            tracked.copy(rivalsBeaten = tracked.rivalsBeaten + rival) else tracked
        return withSavedMatch(beaten, saved.takeIf { outcome == null })
    }

    fun variantId(levelOrdinal: Int, difficulty: Difficulty): String =
        "level-%04d".format(levelOrdinal) + ":" + difficulty.name.lowercase()

    /**
     * Records a finished level: the best stars, the unlock of the next level (a win with one star
     * is enough) and the statistics. A loss never lowers the recorded best.
     */
    fun recordWin(
        profile: Profile,
        levelOrdinal: Int,
        difficulty: Difficulty,
        stars: Int,
        moves: Int,
        hintsUsed: Int,
    ): Profile {
        val variant = variantId(levelOrdinal, difficulty)
        val progress = profile.progress(difficulty)
        val best = progress.bestByVariantId[variant]
        val newBest = LevelBest(
            stars = maxOf(best?.stars ?: 0, stars),
            fewestMoves = minOf(best?.fewestMoves ?: Int.MAX_VALUE, moves).takeIf { it != Int.MAX_VALUE },
            hintsUsed = minOf(best?.hintsUsed ?: Int.MAX_VALUE, hintsUsed).takeIf { it != Int.MAX_VALUE } ?: 0,
        )
        val newProgress = progress.copy(
            completedVariantIds = progress.completedVariantIds + variant,
            bestByVariantId = progress.bestByVariantId + (variant to newBest),
            highestReached = maxOf(progress.highestReached, levelOrdinal),
        )
        val stats = profile.stats(difficulty)
        val updated = profile.copy(
            revision = profile.revision + 1,
            progressByDifficulty = profile.progressByDifficulty + (difficulty to newProgress),
            statsByDifficulty = profile.statsByDifficulty + (
                difficulty to stats.copy(
                    wins = stats.wins + 1,
                    moves = stats.moves + moves,
                    hintsUsed = stats.hintsUsed + hintsUsed,
                    allResultsTracked = countsWereComplete(stats),
                )
                ),
        )
        return CampaignAchievements.reconcile(updated, advanceRevision = false)
    }

    fun recordLoss(profile: Profile, difficulty: Difficulty, moves: Int, hintsUsed: Int = 0): Profile {
        val stats = profile.stats(difficulty)
        return profile.copy(
            revision = profile.revision + 1,
            statsByDifficulty = profile.statsByDifficulty + (
                difficulty to stats.copy(losses = stats.losses + 1, moves = stats.moves + moves, hintsUsed = stats.hintsUsed + hintsUsed, allResultsTracked = countsWereComplete(stats))
                ),
        )
    }

    fun recordDraw(profile: Profile, difficulty: Difficulty, moves: Int = 0, hintsUsed: Int = 0): Profile {
        val stats = profile.stats(difficulty)
        return profile.copy(
            revision = profile.revision + 1,
            statsByDifficulty = profile.statsByDifficulty + (difficulty to stats.copy(draws = stats.draws + 1, moves = stats.moves + moves, hintsUsed = stats.hintsUsed + hintsUsed, allResultsTracked = countsWereComplete(stats))),
        )
    }

    private fun countsWereComplete(stats: DifficultyStats): Boolean =
        stats.allResultsTracked || (stats.losses == 0L && stats.draws == 0L)

    /** A level unlocks the next one with at least one star in that difficulty. */
    fun isUnlocked(profile: Profile, levelOrdinal: Int, difficulty: Difficulty): Boolean {
        if (levelOrdinal <= 1) return true
        val previous = variantId(levelOrdinal - 1, difficulty)
        return profile.progress(difficulty).completedVariantIds.contains(previous)
    }

    /** The boss of a stop unlocks the next stop (level 50, 100, ... of each difficulty). */
    fun isStopUnlocked(profile: Profile, stop: Int, difficulty: Difficulty): Boolean {
        if (stop <= 1) return true
        val bossOrdinal = (stop - 1) * 50
        return profile.progress(difficulty).completedVariantIds.contains(variantId(bossOrdinal, difficulty))
    }

    fun withSavedMatch(profile: Profile, saved: SavedMatch?): Profile =
        profile.copy(revision = profile.revision + 1, savedMatch = saved)

    /** Apply pause controls to the latest profile without replacing unrelated settings or journals. */
    fun withFeedbackSettings(profile: Profile, sound: Boolean? = null, vibration: Boolean? = null): Profile {
        val settings = profile.settings.copy(sound = sound ?: profile.settings.sound,
            vibration = vibration ?: profile.settings.vibration)
        return if (settings == profile.settings) profile else withSettings(profile, settings)
    }

    fun withSettings(profile: Profile, settings: Settings): Profile =
        profile.copy(revision = profile.revision + 1, settings = settings)
}
