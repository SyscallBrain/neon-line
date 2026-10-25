package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed certified continuation for Cinema Drive-In levels 231–240. */
object DriveInFinaleService {
    private const val RECIPES = "templates/drivein-finale.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (231..240).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(232, 233, 235, 238, 240))
        require(recipes.lessons.map { it.isDoubleNeon } == (231..240).map { it == 240 })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("drivein-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null &&
                lesson.tutorialColumn == null && lesson.sourceOrdinals == null &&
                (lesson.ordinal in setOf(236, 239) || lesson.sourceHardOrdinal == null)
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.ordinal != 236 }.all { it.sourceOrdinals == null })
        require(recipes.lessons.filter { it.ordinal !in setOf(236, 239) }.all { it.sourceHardOrdinal == null })
        require(recipes.lessons.single { it.ordinal == 236 }.sourceHardOrdinal == 219)
        require(recipes.lessons.single { it.ordinal == 239 }.sourceHardOrdinal == 219)
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    /** Stage a contiguous tail from the published campaign into a fresh output directory. */
    fun run(root: File, output: File, fromOrdinal: Int = 231, toOrdinal: Int = 240) {
        val recipes = load(root)
        require(fromOrdinal in 231..240 && toOrdinal in fromOrdinal..240)
        val selected = recipes.lessons.filter { it.ordinal in fromOrdinal..toOrdinal }
        DriveInContinuationService.generate(root, output, selected, fromOrdinal - 1, "drivein-finale", forcedWinNodeBudget = 150_000)
    }
}
