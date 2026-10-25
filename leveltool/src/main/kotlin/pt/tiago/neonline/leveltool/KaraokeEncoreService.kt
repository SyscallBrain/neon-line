package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.rules.GameEngine

/** Recipe-backed Diva continuation for Moon Karaoke, levels 371–380. */
object KaraokeEncoreService {
    private const val RECIPES = "templates/moon-karaoke-followup-371-380.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (371..380).toList())
        require(lessons.map { it.kind } == listOf("duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel", "duel"))
        require(lessons.all { it.fadeAfter == listOf(5, 3, 2) })
        require(lessons.filter { it.popOut }.map { it.ordinal } == listOf(372, 376))
        require(lessons.filter { it.rotateEvery != null }.map { it.ordinal } == listOf(375, 378))
        require(lessons.all { it.rotateEvery == if (it.ordinal in setOf(375, 378)) 6 else null })
        require(lessons.map { it.isDoubleNeon } == (371..380).map { it == 380 })
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("karaoke-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.sourceOrdinals == null &&
                lesson.sourceHardOrdinal == null && lesson.tutorialKey == null && lesson.tutorialColumn == null &&
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
            sourceOrdinal = 370,
            seedScope = "moon-karaoke-371-380",
            forcedWinNodeBudget = 150_000,
            stopId = "stop-08",
            rivalId = "diva",
        )
    }
}
