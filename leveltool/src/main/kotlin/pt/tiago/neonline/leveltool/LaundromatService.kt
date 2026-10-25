package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed certified opening block for Bubble Laundromat, levels 251–260. */
object LaundromatService {
    private const val RECIPES = "templates/laundromat.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (251..260).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(252, 253, 255, 258, 260))
        require(recipes.lessons.map { it.isDoubleNeon } == (251..260).map { it == 260 })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("laundromat-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null &&
                lesson.tutorialColumn == null &&
                (lesson.ordinal == 258 || lesson.sourceOrdinals == null) &&
                (lesson.ordinal in setOf(253, 256, 258, 259) || lesson.sourceHardOrdinal == null)
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.sourceHardOrdinal != null }.associate { it.ordinal to it.sourceHardOrdinal } ==
            mapOf(253 to 243, 256 to 226, 258 to 248, 259 to 229))
        require(recipes.lessons.single { it.ordinal == 258 }.sourceOrdinals == listOf(248, 248, 248))
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File) = DriveInContinuationService.generate(
        root = root,
        output = output,
        lessons = load(root).lessons,
        sourceOrdinal = 250,
        seedScope = "laundromat-opening",
        forcedWinNodeBudget = 150_000,
        stopId = "stop-06",
        rivalId = "centrifuge",
        preserveSourceSeed = true,
    )
}
