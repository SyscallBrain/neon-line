package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed continuation for Stop 12, levels 571–580. */
object BoulevardOfLightsEncoreService {
    private const val RECIPES = "templates/boulevard-of-lights-encore.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (571..580).toList())
        require(lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("boulevard-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 &&
                lesson.openingPairsMin in 3..16 && lesson.openingPairsMax in lesson.openingPairsMin..16 &&
                lesson.tutorialKey == null && lesson.tutorialColumn == null
        })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "puzzle" }.associate { it.ordinal to it.sourceHardOrdinal } ==
            mapOf(573 to 279, 576 to 219, 579 to 219))
        require(lessons.filter { it.mirrorSourceHard }.map { it.ordinal } == listOf(579))
        require(lessons.filter { it.kind != "puzzle" }.all { it.sourceOrdinals == null && it.sourceHardOrdinal == null })
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null && it.sourceHardOrdinal == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null && it.sourceHardOrdinal == null })
        require(lessons.map { it.isDoubleNeon } == (571..580).map { it == 580 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 570..579) { "Boulevard encore expects campaign content through level 570–579" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 580) {
            "Boulevard encore must begin after the last published level and end at 580"
        }
        DriveInContinuationService.generate(
            root = root, output = output, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
            seedScope = "boulevard-of-lights-571-580", forcedWinNodeBudget = 150_000,
            stopId = "stop-12", rivalId = "queen", calibrationGames = 8,
        )
    }
}
