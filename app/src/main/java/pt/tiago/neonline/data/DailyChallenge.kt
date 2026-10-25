package pt.tiago.neonline.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.security.MessageDigest
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelOutcome

@Serializable
data class SavedDaily(val date: String, val poolVersion: Int, val levelSha256: String, val match: SavedMatch)
@Serializable
data class DailyRecord(val date: String, val difficulty: Difficulty, val levelId: String, val poolVersion: Int,
    val levelSha256: String, val stars: Int = 0, val attempts: Int = 0, val wins: Int = 0)

object DailyChallenge {
    const val HISTORY_DAYS = 270
    fun validDate(text: String): Boolean = runCatching { LocalDate.parse(text).toString() == text && text.length == 10 }.getOrDefault(false)
    fun choose(pool: DailyPoolDto, date: String, difficulty: Difficulty): DailyEntryDto? {
        if (!validDate(date)) return null
        val eligible = pool.entries.filter { it.difficulty == difficultyKey(difficulty) }.sortedBy { it.ordinal }
        if (eligible.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("$date:${difficultyKey(difficulty)}:${pool.version}".toByteArray(Charsets.UTF_8))
        val index = digest.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) } % eligible.size
        return eligible[index.toInt()]
    }
    fun checkpoint(profile: Profile, saved: SavedDaily, outcome: LevelOutcome?): Profile {
        if (outcome == null) return profile.copy(revision = profile.revision + 1, savedDaily = saved)
        val previous = profile.dailyHistory.firstOrNull { it.date == saved.date && it.difficulty == saved.match.difficulty }
        require(previous == null || (previous.levelId == saved.match.levelId && previous.levelSha256 == saved.levelSha256 && previous.poolVersion == saved.poolVersion))
        val record = DailyRecord(saved.date, saved.match.difficulty, saved.match.levelId, saved.poolVersion, saved.levelSha256,
            maxOf(previous?.stars ?: 0, (outcome as? LevelOutcome.Won)?.stars ?: 0),
            (previous?.attempts ?: 0) + 1, (previous?.wins ?: 0) + if (outcome is LevelOutcome.Won) 1 else 0)
        val history = profile.dailyHistory.filterNot { it.date == record.date && it.difficulty == record.difficulty } + record
        val latest = history.maxOf { LocalDate.parse(it.date) }
        val retained = history.filter { !LocalDate.parse(it.date).isBefore(latest.minusDays(HISTORY_DAYS - 1L)) }
            .sortedWith(compareByDescending<DailyRecord> { it.date }.thenBy { it.difficulty.ordinal })
        return profile.copy(revision = profile.revision + 1, savedDaily = null, dailyHistory = retained)
    }
    fun isValid(profile: Profile): Boolean {
        if (profile.dailyHistory.size > HISTORY_DAYS * Difficulty.entries.size) return false
        if (profile.dailyHistory.map { it.date to it.difficulty }.distinct().size != profile.dailyHistory.size) return false
        if (profile.dailyHistory.any { !validDate(it.date) || it.poolVersion < 1 || !validReference(it.levelId, it.levelSha256) ||
                it.stars !in 0..3 || it.attempts < 1 || it.wins !in 0..it.attempts || ((it.stars > 0) != (it.wins > 0)) }) return false
        if (profile.dailyHistory.isNotEmpty()) {
            val latest = profile.dailyHistory.maxOf { LocalDate.parse(it.date) }
            if (profile.dailyHistory.any { LocalDate.parse(it.date).isBefore(latest.minusDays(HISTORY_DAYS - 1L)) }) return false
        }
        val saved = profile.savedDaily ?: return true
        val record = profile.dailyHistory.firstOrNull { it.date == saved.date && it.difficulty == saved.match.difficulty }
        if (record != null && (record.levelId != saved.match.levelId || record.poolVersion != saved.poolVersion || record.levelSha256 != saved.levelSha256)) return false
        return validDate(saved.date) && saved.poolVersion > 0 && validReference(saved.match.levelId, saved.levelSha256) &&
            saved.match.formatVersion == 1 && saved.match.moves.isEmpty() && saved.match.bossHistory.isEmpty() && saved.match.steps.size <= 4096
    }
    private fun validReference(id: String, sha: String): Boolean = id.matches(Regex("level-[0-9]{4}")) && (id.substringAfter("level-").toIntOrNull() ?: 0) in 1..CAMPAIGN_LEVELS && sha.matches(Regex("[0-9a-f]{64}"))
}
