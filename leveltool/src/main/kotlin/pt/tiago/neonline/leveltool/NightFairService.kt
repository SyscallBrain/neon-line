package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Certified opening block for the Night Fair and its 9×7 connect-five tutorial. */
object NightFairService {
    private const val RECIPES = "templates/night-fair.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (451..460).toList())
        require(lessons.first().tutorialKey == "tut_451_five" && lessons.drop(1).all { it.tutorialKey == null })
        require(lessons.all { it.width == 9 && it.height == 7 && it.connect == 5 })
        require(lessons.all { it.familyId.startsWith("night-fair-") && it.openingPairsMin in 3..16 && it.openingPairsMax in it.openingPairsMin..16 })
        require(lessons.all { it.sourceOrdinals == null && it.sourceHardOrdinal == null && !it.popOut && it.rotateEvery == null && it.fadeAfter == null && it.moveClockSec == null })
        require(lessons.map { it.isDoubleNeon } == (451..460).map { it == 460 })
        require(lessons.take(3).all { it.openingPairsMax <= 4 })
        require(lessons.all { it.kind == "duel" && it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 450..459) { "Night Fair service expects campaign content through level 450–459" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 460) {
            "Night Fair continuation must begin after the last published level and end at 460"
        }
        DriveInContinuationService.generate(
            root = root, output = output, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
            seedScope = "night-fair-451-460", forcedWinNodeBudget = 150_000,
            stopId = "stop-10", rivalId = "wheel", calibrationGames = 8,
        )
    }
}
