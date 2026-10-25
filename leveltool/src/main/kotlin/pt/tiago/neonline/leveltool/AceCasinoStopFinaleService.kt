package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.rules.GameEngine

/** Final recipe-backed block of Ace Casino, ending with Ace's three-round boss. */
object AceCasinoStopFinaleService {
    private const val RECIPES = "templates/ace-casino-stop-finale.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val expectedMechanics = listOf(
        setOf("popout", "clock"), setOf("bomb", "rotation"), setOf("fade", "lock"),
        setOf("clock", "wild"), setOf("bomb", "popout"), setOf("fade", "rotation"),
        setOf("lock", "rotation"), setOf("bomb", "fade"), setOf("popout", "wild"),
        setOf("lock", "wild"),
    )
    private val expectedMin = listOf(3,3,4,4,5,5,5,6,6,3)
    private val expectedMax = listOf(4,4,6,6,7,7,7,8,9,5)

    fun load(root: File): DriveInContinuationRecipes = json.decodeFromString(
        DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()
    ).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        val lessons = recipes.lessons
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(lessons.map { it.ordinal } == (541..550).toList())
        require(lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("ace-casino-") &&
                lesson.kind == "duel" && lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 &&
                lesson.puzzleMoves == null && lesson.surviveAiMoves == null && lesson.kindByDifficulty == null &&
                lesson.sourceOrdinals == null && lesson.sourceHardOrdinal == null && lesson.tutorialKey == null &&
                lesson.tutorialColumn == null && lesson.moveClockSec?.let { it in 10..20 } != false &&
                lesson.rotateEvery?.let { it == 6 } != false && lesson.fadeAfter?.let { it == listOf(5, 3, 2) } != false &&
                lesson.specialInventory.keys.all { it in setOf("bomb", "lock", "wild") } &&
                lesson.specialInventory.values.all { it == 1 }
        })
        require(lessons.map(::mechanics) == expectedMechanics)
        require(lessons.map { it.openingPairsMin } == expectedMin && lessons.map { it.openingPairsMax } == expectedMax)
        require(lessons.take(2).all { it.openingPairsMax <= 4 })
        require(lessons.map { it.isDoubleNeon } == (541..550).map { it == 550 })
    }

    fun run(root: File, output: File) {
        val recipes = load(root)
        val sourceOrdinal = Files.levels(root).maxOfOrNull { it.ordinal } ?: error("Published campaign is empty")
        require(sourceOrdinal in 540..549) { "Ace Casino finale expects campaign content through level 540–549" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val expandedRoot = File(output.parentFile, "${output.name}-source")
        val ordinary = File(output.parentFile, "${output.name}-ordinary")
        val bossBase = File(output.parentFile, "${output.name}-boss-base")
        require(!expandedRoot.exists() && !ordinary.exists() && !bossBase.exists()) { "Use fresh staging directories" }
        try {
            check(root.copyRecursively(expandedRoot))
            val catalog = json.decodeFromString(CatalogDto.serializer(), File(expandedRoot, "catalog.json").readText())
            val stops = catalog.stops.map { stop -> if (stop.id == "stop-11") stop.copy(to = 550) else stop }
            check(stops.any { it.id == "stop-11" && it.from == 501 && it.to == 550 })
            File(expandedRoot, "catalog.json").writeText(json.encodeToString(CatalogDto.serializer(), catalog.copy(stops = stops)) + "\n")
            DriveInContinuationService.generate(
                root = expandedRoot, output = ordinary, lessons = recipes.lessons.filter { it.ordinal > sourceOrdinal },
                sourceOrdinal = sourceOrdinal, seedScope = "ace-casino-541-550", forcedWinNodeBudget = 150_000,
                stopId = "stop-11", rivalId = "ace", calibrationGames = 8,
            )
            AceCasinoBoss.run(ordinary, bossBase)
            BossContent.run(bossBase, output, setOf(550))
        } finally {
            expandedRoot.deleteRecursively()
            ordinary.deleteRecursively()
            bossBase.deleteRecursively()
        }
    }

    private fun mechanics(lesson: DriveInLesson): Set<String> = buildSet {
        if (lesson.popOut) add("popout")
        if (lesson.rotateEvery != null) add("rotation")
        if (lesson.fadeAfter != null) add("fade")
        if (lesson.moveClockSec != null) add("clock")
        addAll(lesson.specialInventory.keys)
    }
}
