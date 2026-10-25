package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.game.MatchReplay
import pt.tiago.neonline.game.RestoredMatch
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.serialization.StateHasher

/** Version1 stays classic; version2 adds rules; version3 adds fading; version4 adds shapes; version5 opts into engine3 settlement; version6 strengthens HARD classic endgames; version7 opts into clockwise engine4; version8 adds Dolores. */
@Serializable
data class FreePlayConfig(val version: Int = 1, val rival: String = "sparky", val difficulty: Difficulty = Difficulty.EASY,
    val starter: Player = Player.P1, val seedHex: String = "0123456789abcdef", val rules: FreePlayRules = FreePlayRules()) {
    fun isPerfectMode(): Boolean = rival == PERFECT_RIVAL
    fun isValid(): Boolean = version >= 1 && rival.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && seedHex.matches(Regex("[0-9a-f]{16}")) && rules.isValid(allowMaskedSpecials = version >= 5) && (version != 1 || rules.isClassic()) && (version >= 3 || !rules.fade) && (version >= 4 || rules.shape == FreePlayBoardShape.RECTANGLE) &&
        (!isPerfectMode() || (difficulty == Difficulty.HARD && rules.isClassic()))
    fun initial(): GameState {
        require(isValid() && version in 1..8)
        return GameState.initial("free-play", difficulty, engineVersion(), seedHex, rules.width, rules.height, rules.connect,
            rules.playableMask(), List(rules.width * rules.height) { null }, firstToMove = starter,
            fadeAfter = rules.fadeAfter(difficulty), popOut = rules.popOut, rotateEvery = if (rules.rotation) 6 else null, moveClockSec = rules.clockSeconds.takeIf { it > 0 },
            inventory = if (rules.specials) Player.entries.associateWith { mapOf(PieceKind.WILD to 1, PieceKind.BOMB to 1, PieceKind.LOCK to 1) }
                else GameState.emptyInventory())
    }
    /** Published configs pin engine2 even after the free-play engine expands. */
    fun engineVersion(): Int = when {
        version >= 7 -> pt.tiago.neonline.core.rules.GameEngine.CLOCKWISE_ROTATION_ENGINE_VERSION
        version >= 5 -> pt.tiago.neonline.core.rules.GameEngine.SOLID_FLOOR_ENGINE_VERSION
        else -> 2
    }
    fun maxPlies(): Int? = if (version in 2..8) FreePlay.MAX_PLIES else null
    fun exactEndgameRival(): Boolean = !isPerfectMode() && version in 6..8 && difficulty == Difficulty.HARD && rules.isClassic()
    fun hints(): Int = if (isPerfectMode()) 0 else when (difficulty) { Difficulty.EASY -> 3; Difficulty.MEDIUM -> 2; Difficulty.HARD -> 1 }
    fun hintAi(fallback: AiProfile? = null): AiProfile? = copy(difficulty = Difficulty.HARD).ai() ?: fallback
    fun undos(): Int = if (difficulty == Difficulty.EASY && !isPerfectMode()) 3 else 0
    fun ai(): AiProfile? {
        if (version !in 1..8 || !isValid()) return null
        if (isPerfectMode()) return AiProfile.HARD
        if (rival !in FreePlay.supportedRivals || (rival == "dolores" && version < 8)) return null
        val base = when (difficulty) {
        Difficulty.EASY -> AiProfile(maxDepth = 2, nodeBudget = 1000, attack = 3, defense = 4, center = 2, trap = 1, blunderRate = 180, blunderMayMiss = true)
        Difficulty.MEDIUM -> AiProfile(maxDepth = 4, nodeBudget = 4000, attack = 4, defense = 5, center = 3, trap = 3)
        Difficulty.HARD -> AiProfile(maxDepth = 6, nodeBudget = 12000, attack = 6, defense = 6, center = 4, trap = 5)
        }
        if (version == 1) return base
        val budget = if (rules.specials) when (difficulty) { Difficulty.EASY -> 200; Difficulty.MEDIUM -> 600; Difficulty.HARD -> 1000 }
            else if (rules.hasComplexSearch()) when (difficulty) { Difficulty.EASY -> 500; Difficulty.MEDIUM -> 1500; Difficulty.HARD -> 3000 }
            else base.nodeBudget
        return base.copy(nodeBudget = budget, mirror = rival == "dolores")
    }

    companion object { const val PERFECT_RIVAL = "perfect" }
}

