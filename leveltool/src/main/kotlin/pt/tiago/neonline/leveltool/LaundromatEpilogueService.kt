package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed final ordinary Bubble Laundromat levels, 291–299. */
object LaundromatEpilogueService {
    private const val RECIPES = "templates/laundromat-epilogue.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (291..299).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "puzzle", "defense", "duel", "puzzle", "defense", "duel", "puzzle", "defense"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(292, 295, 298))
        require(recipes.lessons.none { it.isDoubleNeon })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("laundromat-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null && lesson.tutorialColumn == null &&
                lesson.sourceOrdinals == null && lesson.sourceHardOrdinal == null
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File) = DriveInContinuationService.generate(
        root = root,
        output = output,
        lessons = load(root).lessons,
        sourceOrdinal = 290,
        seedScope = "laundromat-epilogue",
        forcedWinNodeBudget = 150_000,
        stopId = "stop-06",
        rivalId = "centrifuge",
        preserveSourceSeed = true,
    )
}
