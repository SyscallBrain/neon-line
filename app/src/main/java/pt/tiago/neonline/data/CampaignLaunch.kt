package pt.tiago.neonline.data

import pt.tiago.neonline.core.model.Difficulty

enum class CampaignLaunchAction { UNAVAILABLE, LOCKED, RESUME, INTRO }

/** Launch decisions stay independent of dialogs and never write progression. */
object CampaignLaunch {
    fun action(profile: Profile, levels: List<CatalogLevel>, ordinal: Int, difficulty: Difficulty, resumable: SavedMatch?): CampaignLaunchAction {
        val level = levels.firstOrNull { it.ordinal == ordinal && ordinal in 1..CAMPAIGN_LEVELS } ?: return CampaignLaunchAction.UNAVAILABLE
        if (resumable?.levelId == level.id && resumable.difficulty == difficulty) return CampaignLaunchAction.RESUME
        if (!ProfileReducer.isUnlocked(profile, ordinal, difficulty) || !ProfileReducer.isStopUnlocked(profile,(ordinal-1)/50+1,difficulty))
            return CampaignLaunchAction.LOCKED
        return CampaignLaunchAction.INTRO
    }
    fun needsReplacement(saved: SavedMatch?): Boolean = saved != null && (saved.moves.isNotEmpty() || saved.steps.isNotEmpty() || saved.bossHistory.isNotEmpty())
}
