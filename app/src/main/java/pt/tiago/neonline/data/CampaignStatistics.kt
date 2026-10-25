package pt.tiago.neonline.data

import pt.tiago.neonline.core.model.Difficulty

/** Progress counts unique packaged levels; result counters include replayed attempts. */
data class CampaignSummary(
    val availableLevels: Int,
    val completedLevels: Int,
    val threeStarLevels: Int,
    val stars: Int,
    val stats: DifficultyStats,
) {
    val maxStars: Int get() = availableLevels * 3
    val starProgress: Float get() = if (maxStars == 0) 0f else stars.toFloat() / maxStars
    val winRate: Double? get() {
        val results = stats.wins.toDouble() + stats.losses + stats.draws
        return if (results == 0.0) null else stats.wins * 100.0 / results
    }
    val hasLegacyGaps: Boolean get() = !stats.allResultsTracked && (stats.losses > 0 || stats.draws > 0)
}

object CampaignStatistics {
    fun summarize(profile: Profile, levels: List<CatalogLevel>, difficulty: Difficulty): CampaignSummary {
        val ids = levels.map { ProfileReducer.variantId(it.ordinal, difficulty) }.toSet()
        val progress = profile.progress(difficulty)
        val best = progress.bestByVariantId.filterKeys { it in ids }.values
        return CampaignSummary(
            availableLevels = ids.size,
            completedLevels = progress.completedVariantIds.count { it in ids },
            threeStarLevels = best.count { it.stars == 3 },
            stars = best.sumOf { it.stars },
            stats = profile.stats(difficulty),
        )
    }

    fun levelsToImprove(profile: Profile, levels: List<CatalogLevel>, difficulty: Difficulty): List<CatalogLevel> =
        levels.filter { level -> ProfileReducer.isUnlocked(profile, level.ordinal, difficulty) &&
            (profile.progress(difficulty).bestByVariantId[ProfileReducer.variantId(level.ordinal, difficulty)]?.stars ?: 0) < 3
        }.sortedBy { it.ordinal }
}
