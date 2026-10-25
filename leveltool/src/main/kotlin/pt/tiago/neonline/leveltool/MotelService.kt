package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

@Serializable
data class MotelLesson(
    val ordinal: Int,
    val familyId: String,
    val titleEn: String,
    val titlePt: String,
    val kind: String,
    val shapeRule: String? = null,
    val targetCell: Int? = null,
    val puzzleMoves: Int? = null,
    val surviveAiMoves: Int? = null,
    val tutorialKey: String? = null,
    val tutorialColumn: Int? = null,
    val isDoubleNeon: Boolean = false,
)

@Serializable
data class MotelRecipes(val version: Int, val engineVersion: Int, val lessons: List<MotelLesson>)

/** Family-specific generation for the Flamingo Motel campaign blocks. */
object MotelService {
    private const val RECIPES = "templates/motel.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): MotelRecipes =
        json.decodeFromString(MotelRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: MotelRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == 2)
        require(recipes.lessons.map { it.ordinal } == (101..149).toList())
        require(recipes.lessons.map { it.familyId }.distinct().size == 49)
        require(recipes.lessons.all { it.titleEn.isNotBlank() && it.titlePt.isNotBlank() })
        require(recipes.lessons.take(10).map { it.kind } == listOf("shape","duel","puzzle","shape","duel","defense","shape","puzzle","shape","duel"))
        val cycle = listOf("duel","puzzle","defense","duel","puzzle","defense","duel","puzzle","defense","duel")
        require(recipes.lessons.drop(10).map { it.kind } == List(3) { cycle }.flatten() + cycle.take(9))
        val shapes = recipes.lessons.filter { it.kind == "shape" }
        require(shapes.map { it.ordinal } == listOf(101,104,107,109))
        require(shapes.map { it.shapeRule } == listOf("diagonal_only","through_target","horizontal_only","vertical_only"))
        require(shapes.all { (it.shapeRule == "through_target") == (it.targetCell != null) })
        require(recipes.lessons.filter { it.kind == "puzzle" }.map { it.puzzleMoves } == listOf(2,3) + List(4) { listOf(2,3,4) }.flatten())
        require(recipes.lessons.filter { it.kind == "defense" }.map { it.surviveAiMoves } == listOf(4) + List(4) { listOf(4,5,6) }.flatten())
        require(recipes.lessons.first().tutorialKey == "tut_101_diagonal" && recipes.lessons.first().tutorialColumn == 3)
        require(recipes.lessons.drop(1).all { it.tutorialKey == null && it.tutorialColumn == null })
        require(recipes.lessons.map { it.isDoubleNeon } == (101..149).map { it == 110 || it == 120 || it == 130 || it == 140 })
    }

    /** Generate recipes missing from the source catalog, retaining all published content. */
    fun run(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val recipes = load(root)
        val published = Files.levels(root)
        val maxPublished = published.maxOfOrNull { it.ordinal } ?: 0
        require(maxPublished in 100..600 && published.map { it.ordinal } == (1..maxPublished).toList()) {
            "Expected a contiguous published campaign ending between levels 100 and 600"
        }
        check(root.copyRecursively(output))
        val missingLessons = missingLessons(recipes, maxPublished)
        if (missingLessons.isEmpty()) return
        val template = published.first { it.ordinal == 54 }
        val seen = published.flatMap { level ->
            level.variants.values.map { DinerEntry.signature(it.toState(level.id, Difficulty.EASY)) }
        }.toMutableSet()
        for (lesson in missingLessons) {
            val id = "level-%04d".format(lesson.ordinal)
            val variants = linkedMapOf<String, VariantDto>()
            val witnesses = linkedMapOf<Difficulty, List<Certificates.Step>>()
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val base = template.variants.getValue(key).copy(
                    kind = KindDto(lesson.kind, winInMoves = lesson.puzzleMoves,
                        surviveAiMoves = lesson.surviveAiMoves, shapeRule = lesson.shapeRule),
                    pieces = emptyList(), targets = lesson.targetCell?.let(::listOf) ?: emptyList(),
                    shapeRule = lesson.shapeRule, rules = RulesDto(), inventory = emptyMap(),
                    firstToMove = "P1", rival = "flamingo", ai = profile(difficulty), bossRounds = emptyList())
                var selected: VariantDto? = null
                var path: List<Certificates.Step>? = null
                for (attempt in 0 until 250_000) {
                    val seed = Generator.seedFor("$id:$key:motel:$attempt", lesson.ordinal, 3)
                    val rng = SplitMix64.seedFromHex(seed).let(::SplitMix64)
                    val opening = if (lesson.kind == "shape") shapeOpening(lesson, difficulty, rng)
                        else opening(base, difficulty, lesson.kind == "puzzle", false, rng) ?: continue
                    val variant = base.copy(seedHex = seed, pieces = opening)
                    val start = variant.toState(id, difficulty)
                    if ((lesson.kind != "shape" && hasImmediateWin(start, Player.P1)) || hasImmediateWin(start, Player.P2)) continue
                    if (GameEngine.findLines(start.pieces, start.width, start.height, start.connect, Player.P1).isNotEmpty() ||
                        GameEngine.findLines(start.pieces, start.width, start.height, start.connect, Player.P2).isNotEmpty()) continue
                    val physical = DinerEntry.signature(start)
                    if (physical in seen) continue
                    if (lesson.kind == "puzzle") {
                        val moves = requireNotNull(lesson.puzzleMoves)
                        val proof = ForcedWin(150_000)
                        if (proof.canWin(start, moves - 1) != false) continue
                        val roots = proof.winningMoves(start, moves) ?: continue
                        if (roots.isEmpty() || (difficulty != Difficulty.EASY && roots.size != 1)) continue
                    }
                    val witness = if (lesson.kind == "shape") {
                        val move = lesson.tutorialColumn?.let(Move::Drop) ?: DinerEntry.winning(start, Player.P1).firstOrNull()
                        if (move == null || GameEngine.apply(start, move).state.status != Status.Won(Player.P1)) continue
                        listOf(Certificates.Step(move, start.aiStateHex))
                    } else DinerEntry.witness(start, variant) ?: continue
                    val ownMoves = (witness.size + 1) / 2
                    val wins = calibration(start, variant, id)
                    selected = variant.copy(
                        stars = StarsDto(ownMoves + 7, ownMoves + 2),
                        expectedMetrics = MetricsDto(wins / 16.0, ownMoves))
                    path = witness
                    seen += physical
                    println("motel-service: $id/$key attempt=$attempt witness=$ownMoves sample=$wins/16")
                    break
                }
                check(selected != null && path != null) { "No certified candidate for $id/$key" }
                variants[key] = requireNotNull(selected)
                witnesses[difficulty] = requireNotNull(path)
            }
            val lessonTags = listOf("motel", lesson.kind) + listOfNotNull(lesson.shapeRule)
            val level = template.copy(
                engineVersion = GameEngine.ENGINE_VERSION, id = id, ordinal = lesson.ordinal, stopId = "stop-03",
                titleEn = lesson.titleEn, titlePt = lesson.titlePt, familyId = lesson.familyId,
                learningTags = lessonTags, revision = 1, isDoubleNeon = lesson.isDoubleNeon,
                tutorial = lesson.tutorialKey?.let { TutorialDto(listOf(TutorialStepDto(it, listOf(requireNotNull(lesson.tutorialColumn)), lesson.tutorialColumn))) },
                variants = variants)
            check(Validator.validateLevel(level).isEmpty()) { "Invalid Motel level ${level.id}: ${Validator.validateLevel(level)}" }
            for ((difficulty, witness) in witnesses) {
                val certificate = Certificates.fromSteps(level, difficulty, witness)
                check(Certificates.verify(level, certificate).isEmpty()) { "Invalid witness $id/$difficulty" }
                File(output, "certificates/$id/${difficultyKey(difficulty)}.json").apply {
                    parentFile.mkdirs()
                    writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
                }
            }
            File(output, "levels/stop-03/$id.json").apply {
                parentFile.mkdirs()
                writeText(levelJson(level) + "\n")
            }
        }
        Certifier.rebuildCatalog(output, GameEngine.ENGINE_VERSION, Files.levels(output))
    }

    internal fun missingLessons(recipes: MotelRecipes, maxPublished: Int): List<MotelLesson> =
        recipes.lessons.filter { it.ordinal > maxPublished }

    private fun opening(base: VariantDto, difficulty: Difficulty, puzzle: Boolean, firstLesson: Boolean, rng: SplitMix64): List<PieceDto>? {
        val pairs = when {
            firstLesson -> 3 + rng.nextInt(3)
            puzzle -> 8 + rng.nextInt(3)
            difficulty == Difficulty.EASY -> 5 + rng.nextInt(3)
            difficulty == Difficulty.MEDIUM -> 7 + rng.nextInt(3)
            else -> 9 + rng.nextInt(3)
        }
        var state = base.copy(seedHex = "0123456789abcdef", pieces = emptyList()).toState("motel-opening", difficulty)
        repeat(pairs * 2) {
            val choices = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }
                .filter { it.status == Status.InProgress }
            if (choices.isEmpty()) return null
            state = choices[rng.nextInt(choices.size)]
        }
        return state.pieces.mapIndexedNotNull { index, piece ->
            piece?.let { PieceDto(index / state.width, index % state.width, requireNotNull(it.owner).name) }
        }
    }

    private fun shapeOpening(lesson: MotelLesson, difficulty: Difficulty, rng: SplitMix64): List<PieceDto> {
        val pieces = when (lesson.ordinal) {
            101 -> listOf(
                PieceDto(5,0,"P1"), PieceDto(5,1,"P1"), PieceDto(4,1,"P1"),
                PieceDto(5,2,"P2"), PieceDto(4,2,"P2"), PieceDto(3,2,"P1"),
                PieceDto(5,3,"P2"), PieceDto(4,3,"P1"), PieceDto(3,3,"P2"),
            )
            104 -> listOf(
                PieceDto(5,0,"P2"), PieceDto(4,0,"P1"), PieceDto(3,0,"P1"),
                PieceDto(5,1,"P1"), PieceDto(4,1,"P2"), PieceDto(3,1,"P1"),
                PieceDto(5,2,"P2"), PieceDto(4,2,"P1"), PieceDto(3,2,"P1"),
                PieceDto(5,3,"P1"), PieceDto(4,3,"P2"),
            )
            107 -> listOf(PieceDto(5,0,"P1"), PieceDto(5,1,"P1"), PieceDto(5,2,"P1"), PieceDto(5,6,"P2"))
            109 -> listOf(PieceDto(5,3,"P1"), PieceDto(4,3,"P1"), PieceDto(3,3,"P1"),
                PieceDto(5,if (difficulty == Difficulty.EASY) 0 else 6,"P2"))
            else -> error("No curated Motel shape opening for ${lesson.ordinal}")
        }
        // The seed still varies harmless side pieces so difficulty variants do not share positions.
        val occupied = pieces.map { it.r * 7 + it.c }.toSet()
        val safeSidePieces = when (lesson.ordinal) {
            101 -> listOf(PieceDto(5,5,"P1"), PieceDto(5,6,"P2"))
            104 -> listOf(PieceDto(5,4,"P2"), PieceDto(5,6,"P1"))
            107 -> listOf(PieceDto(5,4,"P2"), PieceDto(5,5,"P1"))
            else -> listOf(PieceDto(5,4,"P2"), PieceDto(5,5,"P1"))
        }.filter { it.r * 7 + it.c !in occupied }
        val extraCount = rng.nextInt(safeSidePieces.size + 1)
        return pieces + safeSidePieces.take(extraCount)
    }

    private fun hasImmediateWin(state: GameState, player: Player): Boolean {
        val probe = state.copy(toMove = player)
        return GameEngine.validMoves(probe).any { GameEngine.apply(probe, it).state.status == Status.Won(player) }
    }

    private fun calibration(start: GameState, variant: VariantDto, id: String): Int = (0 until 16).count { index ->
        val human = HumanAgent(HumanAgent.HumanPolicy.forDifficulty(start.difficulty),
            SplitMix64(0xF1A6L + id.hashCode() + index.toLong() * 97L))
        if (variant.kind.type == "defense") human.playDefense(start, variant.ai.toProfile(), requireNotNull(variant.kind.surviveAiMoves)).playerWon
        else human.play(start, variant.ai.toProfile(), variant.kind.winInMoves).playerWon
    }

    private fun profile(difficulty: Difficulty): AiDto = when (difficulty) {
        Difficulty.EASY -> AiDto(2, 2_000, 220, 3, 4, 2, 1, blunderMayMiss = true)
        Difficulty.MEDIUM -> AiDto(3, 8_000, 40, 4, 5, 3, 3)
        Difficulty.HARD -> AiDto(4, 20_000, 0, 6, 5, 3, 5)
    }
}
