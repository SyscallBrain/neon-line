package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** First recipe-backed block for Boulevard of Lights and its final rival, Queen. */
object BoulevardOfLightsService {
    private const val RECIPES = "templates/boulevard-of-lights.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (551..560).toList())
        require(lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("boulevard-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 &&
                lesson.openingPairsMin in 3..16 && lesson.openingPairsMax in lesson.openingPairsMin..16 &&
                lesson.tutorialKey == null && lesson.tutorialColumn == null && lesson.sourceOrdinals == null
        })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "puzzle" }.associate { it.ordinal to it.sourceHardOrdinal } ==
            mapOf(553 to 279, 556 to 219, 559 to 279))
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null && it.sourceHardOrdinal == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null && it.sourceHardOrdinal == null })
        require(lessons.map { it.isDoubleNeon } == (551..560).map { it == 560 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 550..559) { "Boulevard opening expects campaign content through level 550–559" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 560) {
            "Boulevard opening must begin after the last published level and end at 560"
        }
        DriveInContinuationService.generate(
            root = root, output = output, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
            seedScope = "boulevard-of-lights-551-560", forcedWinNodeBudget = 150_000,
            stopId = "stop-12", rivalId = "queen", calibrationGames = 8,
        )
    }
}
