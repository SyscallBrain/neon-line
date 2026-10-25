package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed continuation of the Night Fair connect-five campaign. */
object NightFairEncoreService {
    private const val RECIPES = "templates/night-fair-encore.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (471..480).toList())
        require(lessons.all {
            it.titleEn.isNotBlank() && it.titlePt.isNotBlank() && it.familyId.startsWith("night-fair-") &&
                it.kind == "duel" && it.width == 9 && it.height == 7 && it.connect == 5 &&
                it.openingPairsMin in 3..16 && it.openingPairsMax in it.openingPairsMin..16 &&
                it.puzzleMoves == null && it.surviveAiMoves == null && it.kindByDifficulty == null &&
                it.sourceOrdinals == null && it.sourceHardOrdinal == null && !it.popOut &&
                it.moveClockSec == null && it.rotateEvery == null && it.fadeAfter == null
        })
        require(lessons.take(3).all { it.openingPairsMax <= 4 })
        require(lessons.map { it.isDoubleNeon } == (471..480).map { it == 480 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 470..479) { "Night Fair encore expects campaign content through level 470–479" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 480) {
            "Night Fair encore must begin after the last published level and end at 480"
        }
        DriveInContinuationService.generate(
            root = root, output = output, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
            seedScope = "night-fair-471-480", forcedWinNodeBudget = 150_000,
            stopId = "stop-10", rivalId = "wheel", calibrationGames = 8,
        )
    }
}
