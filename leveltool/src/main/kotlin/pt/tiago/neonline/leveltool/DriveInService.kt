package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

@Serializable
data class DriveInLesson(
    val ordinal: Int,
    val familyId: String,
    val titleEn: String,
    val titlePt: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val connect: Int,
    val openingPairsMin: Int,
    val openingPairsMax: Int,
    val puzzleMoves: List<Int>? = null,
    val surviveAiMoves: List<Int>? = null,
    val popOut: Boolean = false,
    val tutorialKey: String? = null,
    val tutorialColumn: Int? = null,
    val isDoubleNeon: Boolean = false,
    val sourceOrdinals: List<Int>? = null,
    val sourceHardOrdinal: Int? = null,
    val mirrorSourceHard: Boolean = false,
    val moveClockSec: Int? = null,
    val rotateEvery: Int? = null,
    val fadeAfter: List<Int>? = null,
    /** Special-piece counts granted symmetrically to both players. */
    val specialInventory: Map<String, Int> = emptyMap(),
    /** Optional per-difficulty objective overrides, used for mixed puzzle/duel variants. */
    val kindByDifficulty: List<String>? = null,
)

@Serializable
data class DriveInRecipes(val version: Int, val engineVersion: Int, val lessons: List<DriveInLesson>)

/** Certified opening block for the Cinema Drive-In and its Pop Out tutorial. */
object DriveInService {
    private const val RECIPES = "templates/drivein.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInRecipes =
        json.decodeFromString(DriveInRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (201..210).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.map { it.popOut } == listOf(true, false, true, false, true, false, false, true, false, true))
        require(recipes.lessons.map { it.isDoubleNeon } == (201..210).map { it == 210 })
        require(recipes.lessons.all {
            it.titleEn.isNotBlank() && it.titlePt.isNotBlank() && it.familyId.startsWith("drivein-") &&
                it.width == 7 && it.height == 6 && it.connect == 4 && it.openingPairsMin in 3..16 &&
                it.openingPairsMax in it.openingPairsMin..16
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
        require(recipes.lessons.map { it.tutorialKey } == listOf("tut_201_popout", null, null, null, null, null, null, null, null, null))
        require(recipes.lessons.map { it.tutorialColumn } == listOf(3, null, null, null, null, null, null, null, null, null))
        require(recipes.lessons.map { it.sourceOrdinals } == listOf(null, null, listOf(2, 75, 69), null, null, null, null, null, null, null))
    }

    /** Stage ten new certified levels from the frozen 200-level campaign. */
    fun run(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val recipes = load(root)
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..200).toList()) { "Expected a published campaign through level 200" }
        check(root.copyRecursively(output))
        val template = published.single { it.ordinal == 200 }
        val sourcePuzzles = recipes.lessons.flatMap { it.sourceOrdinals.orEmpty() }.distinct().associateWith { ordinal ->
            published.single { it.ordinal == ordinal }
        }
        val seen = published.flatMap { level -> level.variants.values.map { signature(it.toState(level.id, Difficulty.EASY)) } }.toMutableSet()

