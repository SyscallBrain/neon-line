package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

@Serializable
data class RivalRecord(val wins: Long = 0, val draws: Long = 0, val losses: Long = 0)
data class RivalDefinition(val id: String, val name: String, val stop: Int)
@Serializable
data class RivalLevel(val id: String, val ordinal: Int, val difficulty: Difficulty, val rival: String, val boss: Boolean)
data class RivalCard(val definition: RivalDefinition, val encountered: Boolean, val bossDifficulties: Set<Difficulty>,
    val record: RivalRecord, val challengeOrdinal: Int?) {
    val bossBeaten: Boolean get() = bossDifficulties.isNotEmpty()
}

/** Presentation derives old unlocks from real content; counters never invent historical attempts. */
object RivalGallery {
    val roster = listOf("sparky" to "Sparky", "dolores" to "Dolores", "flamingo" to "Flamingo", "strike" to "Strike",
        "projectionist" to "Projecionista", "centrifuge" to "Centrífuga", "skater" to "Patins", "diva" to "Diva",
        "pixel" to "Pixel", "wheel" to "Roda", "ace" to "Ás", "queen" to "Rainha da Avenida")
        .mapIndexed { index, (id, name) -> RivalDefinition(id, name, index + 1) }
    private val supportedIds = roster.map { it.id }.toSet()
    private const val MAX_RIVALS = 64
    private val futureCapacity = MAX_RIVALS - roster.size
    private const val MAX_COUNTER = Long.MAX_VALUE / 4

    /** Called only by the campaign checkpoint; a boss outcome represents the whole series. */
    fun record(profile: Profile, rival: String, difficulty: Difficulty, outcome: LevelOutcome?): Profile {
        require(validKey(rival))
        // Keep every accepted imported record and reserve a slot for each supported rival.
        // Unknown content beyond the reserved future capacity cannot make a valid profile invalid.
        if (rival !in supportedIds && rival !in profile.rivalRecords &&
            profile.rivalRecords.keys.count { it !in supportedIds } >= futureCapacity) return profile
        val difficulties = profile.rivalRecords[rival].orEmpty()
        val previous = difficulties[difficulty]
        if (outcome == null && previous != null) return profile
        val old = previous ?: RivalRecord()
        val next = when (outcome) {
            is LevelOutcome.Won -> old.copy(wins = (old.wins + 1).coerceAtMost(MAX_COUNTER))
            is LevelOutcome.Lost -> if (outcome.reason == "draw") old.copy(draws = (old.draws + 1).coerceAtMost(MAX_COUNTER))
                else old.copy(losses = (old.losses + 1).coerceAtMost(MAX_COUNTER))
            null -> old
        }
        return profile.copy(revision = profile.revision + 1, rivalRecords = profile.rivalRecords + (rival to (difficulties + (difficulty to next))))
    }

    fun summarize(profile: Profile, levels: List<RivalLevel>, difficulty: Difficulty): List<RivalCard> = roster.map { rival ->
        val owned = levels.filter { it.rival == rival.id }
        fun completed(level: RivalLevel) = ProfileReducer.variantId(level.ordinal, level.difficulty) in profile.progress(level.difficulty).completedVariantIds
        val bosses = owned.filter { it.boss && completed(it) }.map { it.difficulty }.toSet()
        val active = profile.savedMatch?.let { saved -> owned.any { it.id == saved.levelId && it.difficulty == saved.difficulty } } ?: false
        val encountered = profile.rivalRecords[rival.id]?.isNotEmpty() == true || owned.any(::completed) || active
        val available = owned.filter { it.difficulty == difficulty && ProfileReducer.isUnlocked(profile, it.ordinal, difficulty) }.sortedBy { it.ordinal }
        val next = available.firstOrNull { !completed(it) } ?: available.lastOrNull()
        RivalCard(rival, encountered, bosses, profile.rivalRecords[rival.id]?.get(difficulty) ?: RivalRecord(), next?.ordinal)
    }

    fun isValid(profile: Profile): Boolean = profile.rivalRecords.size <= MAX_RIVALS &&
        profile.rivalRecords.keys.count { it !in supportedIds } <= futureCapacity && profile.rivalRecords.all { (id, records) ->
        validKey(id) && records.isNotEmpty() && records.values.all { it.wins in 0..MAX_COUNTER && it.draws in 0..MAX_COUNTER && it.losses in 0..MAX_COUNTER }
    }
    private fun validKey(id: String) = id.matches(Regex("[a-z][a-z0-9_-]{0,63}"))
}
