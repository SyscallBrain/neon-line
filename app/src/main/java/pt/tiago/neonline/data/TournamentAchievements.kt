package pt.tiago.neonline.data

/** The single global Tournament badge is proven by an eight-round best result. */
data class TournamentAchievementProgress(val current: Int, val unlocked: Boolean) {
    val target: Int get() = Tournament.ROUNDS
    val fraction: Float get() = (current.toFloat() / target).coerceIn(0f, 1f)
}

object TournamentAchievements {
    const val ID = "tournament.won"

    fun progress(profile: Profile): TournamentAchievementProgress {
        val unlocked = profile.achievements.any { it.id == ID } ||
            profile.tournamentBestRounds.values.any { it >= Tournament.ROUNDS }
        val current = if (unlocked) Tournament.ROUNDS else
            (profile.tournamentBestRounds.values.maxOrNull() ?: 0).coerceIn(0, Tournament.ROUNDS)
        return TournamentAchievementProgress(current, unlocked)
    }

    fun reconcile(profile: Profile, advanceRevision: Boolean = true): Profile {
        if (progress(profile).current < Tournament.ROUNDS || profile.achievements.any { it.id == ID }) return profile
        val revision = if (advanceRevision) profile.revision + 1 else profile.revision
        return profile.copy(revision = revision, achievements = profile.achievements + Achievement(ID, revision))
    }
}
