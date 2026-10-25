package pt.tiago.neonline.data

import java.util.Random
import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.Player

@Serializable
data class TournamentRound(
    val rival: String,
    val rules: FreePlayRules,
    val starter: Player,
    val seedHex: String,
)

@Serializable
enum class TournamentStatus { ACTIVE, WON, LOST }

enum class TournamentOutcome { WIN, DRAW, LOSS }

/** Complete deterministic bracket state. The current match is replayed separately by the game engine. */
@Serializable
data class SavedTournament(
    val difficulty: Difficulty,
    val seedHex: String,
    val bracket: List<String>,
    val availableRules: List<FreePlayRules>,
    val rounds: List<TournamentRound>,
    val roundIndex: Int = 0,
    val clearedRivals: List<String> = emptyList(),
    val drawReplayUsed: Boolean = false,
    val status: TournamentStatus = TournamentStatus.ACTIVE,
    val currentMatch: SavedMatch? = null,
)

/** Pure tournament setup, deterministic round generation, and ladder transitions. */
object Tournament {
    const val ROUNDS = 8
    private val rosterOrder = RivalGallery.roster.mapIndexed { index, rival -> rival.id to index }.toMap()

    fun create(
        selectedRivals: List<String>,
        defeatedRivals: Set<String>,
        difficulty: Difficulty,
        seedHex: String,
        unlockedRules: List<FreePlayRules>,
    ): SavedTournament {
        require(selectedRivals.size == ROUNDS) { "select exactly eight rivals" }
        require(selectedRivals.distinct().size == ROUNDS) { "tournament rivals must be distinct" }
        require(selectedRivals.all { it in defeatedRivals && it in rosterOrder }) { "all selected rivals must be defeated campaign rivals" }
        require(seedHex.matches(Regex("[0-9a-f]{16}"))) { "seed must be 16 lowercase hexadecimal characters" }
        require(unlockedRules.isNotEmpty() && unlockedRules.size <= 64 && unlockedRules.distinct().size == unlockedRules.size && unlockedRules.all { it.isValid() }) {
            "tournament requires a bounded set of unique valid unlocked rules"
        }
        val bracket = selectedRivals.sortedBy { rosterOrder.getValue(it) }
        val rounds = makeRounds(bracket, seedHex, unlockedRules)
        return SavedTournament(difficulty, seedHex, bracket, unlockedRules, rounds)
    }

    fun currentRound(tournament: SavedTournament): TournamentRound? =
        tournament.rounds.getOrNull(tournament.roundIndex).takeIf { tournament.status == TournamentStatus.ACTIVE }

    fun roundConfig(tournament: SavedTournament): FreePlayConfig {
        val round = requireNotNull(currentRound(tournament)) { "tournament has no active round" }
        return FreePlayConfig(version = 8, rival = round.rival, difficulty = tournament.difficulty,
            starter = round.starter, seedHex = round.seedHex, rules = round.rules)
    }

    /** Unlock each tournament rule when campaign progress reaches the stop that introduces it. */
    fun unlockedRules(profile: Profile): List<FreePlayRules> {
        val reached = profile.progressByDifficulty.values.maxOfOrNull { it.highestReached } ?: 0
        return buildList {
            add(FreePlayRules())
            if (reached >= 151) {
                add(FreePlayRules(shape = FreePlayBoardShape.PILLARS))
                add(FreePlayRules(shape = FreePlayBoardShape.STEPS))
            }
            if (reached >= 201) add(FreePlayRules(popOut = true))
            if (reached >= 251) add(FreePlayRules(rotation = true))
            if (reached >= 301) add(FreePlayRules(clockSeconds = 10))
            if (reached >= 351) add(FreePlayRules(fade = true))
            if (reached >= 401) add(FreePlayRules(specials = true))
            if (reached >= 451) add(FreePlayRules(width = 9, height = 7, connect = 5))
        }
    }

    /** A rival is defeated only after a campaign boss win at any difficulty. */
    fun defeatedRivals(profile: Profile, levels: List<RivalLevel>): Set<String> = levels.asSequence()
        .filter { it.boss }
        .filter { it.rival in profile.rivalsBeaten || ProfileReducer.variantId(it.ordinal, it.difficulty) in profile.progress(it.difficulty).completedVariantIds }
        .map { it.rival }
        .filter { it in rosterOrder }
        .toSet()

    private fun defeatedRivals(profile: Profile): Set<String> = RivalGallery.roster.asSequence()
        .filter { rival -> rival.id in profile.rivalsBeaten || Difficulty.entries.any { difficulty ->
            ProfileReducer.variantId(rival.stop * 50, difficulty) in profile.progress(difficulty).completedVariantIds
        } }
        .map { it.id }
        .toSet()

