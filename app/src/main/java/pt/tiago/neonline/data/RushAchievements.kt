package pt.tiago.neonline.data

import pt.tiago.neonline.core.model.Difficulty

data class RushAchievementProgress(val difficulty: Difficulty, val current: Int, val unlocked: Boolean) {
    val id: String get() = "rush.solved_25.${difficulty.name.lowercase()}"
    val target: Int get() = 25
    val fraction: Float get() = (current.toFloat() / target).coerceIn(0f, 1f)
}

/** Best single-run scores prove this milestone, including scores saved by older app versions. */
object RushAchievements {
    val ids: Set<String> = Difficulty.entries.map { RushAchievementProgress(it, 0, false).id }.toSet()

    fun progress(profile: Profile, difficulty: Difficulty): RushAchievementProgress {
        val goal = RushAchievementProgress(difficulty, 0, false)
        val unlocked = profile.achievements.any { it.id == goal.id }
        return goal.copy(current = if (unlocked) goal.target else (profile.rushBest[difficulty] ?: 0).coerceIn(0, goal.target),
            unlocked = unlocked)
    }

    fun reconcile(profile: Profile, advanceRevision: Boolean = true): Profile {
        val earned = Difficulty.entries.map { progress(profile, it) }.filter { !it.unlocked && it.current >= it.target }
        if (earned.isEmpty()) return profile
        val revision = if (advanceRevision) profile.revision + 1 else profile.revision
        return profile.copy(revision = revision,
            achievements = profile.achievements + earned.map { Achievement(it.id, revision) })
    }
}

/** Reconcile all result-derived badges as one profile transaction. */
object ProfileAchievements {
    fun reconcile(profile: Profile, advanceRevision: Boolean = true): Profile {
        val campaign = CampaignAchievements.reconcile(profile, advanceRevision)
        val rush = RushAchievements.reconcile(campaign, advanceRevision && campaign === profile)
        return TournamentAchievements.reconcile(rush, advanceRevision && rush === profile)
    }
}
