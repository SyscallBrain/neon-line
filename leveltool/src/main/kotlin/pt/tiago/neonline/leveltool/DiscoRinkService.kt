package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed first fifty for Stop 7 and its per-move clock. */
object DiscoRinkService {
    private const val RECIPES = "templates/disco-rink.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (301..350).toList())
        require(lessons.map { it.kind } == listOf(
            "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel",
            "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel",
            "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel",
            "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel",
            "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel",
        ))
        require(lessons.take(10).map { it.moveClockSec } == listOf(20, 20, 20, 15, 15, 15, 10, 10, 10, 10))
        require(lessons.drop(10).all { it.moveClockSec == 10 })
        require(lessons.first().tutorialKey == "tut_301_clock" && lessons.first().tutorialColumn == null)
        require(lessons.drop(1).all { it.tutorialKey == null && it.tutorialColumn == null })
        require(lessons.map { it.isDoubleNeon } == (301..350).map { it in setOf(310, 320, 330, 340) })
        require(lessons.filter { it.popOut }.map { it.ordinal } == listOf(312, 316, 322, 326, 332, 336, 342, 346))
        require(lessons.filter { it.rotateEvery != null }.map { it.ordinal } == listOf(325, 328, 335, 338, 345, 348))
        require(lessons.all { it.rotateEvery == if (it.ordinal in setOf(325, 328, 335, 338, 345, 348)) 6 else null })
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("disco-rink-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.moveClockSec?.let { it in 10..20 } == true &&
                lesson.sourceOrdinals == null &&
                lesson.sourceHardOrdinal == when (lesson.ordinal) { 302 -> 279; 312 -> 298; else -> null }
        })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File) {
        val ordinary = File(output.parentFile, "${output.name}-ordinary")
        val bossBase = File(output.parentFile, "${output.name}-boss-base")
        require(!output.exists() && !ordinary.exists() && !bossBase.exists()) { "Use fresh staging directories" }
        try {
            DriveInContinuationService.generate(
                root = root,
                output = ordinary,
                lessons = load(root).lessons.drop(40).map { lesson ->
                    // Calibrate the final bout in the tougher boss band; conversion clears this flag.
                    if (lesson.ordinal == 350) lesson.copy(isDoubleNeon = true) else lesson
                },
                sourceOrdinal = 340,
                seedScope = "disco-rink-341-350",
                forcedWinNodeBudget = 150_000,
                stopId = "stop-07",
                rivalId = "skater",
            )
            DiscoRinkBoss.run(ordinary, bossBase)
            BossContent.run(bossBase, output, setOf(350))
        } finally {
            ordinary.deleteRecursively()
            bossBase.deleteRecursively()
        }
    }
}
