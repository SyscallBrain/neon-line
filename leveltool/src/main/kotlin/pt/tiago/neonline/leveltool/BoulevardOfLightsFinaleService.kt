package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed final block for Stop 12, ending with Queen's boss at level 600. */
object BoulevardOfLightsFinaleService {
    private const val RECIPES = "templates/boulevard-of-lights-finale.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (591..600).toList())
        require(lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("boulevard-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 &&
                lesson.openingPairsMin in 3..16 && lesson.openingPairsMax in lesson.openingPairsMin..16 &&
                lesson.tutorialKey == null && lesson.tutorialColumn == null && lesson.sourceOrdinals == null
        })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "puzzle" }.associate { it.ordinal to it.sourceHardOrdinal } ==
            mapOf(593 to 279, 596 to 219, 599 to 279))
        require(lessons.filter { it.mirrorSourceHard }.map { it.ordinal } == listOf(596))
        require(lessons.filter { it.kind != "puzzle" }.all { it.sourceOrdinals == null && it.sourceHardOrdinal == null })
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null && it.sourceHardOrdinal == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null && it.sourceHardOrdinal == null })
        require(lessons.map { it.isDoubleNeon } == (591..600).map { it == 600 })
        require(lessons.last().specialInventory.keys == setOf("bomb", "wild"))
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 590..599) { "Boulevard finale expects campaign content through level 590–599" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 600) {
            "Boulevard finale must begin after the last published level and end at 600"
        }
        val ordinary = File(output.parentFile, "${output.name}-ordinary")
        val bossBase = File(output.parentFile, "${output.name}-boss-base")
        require(!output.exists() && !ordinary.exists() && !bossBase.exists()) { "Use fresh staging directories" }
        try {
            DriveInContinuationService.generate(
                root = root, output = ordinary, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
                seedScope = "boulevard-of-lights-591-600", forcedWinNodeBudget = 150_000,
                stopId = "stop-12", rivalId = "queen", calibrationGames = 8,
            )
            BoulevardOfLightsFinaleBoss.run(ordinary, bossBase)
            BossContent.run(bossBase, output, setOf(600))
        } finally {
            ordinary.deleteRecursively()
            bossBase.deleteRecursively()
        }
    }
}
