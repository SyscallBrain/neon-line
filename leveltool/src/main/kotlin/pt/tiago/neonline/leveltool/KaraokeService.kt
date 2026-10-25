package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Opens Moon Karaoke with Diva and teaches the campaign's fading-neon rule. */
object KaraokeService {
    private const val RECIPES = "templates/moon-karaoke.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (351..360).toList())
        require(lessons.map { it.kind } == listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"))
        require(lessons.all { it.fadeAfter == listOf(5, 3, 2) })
        require(lessons.filter { it.popOut }.map { it.ordinal } == listOf(352, 356))
        require(lessons.filter { it.rotateEvery != null }.map { it.ordinal } == listOf(355, 358))
        require(lessons.all { it.rotateEvery == if (it.ordinal in setOf(355, 358)) 6 else null })
        require(lessons.map { it.isDoubleNeon } == (351..360).map { it == 360 })
        require(lessons.first().tutorialKey == "tut_351_fade" && lessons.first().tutorialColumn == null)
        require(lessons.drop(1).all { it.tutorialKey == null && it.tutorialColumn == null })
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("karaoke-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.sourceOrdinals == null && lesson.sourceHardOrdinal == null &&
                lesson.moveClockSec == null
        })
        require(lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        DriveInContinuationService.generate(
            root = root,
            output = output,
            lessons = recipes.lessons,
            sourceOrdinal = 350,
            seedScope = "moon-karaoke-351-360",
            forcedWinNodeBudget = 150_000,
            stopId = "stop-08",
            rivalId = "diva",
        )
    }
}