        for (lesson in recipes.lessons) {
            val id = "level-%04d".format(lesson.ordinal)
            val variants = linkedMapOf<String, VariantDto>()
            val witnesses = linkedMapOf<Difficulty, List<Certificates.Step>>()
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val kind = when (lesson.kind) {
                    "puzzle" -> KindDto("puzzle", winInMoves = requireNotNull(lesson.puzzleMoves)[difficulty.ordinal])
                    "defense" -> KindDto("defense", surviveAiMoves = requireNotNull(lesson.surviveAiMoves)[difficulty.ordinal])
                    else -> KindDto("duel")
                }
                val sourceOrdinal = lesson.sourceOrdinals?.get(difficulty.ordinal)
                val source = sourceOrdinal?.let { sourcePuzzles.getValue(it).variants.getValue(key) }
                val base = (source ?: template.variants.getValue(key)).copy(
                    kind = kind, width = 7, height = 6, connect = 4, mask = source?.mask ?: List(6) { "XXXXXXX" },
                    pieces = source?.pieces ?: emptyList(), targets = emptyList(), shapeRule = null,
                    rules = RulesDto(popOut = lesson.popOut), inventory = emptyMap(), firstToMove = "P1",
                    rival = "projectionist", ai = projectionistProfile(difficulty, lesson.popOut, lesson.ordinal == 201), bossRounds = emptyList())

                var selected: VariantDto? = null
                var path: List<Certificates.Step>? = null
                for (attempt in 0 until 250_000) {
                    if (attempt > 0 && attempt % 1_000 == 0) println("drivein-service: searching $id/$key attempt=$attempt")
                    val seed = Generator.seedFor("$id:$key:drivein:$attempt", lesson.ordinal, 5)
                    val rng = SplitMix64(SplitMix64.seedFromHex(seed))
                    val pieces = source?.pieces ?: opening(base, lesson, rng) ?: continue
                    val variant = base.copy(seedHex = seed, pieces = pieces)
                    val start = variant.toState(id, difficulty)
                    if (lesson.kind == "duel") {
                        if (DinerEntry.winning(start, Player.P1).isNotEmpty() || DinerEntry.winning(start, Player.P2).isNotEmpty()) continue
                    } else if (lesson.kind == "defense") {
                        if (DinerEntry.winning(start, Player.P1).isNotEmpty() || DinerEntry.winning(start, Player.P2).isEmpty()) continue
                    }
                    val physical = signature(start)
                    if (physical in seen) continue
                    if (lesson.tutorialColumn?.let { Move.Pop(it) !in GameEngine.validMoves(start) } == true) continue
                    if (lesson.kind == "puzzle") {
                        val moves = requireNotNull(kind.winInMoves)
                        val proof = ForcedWin(30_000)
                        if (proof.canWin(start, moves - 1) != false) continue
                        val roots = proof.winningMoves(start, moves) ?: continue
                        if (roots.isEmpty() || (difficulty != Difficulty.EASY && roots.size != 1)) continue
                    }
                    val witness = DinerEntry.witness(start, variant) ?: continue
                    val ownMoves = (witness.size + 1) / 2
                    if (ownMoves < if (lesson.kind == "puzzle") requireNotNull(kind.winInMoves) else 3) continue
                    val humanWinRate = if (lesson.ordinal == 201) {
                        calibrateTutorial(start, variant.ai.toProfile())
                    } else 0.0
                    if (lesson.ordinal == 201 && humanWinRate !in Calibrator.band(difficulty, false, novelty = true)) continue
                    selected = variant.copy(stars = StarsDto(ownMoves + 7, ownMoves + 2),
                        expectedMetrics = MetricsDto(humanWinRate, ownMoves))
                    path = witness
                    seen += physical
                    println("drivein-service: $id/$key attempt=$attempt witness=$ownMoves PopOut=${lesson.popOut}")
                    break
                }
                check(selected != null && path != null) { "No certified Drive-In candidate for $id/$key" }
                variants[key] = requireNotNull(selected)
                witnesses[difficulty] = requireNotNull(path)
            }
            val tutorial = lesson.tutorialKey?.let { key ->
                TutorialDto(listOf(TutorialStepDto(key, listOf(requireNotNull(lesson.tutorialColumn)), null)))
            }
            val level = template.copy(
                engineVersion = GameEngine.ENGINE_VERSION, id = id, ordinal = lesson.ordinal, stopId = "stop-05",
                titleEn = lesson.titleEn, titlePt = lesson.titlePt, familyId = lesson.familyId,
                learningTags = listOf("drivein", lesson.kind) + if (lesson.popOut) listOf("popout") else emptyList(),
                revision = 1, isDoubleNeon = lesson.isDoubleNeon, tutorial = tutorial, variants = variants)
            check(Validator.validateLevel(level).isEmpty()) { "Invalid Drive-In level $id: ${Validator.validateLevel(level)}" }
            for ((difficulty, witness) in witnesses) {
                val certificate = Certificates.fromSteps(level, difficulty, witness)
                check(Certificates.verify(level, certificate).isEmpty()) { "Invalid witness $id/$difficulty" }
                File(output, "certificates/$id/${difficultyKey(difficulty)}.json").apply {
                    parentFile.mkdirs()
                    writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
                }
            }
            File(output, "levels/stop-05/$id.json").apply {
                parentFile.mkdirs()
                writeText(levelJson(level) + "\n")
            }
        }
        Certifier.rebuildCatalog(output, GameEngine.ENGINE_VERSION, Files.levels(output))
    }

    private fun opening(base: VariantDto, lesson: DriveInLesson, rng: SplitMix64): List<PieceDto>? {
        val pairs = lesson.openingPairsMin + rng.nextInt(lesson.openingPairsMax - lesson.openingPairsMin + 1)
        var state = base.copy(seedHex = "0123456789abcdef", pieces = emptyList()).toState("drivein-opening", Difficulty.EASY)
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

    private fun signature(state: GameState): String =
        "${state.width}x${state.height}:${state.playable.joinToString("") { if (it) "X" else "#" }}:pop=${state.popOut}:${DinerEntry.signature(state)}"

    private fun projectionistProfile(difficulty: Difficulty, popOut: Boolean, tutorial: Boolean): AiDto {
        if (tutorial) return AiDto(1, 1_000, 1_000, 1, 1, 1, 1, prefersPop = popOut, blunderMayMiss = true)
        return when (difficulty) {
            Difficulty.EASY -> AiDto(2, 2_000, 220, 3, 4, 2, 5, prefersPop = popOut, blunderMayMiss = true)
            Difficulty.MEDIUM -> AiDto(3, 8_000, 30, 3, 4, 2, 5, prefersPop = popOut)
            Difficulty.HARD -> AiDto(4, 20_000, 0, 3, 4, 2, 5, prefersPop = popOut)
        }
    }

    private fun calibrateTutorial(start: GameState, rival: AiProfile): Double {
        val policy = HumanAgent.HumanPolicy.forDifficulty(start.difficulty)
        val wins = (0 until 32).count { index ->
            val seed = 0xCA11B + index.toLong() * 31 + start.levelId.hashCode()
            HumanAgent(policy, SplitMix64(seed)).play(start, rival).playerWon
        }
        return wins / 32.0
    }
}
