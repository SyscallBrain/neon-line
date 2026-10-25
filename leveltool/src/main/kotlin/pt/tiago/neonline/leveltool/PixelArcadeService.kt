package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Completes Pixel Arcade and stages its final boss. */
object PixelArcadeService {
    private const val RECIPES = "templates/pixel-arcade.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (401..450).toList())
        require(lessons.take(10).map { it.kind } == listOf("duel", "puzzle", "duel", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"))
        require(lessons.drop(10).take(10).map { it.kind } == listOf("duel", "puzzle", "duel", "defense", "puzzle", "duel", "defense", "puzzle", "duel", "duel"))
        require(lessons.take(20).map { it.specialInventory } == listOf(
            mapOf("bomb" to 1), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(),
            mapOf("bomb" to 1), emptyMap(), emptyMap(), emptyMap(), mapOf("wild" to 1),
            emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), mapOf("wild" to 1),
            emptyMap(), emptyMap(), emptyMap(), mapOf("lock" to 1),
        ))
        require(lessons.drop(20).take(10).map { it.kind } == listOf("duel", "puzzle", "puzzle", "duel", "defense", "duel", "puzzle", "duel", "defense", "duel"))
        require(lessons.drop(30).take(10).map { it.kind } == listOf("puzzle", "duel", "duel", "defense", "puzzle", "duel", "duel", "defense", "puzzle", "duel"))
        require(lessons.drop(40).map { it.kind } == listOf("puzzle", "duel", "duel", "defense", "puzzle", "duel", "duel", "defense", "duel", "duel"))
        require(lessons.drop(20).take(10).map { it.specialInventory } == listOf(
            emptyMap(), emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), mapOf("bomb" to 1), emptyMap(), mapOf("wild" to 1), emptyMap(), emptyMap(),
        ))
        require(lessons.drop(30).take(10).map { it.specialInventory } == listOf(
            emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), emptyMap(), mapOf("wild" to 1),
        ))
        require(lessons.map { it.specialInventory } == lessons.take(40).map { it.specialInventory } + listOf(
            emptyMap(), emptyMap(), mapOf("lock" to 1), emptyMap(), emptyMap(), mapOf("bomb" to 1), emptyMap(), emptyMap(), mapOf("wild" to 1), emptyMap(),
        ))
        require(lessons.map { it.tutorialKey } == (401..450).map { when (it) { 401 -> "tut_401_bomb"; 410 -> "tut_410_wild"; 420 -> "tut_420_lock"; else -> null } })
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("pixel-arcade-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.sourceHardOrdinal == null &&
                !lesson.popOut && lesson.rotateEvery == null && lesson.fadeAfter == null && lesson.moveClockSec == null &&
                lesson.tutorialColumn == null
        })
        require(lessons.all { it.sourceOrdinals == null })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 2, 2) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "puzzle" }.all { it.kindByDifficulty == listOf("puzzle", "puzzle", "duel") })
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "duel" }.all { it.kindByDifficulty == null })
        require(lessons.map { it.isDoubleNeon } == (401..450).map { it == 410 || it == 420 || it == 430 || it == 440 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 400..449) { "Pixel Arcade service expects campaign content through level 400–449" }
        val nextLessons = recipes.lessons.filter { it.ordinal > sourceOrdinal }
        require(nextLessons.firstOrNull()?.ordinal == sourceOrdinal + 1 && nextLessons.lastOrNull()?.ordinal == 450) {
            "Pixel Arcade continuation must begin after the last published level and end at 450"
        }
        val ordinary = File(output.parentFile, "${output.name}-ordinary")
        val bossBase = File(output.parentFile, "${output.name}-boss-base")
        require(!output.exists() && !ordinary.exists() && !bossBase.exists()) { "Use fresh staging directories" }
        try {
            DriveInContinuationService.generate(
                root = root, output = ordinary, lessons = nextLessons, sourceOrdinal = sourceOrdinal,
                seedScope = "pixel-arcade-441-450", forcedWinNodeBudget = 30_000,
                stopId = "stop-09", rivalId = "pixel", calibrationGames = 8,
            )
            PixelArcadeBoss.run(ordinary, bossBase)
            BossContent.run(bossBase, output, setOf(450))
        } finally {
            ordinary.deleteRecursively()
            bossBase.deleteRecursively()
        }
    }
}
