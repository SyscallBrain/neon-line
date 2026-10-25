package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

@Serializable
data class BowlingLesson(
    val ordinal: Int,
    val familyId: String,
    val titleEn: String,
    val titlePt: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val connect: Int,
    val mask: List<String>,
    val openingPairsMin: Int,
    val openingPairsMax: Int,
    val puzzleMoves: List<Int>? = null,
    val surviveAiMoves: List<Int>? = null,
    val tutorialKey: String? = null,
    val tutorialColumn: Int? = null,
    val isDoubleNeon: Boolean = false,
)

@Serializable
data class BowlingRecipes(val version: Int, val engineVersion: Int, val lessons: List<BowlingLesson>)

/** Geometry-aware generation for consecutive Bowling Strike blocks. */
object BowlingService {
    private const val RECIPES = "templates/bowling.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): BowlingRecipes =
        json.decodeFromString(BowlingRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: BowlingRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == 2)
        require(recipes.lessons.map { it.ordinal } == (151..200).toList())
        require(recipes.lessons.map { it.familyId }.distinct().size == 50)
        require(recipes.lessons.all { it.titleEn.isNotBlank() && it.titlePt.isNotBlank() })
        require(recipes.lessons.map { it.kind } == listOf(
            "duel","duel","duel","duel","puzzle","defense","duel","puzzle","defense","duel",
            "duel","puzzle","duel","defense","puzzle","defense","duel","puzzle","defense","duel",
            "duel","puzzle","defense","duel","puzzle","defense","duel","puzzle","defense","duel",
            "duel","puzzle","defense","duel","puzzle","defense","duel","puzzle","defense","duel",
            "duel","puzzle","defense","duel","puzzle","defense","duel","puzzle","duel","duel"))
        require(recipes.lessons.map { it.width to it.height } == listOf(
            5 to 4, 6 to 6, 8 to 7, 7 to 6, 6 to 6, 6 to 6, 8 to 7, 8 to 7, 8 to 7, 8 to 7,
            7 to 6, 5 to 4, 8 to 7, 6 to 6, 7 to 6, 8 to 7, 8 to 7, 8 to 7, 7 to 6, 8 to 7,
            6 to 6, 5 to 4, 7 to 6, 8 to 7, 6 to 6, 8 to 7, 5 to 4, 8 to 7, 7 to 6, 8 to 7,
            7 to 6, 6 to 6, 8 to 7, 5 to 4, 7 to 6, 6 to 6, 8 to 7, 5 to 4, 7 to 6, 8 to 7,
            6 to 6, 8 to 7, 7 to 6, 5 to 4, 6 to 6, 8 to 7, 7 to 6, 5 to 4, 8 to 7, 8 to 7))
        require(recipes.lessons.all { lesson -> lesson.connect == 4 && lesson.width in 5..8 && lesson.height in 4..7 })
        for (lesson in recipes.lessons) {
            require(lesson.mask.size == lesson.height && lesson.mask.all { row -> row.length == lesson.width && row.all { it == 'X' || it == '#' } })
            for (column in 0 until lesson.width) {
                val blocked = (0 until lesson.height).filter { row -> lesson.mask[row][column] == '#' }
                require(blocked.isEmpty() || blocked == (0 until blocked.size).toList() ||
                    blocked == (lesson.height - blocked.size until lesson.height).toList()) { "Mask blocks must touch the top or bottom: ${lesson.ordinal}/$column" }
            }
            require(lesson.openingPairsMin in 2..12 && lesson.openingPairsMax in lesson.openingPairsMin..12)
            val hasTutorial = lesson.tutorialKey != null
            require(hasTutorial == (lesson.tutorialColumn != null))
            if (hasTutorial) {
                val col = requireNotNull(lesson.tutorialColumn)
                require(col in 0 until lesson.width && lesson.mask.any { it[col] == 'X' })
            }
        }
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2,3,4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4,5,6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
        require(recipes.lessons.map { it.tutorialKey } == listOf(
            "tut_151_small_board","tut_152_pillars","tut_153_ceiling",null,null,null,null,null,null,null,
            null,null,null,null,null,null,null,null,null,null,
            null,null,null,null,null,null,null,null,null,null,
            null,null,null,null,null,null,null,null,null,null,
            null,null,null,null,null,null,null,null,null,null))
        require(recipes.lessons.map { it.isDoubleNeon } == (151..200).map { it == 160 || it == 170 || it == 180 || it == 190 })
    }

    /** Stage the next ten levels from a contiguous published campaign. */
    fun run(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val recipes = load(root)
        val published = Files.levels(root)
        val lastOrdinal = published.maxOfOrNull { it.ordinal } ?: 0
        require(published.map { it.ordinal } == (1..lastOrdinal).toList()) { "Expected a contiguous published campaign" }
        val lessons = recipes.lessons.filter { it.ordinal in (lastOrdinal + 1)..(lastOrdinal + 10) }
        require(lessons.map { it.ordinal } == ((lastOrdinal + 1)..(lastOrdinal + 10)).toList()) {
            "Expected ten recipes after level $lastOrdinal"
        }
        check(root.copyRecursively(output))
        val template = published.single { it.ordinal == lastOrdinal - 1 }
        val seen = published.flatMap { level -> level.variants.values.map { signature(it.toState(level.id, Difficulty.EASY)) } }.toMutableSet()
        for (lesson in lessons) {
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
                val base = template.variants.getValue(key).copy(
                    kind = kind, width = lesson.width, height = lesson.height, connect = lesson.connect,
                    mask = lesson.mask, pieces = emptyList(), targets = emptyList(), shapeRule = null,
                    rules = RulesDto(), inventory = emptyMap(), firstToMove = "P1", rival = "strike",
                    ai = strikeProfile(difficulty), bossRounds = emptyList())
                var selected: VariantDto? = null
                var path: List<Certificates.Step>? = null
                for (attempt in 0 until 250_000) {
                    val seed = Generator.seedFor("$id:$key:bowling:$attempt", lesson.ordinal, 4)
                    val rng = SplitMix64(SplitMix64.seedFromHex(seed))
                    val opening = opening(base, lesson, rng) ?: continue
                    val variant = base.copy(seedHex = seed, pieces = opening)
                    val start = variant.toState(id, difficulty)
                    if (DinerEntry.winning(start, Player.P1).isNotEmpty()) continue
                    val rivalThreats = DinerEntry.winning(start, Player.P2)
                    if (lesson.kind == "defense" && rivalThreats.isEmpty()) continue
                    if (lesson.kind != "defense" && rivalThreats.isNotEmpty()) continue
                    if (GameEngine.findLines(start.pieces, start.width, start.height, start.connect, Player.P1).isNotEmpty() ||
                        GameEngine.findLines(start.pieces, start.width, start.height, start.connect, Player.P2).isNotEmpty()) continue
                    val physical = signature(start)
                    if (physical in seen) continue
                    if (lesson.kind == "puzzle") {
                        val moves = requireNotNull(kind.winInMoves)
                        val proof = ForcedWin(150_000)
                        if (proof.canWin(start, moves - 1) != false) continue
                        val roots = proof.winningMoves(start, moves) ?: continue
                        if (roots.isEmpty() || (difficulty != Difficulty.EASY && roots.size != 1)) continue
                    }
                    val witness = witness(start, variant, lesson) ?: continue
                    val ownMoves = (witness.size + 1) / 2
                    val wins = calibration(start, variant, id)
                    selected = variant.copy(stars = StarsDto(ownMoves + 7, ownMoves + 2),
                        expectedMetrics = MetricsDto(wins / 16.0, ownMoves))
                    path = witness
                    seen += physical
                    println("bowling-service: $id/$key attempt=$attempt ${lesson.width}x${lesson.height} witness=$ownMoves sample=$wins/16")
                    break
                }
                check(selected != null && path != null) { "No certified Bowling candidate for $id/$key" }
                variants[key] = requireNotNull(selected)
                witnesses[difficulty] = requireNotNull(path)
            }
            val tutorial = lesson.tutorialKey?.let { key ->
                val column = requireNotNull(lesson.tutorialColumn)
                TutorialDto(listOf(TutorialStepDto(key, listOf(column), column)))
            }
            val level = template.copy(engineVersion = GameEngine.ENGINE_VERSION, id = id, ordinal = lesson.ordinal,
                stopId = "stop-04", titleEn = lesson.titleEn, titlePt = lesson.titlePt, familyId = lesson.familyId,
                learningTags = listOf("bowling", lesson.kind) + if (lesson.mask.any { '#' in it }) listOf("obstacles") else emptyList(),
                revision = 1, isDoubleNeon = lesson.isDoubleNeon, tutorial = tutorial, variants = variants)
            check(Validator.validateLevel(level).isEmpty()) { "Invalid Bowling level $id: ${Validator.validateLevel(level)}" }
            for ((difficulty, witness) in witnesses) {
                val certificate = Certificates.fromSteps(level, difficulty, witness)
                check(Certificates.verify(level, certificate).isEmpty()) { "Invalid witness $id/$difficulty" }
                File(output, "certificates/$id/${difficultyKey(difficulty)}.json").apply {
                    parentFile.mkdirs()
                    writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
                }
            }
            File(output, "levels/stop-04/$id.json").apply {
                parentFile.mkdirs()
                writeText(levelJson(level) + "\n")
            }
        }
        Certifier.rebuildCatalog(output, GameEngine.ENGINE_VERSION, Files.levels(output))
    }

    private fun opening(base: VariantDto, lesson: BowlingLesson, rng: SplitMix64): List<PieceDto>? {
        val pairs = lesson.openingPairsMin + rng.nextInt(lesson.openingPairsMax - lesson.openingPairsMin + 1)
        var state = base.copy(seedHex = "0123456789abcdef", pieces = emptyList()).toState("bowling-opening", Difficulty.EASY)
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

    private fun witness(start: GameState, variant: VariantDto, lesson: BowlingLesson): List<Certificates.Step>? {
        val column = lesson.tutorialColumn ?: return DinerEntry.witness(start, variant)
        val move = Move.Drop(column)
        if (move !in GameEngine.validMoves(start)) return null
        val after = GameEngine.apply(start, move)
        if (after.state.status != Status.InProgress) return null
        val tail = DinerEntry.witness(after.state, variant) ?: return null
        return listOf(Certificates.Step(move, start.aiStateHex)) + tail
    }

    private fun signature(state: GameState): String =
        "${state.width}x${state.height}:${state.playable.joinToString("") { if (it) "X" else "#" }}:${DinerEntry.signature(state)}"

    private fun calibration(start: GameState, variant: VariantDto, id: String): Int = (0 until 16).count { index ->
        val human = HumanAgent(HumanAgent.HumanPolicy.forDifficulty(start.difficulty),
            SplitMix64(0xB0A11L + id.hashCode() + index.toLong() * 97L))
        if (variant.kind.type == "defense") human.playDefense(start, variant.ai.toProfile(), requireNotNull(variant.kind.surviveAiMoves)).playerWon
        else human.play(start, variant.ai.toProfile(), variant.kind.winInMoves).playerWon
    }

    private fun strikeProfile(difficulty: Difficulty): AiDto = when (difficulty) {
        Difficulty.EASY -> AiDto(2, 2_000, 180, 5, 3, 4, 2, blunderMayMiss = true)
        Difficulty.MEDIUM -> AiDto(3, 8_000, 20, 6, 3, 5, 3)
        Difficulty.HARD -> AiDto(4, 20_000, 0, 8, 3, 6, 4)
    }
}