    fun resolve(tournament: SavedTournament, outcome: TournamentOutcome): SavedTournament {
        require(isValid(tournament) && tournament.status == TournamentStatus.ACTIVE) { "tournament is not active" }
        return when (outcome) {
            TournamentOutcome.DRAW -> if (!tournament.drawReplayUsed) {
                tournament.copy(drawReplayUsed = true, currentMatch = null)
            } else {
                tournament.copy(status = TournamentStatus.LOST, currentMatch = null)
            }
            TournamentOutcome.LOSS -> tournament.copy(status = TournamentStatus.LOST, currentMatch = null)
            TournamentOutcome.WIN -> {
                val nextIndex = tournament.roundIndex + 1
                tournament.copy(
                    roundIndex = nextIndex,
                    clearedRivals = tournament.clearedRivals + tournament.bracket[tournament.roundIndex],
                    drawReplayUsed = false,
                    status = if (nextIndex == ROUNDS) TournamentStatus.WON else TournamentStatus.ACTIVE,
                    currentMatch = null,
                )
            }
        }
    }

    fun isValid(tournament: SavedTournament): Boolean {
        if (!tournament.seedHex.matches(Regex("[0-9a-f]{16}"))) return false
        if (tournament.bracket.size != ROUNDS || tournament.bracket.distinct().size != ROUNDS) return false
        if (tournament.bracket.any { it !in rosterOrder } || tournament.bracket != tournament.bracket.sortedBy { rosterOrder.getValue(it) }) return false
        if (tournament.availableRules.isEmpty() || tournament.availableRules.size > 64 ||
            tournament.availableRules.distinct().size != tournament.availableRules.size || tournament.availableRules.any { !it.isValid() }) return false
        if (tournament.rounds.size != ROUNDS || tournament.roundIndex !in 0..ROUNDS ||
            tournament.clearedRivals != tournament.bracket.take(tournament.roundIndex)) return false
        if (tournament.rounds != makeRounds(tournament.bracket, tournament.seedHex, tournament.availableRules)) return false
        if (tournament.status == TournamentStatus.ACTIVE && tournament.roundIndex !in 0 until ROUNDS) return false
        if (tournament.status == TournamentStatus.WON && tournament.roundIndex != ROUNDS) return false
        if (tournament.status == TournamentStatus.LOST && tournament.roundIndex !in 0 until ROUNDS) return false
        if (tournament.status != TournamentStatus.ACTIVE && tournament.currentMatch != null) return false
        val match = tournament.currentMatch ?: return true
        return tournament.status == TournamentStatus.ACTIVE && match.difficulty == tournament.difficulty &&
            match.formatVersion == 1 && match.steps.size <= 4096 && match.moves.size <= 4096 &&
            match.elapsedMs >= 0L && match.peekCount in 0..pt.tiago.neonline.game.PeekVisibility.MAX_COUNT && match.bossHistory.isEmpty()
    }

    fun isValid(profile: Profile): Boolean {
        if (profile.tournamentBestRounds.values.any { it !in 0..ROUNDS }) return false
        val saved = profile.savedTournament ?: return true
        return isValid(saved) && saved.bracket.all { it in defeatedRivals(profile) } &&
            unlockedRules(profile).containsAll(saved.availableRules)
    }

    fun checkpoint(profile: Profile, tournament: SavedTournament): Profile {
        require(isValid(tournament) && tournament.status == TournamentStatus.ACTIVE)
        val saved = requireNotNull(profile.savedTournament) { "there is no saved tournament to checkpoint" }
        require(saved.copy(currentMatch = tournament.currentMatch) == tournament) {
            "checkpoint does not match the saved tournament"
        }
        return profile.copy(revision = profile.revision + 1, savedTournament = tournament)
    }

    /** Records a terminal result once, retaining the best number of rounds cleared per difficulty. */
    fun finish(profile: Profile, tournament: SavedTournament): Profile {
        require(isValid(tournament) && tournament.status != TournamentStatus.ACTIVE)
        val saved = profile.savedTournament ?: return profile
        require(saved == tournament) { "terminal result does not match the saved tournament" }
        val roundsCleared = if (tournament.status == TournamentStatus.WON) ROUNDS else tournament.roundIndex
        val best = maxOf(profile.tournamentBestRounds[tournament.difficulty] ?: 0, roundsCleared)
        val finished = profile.copy(
            revision = profile.revision + 1,
            savedTournament = null,
            tournamentBestRounds = profile.tournamentBestRounds + (tournament.difficulty to best),
        )
        return TournamentAchievements.reconcile(finished, advanceRevision = false)
    }

    private fun makeRounds(bracket: List<String>, seedHex: String, rules: List<FreePlayRules>): List<TournamentRound> {
        val random = Random(seedHex.toULong(16).toLong())
        val stableRules = rules.sortedBy { it.toString() }
        return bracket.mapIndexed { index, rival ->
            val roundSeed = random.nextLong().toULong().toString(16).padStart(16, '0')
            TournamentRound(rival, stableRules[random.nextInt(stableRules.size)], if (index % 2 == 0) Player.P1 else Player.P2, roundSeed)
        }
    }
}
