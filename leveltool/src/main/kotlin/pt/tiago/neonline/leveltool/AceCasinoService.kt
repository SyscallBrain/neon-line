package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** First calibrated chapter of the Ace Casino combined-rules stop. */
object AceCasinoService {
    private const val RECIPES = "templates/ace-casino.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val expectedMechanics = listOf(
        setOf("popout", "clock"), setOf("popout", "bomb"), setOf("rotation", "clock"),
        setOf("fade", "bomb"), setOf("popout", "rotation"), setOf("fade", "lock"),
        setOf("rotation", "wild"), setOf("fade", "clock"), setOf("popout", "lock"),
        setOf("rotation", "bomb"),
    )

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (501..510).toList())
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("ace-casino-") &&
                lesson.kind == "duel" && lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 &&
                lesson.openingPairsMin in 3..16 && lesson.openingPairsMax in lesson.openingPairsMin..16 &&
                lesson.puzzleMoves == null && lesson.surviveAiMoves == null && lesson.kindByDifficulty == null &&
                lesson.sourceOrdinals == null && lesson.sourceHardOrdinal == null && lesson.tutorialKey == null &&
                lesson.tutorialColumn == null && lesson.moveClockSec?.let { it in 10..20 } != false &&
                lesson.rotateEvery?.let { it == 6 } != false && lesson.fadeAfter?.let { it == listOf(5, 3, 2) } != false &&
                lesson.specialInventory.keys.all { it in setOf("bomb", "lock", "wild") } &&
                lesson.specialInventory.values.all { it == 1 }
        })
        require(lessons.map(::mechanics) == expectedMechanics)
        require(lessons.take(3).all { it.openingPairsMax <= 4 })
        require(lessons.map { it.isDoubleNeon } == (501..510).map { it == 510 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 500..509) { "Ace Casino expects campaign content through level 500–509" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 510) {
            "Ace Casino must begin after the last published level and end at 510"
        }
        DriveInContinuationService.generate(
            root = root, output = output, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
            seedScope = "ace-casino-501-510", forcedWinNodeBudget = 150_000,
            stopId = "stop-11", rivalId = "ace", calibrationGames = 8,
        )
    }

    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }
}
