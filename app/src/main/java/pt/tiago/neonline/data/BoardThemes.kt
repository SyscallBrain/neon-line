package pt.tiago.neonline.data

import pt.tiago.neonline.ui.art.BoardSkin

object BoardThemes {
    fun resolve(key: String, stars: Int): BoardSkin = BoardSkin.entries.firstOrNull { it.key == key && isUnlocked(it, stars) } ?: BoardSkin.NEON_ROAD
    fun isUnlocked(skin: BoardSkin, stars: Int): Boolean = stars >= skin.requiredStars
    fun select(profile: Profile, skin: BoardSkin): Profile =
        if (!isUnlocked(skin, profile.totalStars()) || skin.key == profile.settings.skin) profile
        else ProfileReducer.withSettings(profile, profile.settings.copy(skin = skin.key))
    fun newlyUnlocked(before: Profile, after: Profile): List<BoardSkin> = BoardSkin.entries.filter {
        !isUnlocked(it, before.totalStars()) && isUnlocked(it, after.totalStars())
    }
}

data class CampaignUnlocks(val achievements: List<String>, val skins: List<BoardSkin>)
