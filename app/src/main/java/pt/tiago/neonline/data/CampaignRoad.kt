package pt.tiago.neonline.data

import pt.tiago.neonline.core.model.Difficulty

enum class RoadStopStatus { OPEN, LOCKED, UNAVAILABLE }

data class RoadStop(val number: Int, val availableLevels: Int, val litSegments: Set<Int>, val stars: Int,
    val status: RoadStopStatus, val current: Boolean = false) {
    val canOpen: Boolean get() = status == RoadStopStatus.OPEN
    val maxStars: Int get() = availableLevels * 3
}

/** The catalog controls availability; only completed variants light the fifty numbered tubes. */
object CampaignRoad {
    fun levelsForStop(levels: List<CatalogLevel>, stop: Int): List<CatalogLevel> = if (stop !in 1..12) emptyList() else
        levels.filter { it.ordinal in ((stop - 1) * 50 + 1)..(stop * 50) }.distinctBy { it.ordinal }.sortedBy { it.ordinal }

    fun summarize(profile: Profile, levels: List<CatalogLevel>, difficulty: Difficulty, currentOrdinal: Int): List<RoadStop> {
        val progress = profile.progress(difficulty)
        val stops = (1..12).map { number ->
            val published = levelsForStop(levels, number)
            RoadStop(number, published.size,
                published.filter { ProfileReducer.variantId(it.ordinal, difficulty) in progress.completedVariantIds }
                    .map { (it.ordinal - 1) % 50 }.toSet(),
                published.sumOf { progress.bestByVariantId[ProfileReducer.variantId(it.ordinal, difficulty)]?.stars ?: 0 },
                when {
                    published.isEmpty() -> RoadStopStatus.UNAVAILABLE
                    !ProfileReducer.isStopUnlocked(profile, number, difficulty) -> RoadStopStatus.LOCKED
                    else -> RoadStopStatus.OPEN
                })
        }
        val requested = if (currentOrdinal in 1..600) (currentOrdinal - 1) / 50 + 1 else null
        val current = stops.firstOrNull { it.number == requested && it.canOpen } ?: stops.firstOrNull { it.canOpen }
        return stops.map { it.copy(current = it.number == current?.number) }
    }

    fun canOpen(profile: Profile, levels: List<CatalogLevel>, difficulty: Difficulty, stop: Int): Boolean =
        stop in 1..12 && levelsForStop(levels, stop).isNotEmpty() && ProfileReducer.isStopUnlocked(profile, stop, difficulty)
}
