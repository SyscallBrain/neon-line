package pt.tiago.neonline.leveltool

import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.model.Difficulty

/** Fixed stratified sample; report actual app profiles, never temporary tuning profiles. */
object DifficultyAudit {
    @Serializable data class Sample(val level: String, val difficulty: String, val games: Int, val wins: Int, val averageMoves: Double)
    @Serializable data class Report(val policyVersion: Int = 2, val samples: List<Sample>)

    fun run(root: File, games: Int) {
        val samples = mutableListOf<Sample>()
        val ids = listOf(11, 12, 24, 25, 36, 37, 49, 50)
        for (ordinal in ids) {
            val id = "level-%04d".format(ordinal)
            for (difficulty in Difficulty.entries) {
                val result = Calibrator.calibrate(root, id, difficulty, games)
                val sample = Sample(id, difficulty.name.lowercase(), games, (result.winRate * games).roundToInt(), result.solverMoves)
                samples += sample
                println("audit: $id/${sample.difficulty} ${sample.wins}/$games wins, ${"%.1f".format(sample.averageMoves)} moves")
            }
        }
        val report = File(root, "balance-sample.json")
        report.writeText(Json { prettyPrint = true }.encodeToString(Report.serializer(), Report(samples = samples)) + "\n")
        Difficulty.entries.forEach { difficulty ->
            val rows = samples.filter { it.difficulty == difficulty.name.lowercase() }
            println("audit total: ${difficulty.name.lowercase()} ${rows.sumOf { it.wins }}/${rows.sumOf { it.games }}")
        }
    }
}