@Serializable
data class SavedFreePlay(val config: FreePlayConfig, val match: SavedMatch, val engineVersion: Int = config.engineVersion())

object FreePlay {
    const val MAX_PLIES = 512
    const val MAX_COUNTER = Long.MAX_VALUE / 4
    val supportedRivals = setOf("sparky", "dolores")
    fun canStart(profile: Profile, levels: List<RivalLevel>, config: FreePlayConfig): Boolean = config.isValid() && config.ai() != null &&
        (config.isPerfectMode() || (levels.any { it.rival == config.rival } &&
            RivalGallery.summarize(profile, levels, config.difficulty).any { it.definition.id == config.rival && it.encountered }))

    /** Reserves a newly selected game before its UI can emit checkpoints. */
    fun start(profile: Profile, config: FreePlayConfig): Profile {
        require(config.isValid())
        val initial = config.initial()
        val hash = StateHasher.hash(initial)
        val match = SavedMatch(initial.levelId, config.difficulty, initialHash = hash, stateHash = hash,
            hintsLeft = config.hints(), undosLeft = config.undos())
        return profile.copy(revision = profile.revision + 1, savedFreePlay = SavedFreePlay(config, match))
    }

    fun restore(saved: SavedFreePlay): RestoredMatch? = runCatching {
        require(saved.engineVersion == saved.config.engineVersion() && saved.config.ai() != null && saved.config.isValid() && saved.match.bossHistory.isEmpty() && (saved.config.maxPlies()?.let { saved.match.steps.size < it } ?: true))
        MatchReplay.restore(saved.config.initial(), saved.match, saved.config.hints(), saved.config.undos())
    }.getOrNull()

    fun checkpoint(profile: Profile, config: FreePlayConfig, match: SavedMatch, outcome: LevelOutcome?): Profile {
        val active = profile.savedFreePlay
        if (active != null && (active.config != config || active.match.initialHash != match.initialHash)) return profile
        val previous = profile.savedFreePlay?.takeIf { it.config == config && it.match.initialHash == match.initialHash }?.match?.peekCount ?: 0
        val delta = (match.peekCount.toLong() - previous).coerceAtLeast(0)
        val totals = if (delta == 0L) profile.freePlayPeeks else profile.freePlayPeeks +
            (config.difficulty to ((profile.freePlayPeeks[config.difficulty] ?: 0L) + delta).coerceAtMost(MAX_COUNTER))
        if (outcome == null) return profile.copy(freePlayPeeks = totals, revision = profile.revision + 1, savedFreePlay = SavedFreePlay(config, match))
        val old = profile.freePlayStats[config.difficulty] ?: RivalRecord()
        val next = when (outcome) {
            is LevelOutcome.Won -> old.copy(wins = (old.wins + 1).coerceAtMost(MAX_COUNTER))
            is LevelOutcome.Lost -> if (outcome.reason == "draw") old.copy(draws = (old.draws + 1).coerceAtMost(MAX_COUNTER))
                else old.copy(losses = (old.losses + 1).coerceAtMost(MAX_COUNTER))
        }
        return profile.copy(revision = profile.revision + 1, savedFreePlay = null, freePlayPeeks = totals, freePlayStats = profile.freePlayStats + (config.difficulty to next))
    }

    /** Drops callbacks queued by a game screen after navigation or replacement. */
    fun checkpointForCurrentSession(profile: Profile, config: FreePlayConfig, match: SavedMatch,
        outcome: LevelOutcome?, callbackSession: Int, currentSession: Int): Profile {
        if (callbackSession != currentSession) return profile
        return checkpoint(profile, config, match, outcome)
    }

    /** Journals are replayed separately; future supported configs can survive an import preview. */
    fun isValid(profile: Profile): Boolean {
        if (profile.freePlayPeeks.values.any { it !in 0..MAX_COUNTER }) return false
        if (profile.freePlayStats.values.any { it.wins !in 0..MAX_COUNTER || it.draws !in 0..MAX_COUNTER || it.losses !in 0..MAX_COUNTER }) return false
        val saved = profile.savedFreePlay ?: return true
        return saved.config.isValid() && saved.engineVersion >= 1 && saved.match.formatVersion >= 1 &&
            saved.match.peekCount in 0..pt.tiago.neonline.game.PeekVisibility.MAX_COUNT && saved.match.steps.size <= 4096 && saved.match.moves.size <= 4096 && saved.match.bossHistory.size <= 5 && saved.match.bossHistory.all { it.steps.size <= 4096 }
    }
}
