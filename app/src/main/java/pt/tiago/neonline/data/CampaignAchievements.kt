package pt.tiago.neonline.data

import pt.tiago.neonline.core.model.Difficulty

/** These goals can be proved from existing best results, so legacy players keep their credit. */
enum class CampaignGoal(val key: String, val target: Int) {
    FIRST_WIN("first_win", 1), LEVELS_10("levels_10", 10), LEVELS_25("levels_25", 25), LEVELS_50("levels_50", 50),
    PERFECT_10("perfect_10", 10), PERFECT_25("perfect_25", 25), STARS_100("stars_100", 100),
    STOP_1("stop_1", 1), STOP_2("stop_2", 1), STOP_3("stop_3", 1), STOP_4("stop_4", 1), STOP_5("stop_5", 1), STOP_6("stop_6", 1), STOP_7("stop_7", 1), STOP_8("stop_8", 1), STOP_9("stop_9", 1),
}

data class CampaignAchievement(val goal: CampaignGoal, val difficulty: Difficulty) {
    val id: String get() = "campaign.${goal.key}.${difficulty.name.lowercase()}"
}

data class AchievementProgress(val definition: CampaignAchievement, val current: Int, val unlocked: Boolean) {
    val target: Int get() = definition.goal.target
    val fraction: Float get() = (current.toFloat() / target).coerceIn(0f, 1f)
}

object CampaignAchievements {
    val definitions: List<CampaignAchievement> = Difficulty.entries.flatMap { difficulty ->
        CampaignGoal.entries.map { CampaignAchievement(it, difficulty) }
    }

    fun progress(profile: Profile, difficulty: Difficulty): List<AchievementProgress> {
        val stored = profile.progress(difficulty)
        val pattern = Regex("level-([0-9]{4}):${difficulty.name.lowercase()}")
        fun valid(id: String): Boolean {
            val ordinal = pattern.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return ordinal in 1..CAMPAIGN_LEVELS
        }
        val completed = stored.completedVariantIds.filter(::valid).toSet()
        val best = stored.bestByVariantId.filterKeys { it in completed }
        val perfect = best.values.count { it.stars == 3 }
        val stars = best.values.sumOf { it.stars.coerceIn(0, 3) }
        val earned = profile.achievements.map { it.id }.toSet()
        return definitions.filter { it.difficulty == difficulty }.map { definition ->
            val value = when (definition.goal) {
                CampaignGoal.FIRST_WIN, CampaignGoal.LEVELS_10, CampaignGoal.LEVELS_25, CampaignGoal.LEVELS_50 -> completed.size
                CampaignGoal.PERFECT_10, CampaignGoal.PERFECT_25 -> perfect
                CampaignGoal.STARS_100 -> stars
                CampaignGoal.STOP_1 -> if ("level-0050:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_2 -> if ("level-0100:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_3 -> if ("level-0150:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_4 -> if ("level-0200:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_5 -> if ("level-0250:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_6 -> if ("level-0300:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_7 -> if ("level-0350:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_8 -> if ("level-0400:${difficulty.name.lowercase()}" in completed) 1 else 0
                CampaignGoal.STOP_9 -> if ("level-0450:${difficulty.name.lowercase()}" in completed) 1 else 0
            }
            val unlocked = definition.id in earned
            AchievementProgress(definition, if (unlocked) definition.goal.target else value.coerceAtMost(definition.goal.target), unlocked)
        }
    }

    /** Called in the same transaction as a win, or once to reconcile a legacy/imported profile. */
    fun reconcile(profile: Profile, advanceRevision: Boolean = true): Profile {
        val new = Difficulty.entries.flatMap { progress(profile, it) }
            .filter { !it.unlocked && it.current >= it.target }.map { it.definition }
        if (new.isEmpty()) return profile
        val revision = if (advanceRevision) profile.revision + 1 else profile.revision
        return profile.copy(revision = revision, achievements = profile.achievements + new.map { Achievement(it.id, revision) })
    }
}
