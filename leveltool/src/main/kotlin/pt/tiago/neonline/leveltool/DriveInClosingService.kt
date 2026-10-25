package pt.tiago.neonline.leveltool

import java.io.File
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed continuation for the final ordinary Cinema Drive-In levels, 241–249. */
object DriveInClosingService {
    private const val RECIPES = "templates/drivein-closing.json"

    fun load(root: File): DriveInContinuationRecipes =
        kotlinx.serialization.json.Json.decodeFromString(
            DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText(),
        ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (241..249).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(242, 243, 245, 248))
        require(recipes.lessons.none { it.isDoubleNeon })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("drivein-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null &&
                lesson.tutorialColumn == null && lesson.sourceOrdinals == null &&
                (lesson.ordinal == 249 || lesson.sourceHardOrdinal == null)
        })
        require(recipes.lessons.filter { it.ordinal != 249 }.all { it.sourceHardOrdinal == null })
        require(recipes.lessons.single { it.ordinal == 249 }.sourceHardOrdinal == 219)
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File, fromOrdinal: Int = 241, toOrdinal: Int = 249) {
        val recipes = load(root)
        require(fromOrdinal in 241..249 && toOrdinal in fromOrdinal..249)
        val selected = recipes.lessons.filter { it.ordinal in fromOrdinal..toOrdinal }
        DriveInContinuationService.generate(root, output, selected, fromOrdinal - 1, "drivein-closing", forcedWinNodeBudget = 150_000)
    }
}
