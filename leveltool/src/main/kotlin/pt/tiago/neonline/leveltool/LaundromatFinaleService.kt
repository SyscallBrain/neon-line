package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed continuation for Bubble Laundromat, levels 271–280. */
object LaundromatFinaleService {
    private const val RECIPES = "templates/laundromat-finale.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (271..280).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(272, 273, 275, 278, 280))
        require(recipes.lessons.map { it.isDoubleNeon } == (271..280).map { it == 280 })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("laundromat-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null && lesson.tutorialColumn == null &&
                lesson.sourceOrdinals == null
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.sourceHardOrdinal != null }.associate { it.ordinal to it.sourceHardOrdinal } ==
            mapOf(273 to 263, 276 to 266))
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null && it.sourceHardOrdinal == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null && it.sourceHardOrdinal == null })
    }

    fun run(root: File, output: File) = DriveInContinuationService.generate(
        root = root,
        output = output,
        lessons = load(root).lessons,
        sourceOrdinal = 270,
        seedScope = "laundromat-finale",
        forcedWinNodeBudget = 150_000,
        stopId = "stop-06",
        rivalId = "centrifuge",
        preserveSourceSeed = true,
    )
}
