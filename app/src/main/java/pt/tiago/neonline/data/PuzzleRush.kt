package pt.tiago.neonline.data

import java.security.MessageDigest
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.objectives.LevelKind

data class RushPuzzle @JvmOverloads constructor(val ordinal: Int, val sourceDifficulty: Difficulty, val moves: Int, val entryId: String? = null)
enum class RushEnd { TIME, ERRORS, EXIT, LIMIT }
data class RushRun(val difficulty: Difficulty, val seedHex: String, val startedAtMs: Long,
    val solved: Int = 0, val errors: Int = 0, val attempt: Int = 0, val end: RushEnd? = null) {
    init {
        require(seedHex.matches(Regex("[0-9a-f]{16}")) && startedAtMs >= 0L)
        require(solved in 0..PuzzleRush.MAX_SCORE && errors in 0..5 && attempt in 0..PuzzleRush.MAX_SCORE + 5)
    }
}

/** A run is ephemeral; only independent best scores are persisted. The wall clock never pauses. */
object PuzzleRush {
    const val MAX_SCORE = 100_000
    fun durationMs(d: Difficulty): Long = when(d) { Difficulty.EASY -> 240_000L; Difficulty.MEDIUM -> 180_000L; Difficulty.HARD -> 120_000L }
    fun errorLimit(d: Difficulty): Int = if(d == Difficulty.EASY) 5 else 3
    fun stage(solved: Int): Int = when { solved == 0 -> 1; solved < 5 -> 2; else -> 3 }
    fun remainingMs(run: RushRun, now: Long): Long =
        (durationMs(run.difficulty) - if(now <= run.startedAtMs) 0L else now - run.startedAtMs).coerceAtLeast(0L)
    fun tick(run: RushRun, now: Long): RushRun = if(run.end == null && remainingMs(run,now) == 0L) run.copy(end = RushEnd.TIME) else run
    fun stop(run: RushRun): RushRun = if(run.end == null) run.copy(end = RushEnd.EXIT) else run
    fun complete(run: RushRun, attempt: Int, won: Boolean, now: Long): RushRun {
        val active = tick(run,now)
        if(active.end != null || attempt != active.attempt) return active
        val solved = (active.solved + if(won) 1 else 0).coerceAtMost(MAX_SCORE)
        val errors = active.errors + if(won) 0 else 1
        return active.copy(solved = solved, errors = errors, attempt = (active.attempt + 1).coerceAtMost(MAX_SCORE + 5),
            end = when { errors >= errorLimit(active.difficulty) -> RushEnd.ERRORS; solved >= MAX_SCORE -> RushEnd.LIMIT; else -> null })
    }
    fun pool(levels: List<CampaignMapLevel>): List<RushPuzzle> = levels.filter {
        it.ordinal in 1..CAMPAIGN_LEVELS && it.kind == LevelKind.PUZZLE && it.fadeAfter == null && it.clockSeconds == null &&
            ((it.difficulty == Difficulty.EASY && it.winInMoves in 1..2) || (it.difficulty == Difficulty.HARD && it.winInMoves == 3))
    }.map { RushPuzzle(it.ordinal,it.difficulty,requireNotNull(it.winInMoves)) }.distinct()
        .sortedWith(compareBy<RushPuzzle> { it.moves }.thenBy { it.ordinal }.thenBy { it.sourceDifficulty.ordinal })
    fun ready(pool: List<RushPuzzle>): Boolean = (1..3).all { stage -> pool.any { it.moves == stage } }
    fun choose(pool: List<RushPuzzle>, run: RushRun): RushPuzzle? {
        val stage = stage(run.solved)
        val options = pool.filter { it.moves == stage }.distinct().sortedWith(compareBy<RushPuzzle> { it.ordinal }.thenBy { it.sourceDifficulty.ordinal })
        if(options.isEmpty()) return null
        val hash = MessageDigest.getInstance("SHA-256").digest("${run.seedHex}:$stage".toByteArray(Charsets.UTF_8))
        val offset = hash.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        return options[((offset + run.attempt) % options.size).toInt()]
    }
    fun record(profile: Profile, difficulty: Difficulty, score: Int): Profile {
        require(score in 0..MAX_SCORE)
        if(score <= (profile.rushBest[difficulty] ?: 0)) return RushAchievements.reconcile(profile)
        require(profile.revision in 0 until Long.MAX_VALUE)
        return RushAchievements.reconcile(profile.copy(revision = (profile.revision + 1).coerceAtMost(Long.MAX_VALUE - 1),
            rushBest = profile.rushBest + (difficulty to score)), advanceRevision = false)
    }
    fun isValid(profile: Profile): Boolean = profile.rushBest.values.all { it in 0..MAX_SCORE }
}
