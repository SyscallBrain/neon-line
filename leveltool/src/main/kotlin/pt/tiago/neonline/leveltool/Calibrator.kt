package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.random.SplitMix64
import java.io.File
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.difficultyKey
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.forBossRound
import pt.tiago.neonline.core.objectives.BossSeries
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Player

/**
 * Calibration: measures the simulated player against the exact published rival.
 * It never silently tunes a temporary profile different from the app.
 *
 * The bands:
 *
 * | | Easy | Medium | Hard |
 * |---|---|---|---|
 * | normal level | 85-95% | 60-75% | 35-50% |
 * | double neon and boss | 65-75% | 30-40% | 15-25% |
 * | novelty introduction | >= 95% | >= 85% | >= 70% |
 *
 * Ruling: the plan asks for 300 games per variant. On this laptop that is minutes per level, so
 * the default is 60 games with the band checked against the same agent; `calibrate --games 300`
 * runs the full count when a level is being finalised.
 */
object Calibrator {

    fun isNoveltyIntroduction(levelOrdinal: Int): Boolean = levelOrdinal in listOf(1, 51, 201, 301, 351, 401, 410, 420, 451, 551)

    fun band(difficulty: Difficulty, doubleNeon: Boolean, novelty: Boolean): ClosedFloatingPointRange<Double> =
        when (difficulty) {
            Difficulty.EASY -> if (novelty) 0.95..1.01 else if (doubleNeon) 0.65..0.75 else 0.85..0.95
            Difficulty.MEDIUM -> if (novelty) 0.85..1.01 else if (doubleNeon) 0.30..0.40 else 0.60..0.75
            Difficulty.HARD -> if (novelty) 0.70..1.01 else if (doubleNeon) 0.15..0.25 else 0.35..0.50
        }

    fun calibrate(root: File, levelId: String, difficulty: Difficulty, games: Int): CalibrationResult {
        val level = Files.levels(root).first { it.id == levelId }
        val key = difficultyKey(difficulty)
        val variant = level.variants[key] ?: error("missing $key for $levelId")
        require(games > 0)
        val profile = variant.ai.toProfile()
        val start = variant.toState(levelId, difficulty)
        val policy = HumanAgent.HumanPolicy.forDifficulty(difficulty)

        val outcomes = java.util.stream.IntStream.range(0, games).parallel().mapToObj { index ->
            if (variant.kind.type == "boss" && variant.bossRounds.size == 3) {
                var series = BossSeries()
                var attempts = 0
                while (!series.decided && attempts++ < 6) {
                    val seed = 0xCA11B + index.toLong() * 31 + levelId.hashCode() + series.roundIndex * 101L +
                        if (series.repeatingDraw) 0xD00L else 0L
                    val agent = HumanAgent(policy, SplitMix64(seed))
                    val round = agent.play(variant.forBossRound(series.roundIndex).toState(levelId, difficulty), profile)
                    val outcome = when {
                        round.playerWon -> MatchOutcome.Win(Player.P1, emptyList())
                        round.rivalWon -> MatchOutcome.Win(Player.P2, emptyList())
                        else -> MatchOutcome.Draw("full")
                    }
                    series = series.record(outcome, round.humanMoves, 0)
                }
                (series.wins == 2) to series.totalMoves
            } else {
                val agent = HumanAgent(policy, SplitMix64(0xCA11B + index.toLong() * 31 + levelId.hashCode()))
                val result = if (variant.kind.type == "defense")
                    agent.playDefense(start, profile, requireNotNull(variant.kind.surviveAiMoves))
                else agent.play(start, profile, variant.kind.winInMoves)
                result.playerWon to result.humanMoves
            }
        }.toList()
        val wins = outcomes.count { it.first }
        val solverMoves = outcomes.map { it.second }
        val winRate = wins.toDouble() / games
        val target = band(
            difficulty,
            level.isDoubleNeon || variant.kind.type == "boss",
            novelty = isNoveltyIntroduction(level.ordinal),
        )
        return CalibrationResult(
            levelId = levelId,
            difficulty = difficulty,
            games = games,
            winRate = winRate,
            inBand = winRate in target,
            solverMoves = solverMoves.average(),
            profile = profile,
        )
    }

    data class CalibrationResult(
        val levelId: String,
        val difficulty: Difficulty,
        val games: Int,
        val winRate: Double,
        val inBand: Boolean,
        val solverMoves: Double,
        val profile: AiProfile,
    )
}

/** Small file helper shared by the commands. */
object Files {
    fun levels(root: File): List<LevelFileDto> = File(root, "levels").walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .map { Certificates.decodeLevel(it.readText()) }
        .sortedBy { it.ordinal }
        .toList()
}
