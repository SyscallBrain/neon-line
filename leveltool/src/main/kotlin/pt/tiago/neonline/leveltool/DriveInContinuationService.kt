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
data class DriveInContinuationRecipes(val version: Int, val engineVersion: Int, val lessons: List<DriveInLesson>)

/** Recipe-backed certified continuation for the Cinema Drive-In, levels 211–220. */
object DriveInContinuationService {
    private const val RECIPES = "templates/drivein-followup.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (211..220).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(212, 213, 215, 218, 220))
        require(recipes.lessons.map { it.isDoubleNeon } == (211..220).map { it == 220 })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("drivein-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null && it.sourceOrdinals == null })
        require(recipes.lessons.filter { it.kind != "puzzle" }.all { it.sourceOrdinals == null && it.sourceHardOrdinal == null })
        require(recipes.lessons.all { it.sourceHardOrdinal == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    /** Build a fresh stage from the published, contiguous 210-level campaign. */
    fun run(root: File, output: File) = generate(root, output, load(root).lessons, 210, "drivein-followup")

    internal fun generate(
        root: File,
        output: File,
        lessons: List<DriveInLesson>,
        sourceOrdinal: Int,
        seedScope: String,
        forcedWinNodeBudget: Int = 30_000,
        stopId: String = "stop-05",
        rivalId: String = "projectionist",
        preserveSourceSeed: Boolean = false,
        calibrationGames: Int = 32,
    ) {
        val rootPath = root.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!outputPath.startsWith(rootPath) && !rootPath.startsWith(outputPath)) { "Use a separate staging directory" }
        require(!output.exists()) { "Use a fresh staging directory" }
        val published = Files.levels(root)
        require(published.map { it.ordinal } == (1..sourceOrdinal).toList()) { "Expected a contiguous published campaign through level $sourceOrdinal" }
        check(root.copyRecursively(output))
        val template = published.single { it.ordinal == sourceOrdinal }
        val sourceOrdinals = lessons.flatMap { it.sourceOrdinals.orEmpty() } + lessons.mapNotNull { it.sourceHardOrdinal }
        val sourcePuzzles = sourceOrdinals.distinct().associateWith { ordinal ->
            published.single { it.ordinal == ordinal }
        }
        val seen = published.flatMap { level -> level.variants.values.map { signature(it.toState(level.id, Difficulty.EASY)) } }.toMutableSet()

        for (lesson in lessons) {
            val id = "level-%04d".format(lesson.ordinal)
            val variants = linkedMapOf<String, VariantDto>()
            val witnesses = linkedMapOf<Difficulty, List<Certificates.Step>>()
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val lessonKind = lesson.kindByDifficulty?.get(difficulty.ordinal) ?: lesson.kind
                val kind = when (lessonKind) {
                    "puzzle" -> KindDto("puzzle", winInMoves = requireNotNull(lesson.puzzleMoves)[difficulty.ordinal])
                    "defense" -> KindDto("defense", surviveAiMoves = requireNotNull(lesson.surviveAiMoves)[difficulty.ordinal])
                    else -> KindDto("duel")
                }
                val seedBase = template.variants.getValue(key)
                val empty = seedBase.copy(kind = kind, width = lesson.width, height = lesson.height, connect = lesson.connect,
                    mask = List(lesson.height) { "X".repeat(lesson.width) }, pieces = emptyList(), targets = emptyList(), shapeRule = null,
                    rules = RulesDto(popOut = lesson.popOut, rotateEvery = lesson.rotateEvery, moveClockSec = lesson.moveClockSec,
                        fadeAfter = lesson.fadeAfter?.get(difficulty.ordinal)),
                    inventory = if (lesson.specialInventory.isEmpty()) emptyMap() else mapOf("P1" to lesson.specialInventory, "P2" to lesson.specialInventory),
                    firstToMove = "P1",
                    rival = rivalId, bossRounds = emptyList())
                var selected: VariantDto? = null
                var witness: List<Certificates.Step>? = null
                for (attempt in 0 until 250_000) {
                    if (attempt > 0 && attempt % 5_000 == 0) println("$seedScope: searching $id/$key attempt=$attempt")
                    if (attempt > 0 && (lesson.sourceOrdinals?.get(difficulty.ordinal) != null ||
                        (difficulty == Difficulty.HARD && lesson.sourceHardOrdinal != null))) break
                    val seed = Generator.seedFor("$id:$key:$seedScope:$attempt", lesson.ordinal, 5)
                    val rng = SplitMix64(SplitMix64.seedFromHex(seed))
                    val sourceOrdinal = if (difficulty == Difficulty.HARD) lesson.sourceHardOrdinal
                        else lesson.sourceOrdinals?.get(difficulty.ordinal)
                    val source = sourceOrdinal?.let { sourcePuzzles.getValue(it).variants.getValue(key) }
                    val pieces = source?.pieces?.let { sourcePieces ->
                        if (difficulty == Difficulty.HARD && lesson.sourceHardOrdinal != null && !lesson.mirrorSourceHard) sourcePieces
                        else sourcePieces.map { piece -> piece.copy(c = lesson.width - 1 - piece.c) }
                    } ?: opening(empty, lesson, rng) ?: continue
                    val candidate = empty.copy(
                        seedHex = if (preserveSourceSeed && source != null) source.seedHex else seed,
                        pieces = pieces,
                        mask = source?.mask ?: empty.mask,
                    )
                    val start = candidate.toState(id, difficulty)
                    if (lesson.popOut && GameEngine.validMoves(start).none { it is Move.Pop }) continue
                    if (lessonKind == "duel") {
                        if (DinerEntry.winning(start, Player.P1).isNotEmpty() || DinerEntry.winning(start, Player.P2).isNotEmpty()) continue
                    } else if (lessonKind == "defense") {
                        if (DinerEntry.winning(start, Player.P1).isNotEmpty() || DinerEntry.winning(start, Player.P2).isEmpty()) continue
                    }
                    val physical = signature(start)
                    if (physical in seen && source == null) continue
                    if (lessonKind == "puzzle") {
                        val target = requireNotNull(kind.winInMoves)
                        val proof = ForcedWin(forcedWinNodeBudget)
                        if (proof.canWin(start, target - 1) != false) {
                            if (source != null) println("$seedScope: source $sourceOrdinal $id/$key is not exact in $target moves")
                            continue
                        }
                        val roots = proof.winningMoves(start, target) ?: continue
                        if (roots.isEmpty() || (difficulty != Difficulty.EASY && roots.size != 1)) {
                            if (source != null) println("$seedScope: source $sourceOrdinal $id/$key has ${roots.size} winning first moves")
                            continue
                        }
                    }
                    for (ai in profiles(difficulty, lesson.popOut, rivalId)) {
                        val calibratedCandidate = candidate.copy(ai = ai)
                        val band = Calibrator.band(difficulty, lesson.isDoubleNeon, novelty = Calibrator.isNoveltyIntroduction(lesson.ordinal))
                        val quickRate = calibrate(start, ai.toProfile(), kind, id, 8)
                        if (quickRate !in (band.start - 0.25)..(band.endInclusive + 0.25)) {
                            if (source != null) println("$seedScope: source $sourceOrdinal $id/$key quick=$quickRate profile=$ai")
                            continue
                        }
                        val rate = if (calibrationGames <= 8) quickRate else calibrate(start, ai.toProfile(), kind, id, calibrationGames)
                        if (rate !in band) {
                            if (source != null) println("$seedScope: source $sourceOrdinal $id/$key profile=$ai rate=$rate out of band")
                            continue
                        }
                        val path = DinerEntry.witness(start, calibratedCandidate)
                        if (path == null) {
                            if (source != null) println("$seedScope: source $sourceOrdinal $id/$key profile=$ai has no rival-replayable witness")
                            continue
                        }
                        val ownMoves = (path.size + 1) / 2
                        if (ownMoves < if (lessonKind == "puzzle") requireNotNull(kind.winInMoves) else 3) continue
                        selected = calibratedCandidate.copy(stars = StarsDto(ownMoves + 7, ownMoves + 2),
                            expectedMetrics = MetricsDto(rate, ownMoves))
                        witness = path
                        break
                    }
                    if (selected != null) {
                        seen += physical
                        println("$seedScope: $id/$key attempt=$attempt witness=${selected.expectedMetrics.solverMoves} rate=${selected.expectedMetrics.humanWinRate}")
                        break
                    }
                }
                check(selected != null && witness != null) { "No calibrated certified Drive-In candidate for $id/$key" }
                variants[key] = requireNotNull(selected)
                witnesses[difficulty] = requireNotNull(witness)
            }
            val level = template.copy(engineVersion = GameEngine.ENGINE_VERSION, id = id, ordinal = lesson.ordinal,
                stopId = stopId, titleEn = lesson.titleEn, titlePt = lesson.titlePt, familyId = lesson.familyId,
                learningTags = listOf(when (stopId) { "stop-07" -> "disco-rink"; "stop-08" -> "moon-karaoke"; "stop-09" -> "pixel-arcade"; "stop-10" -> "night-fair"; "stop-11" -> "ace-casino"; "stop-12" -> "boulevard-of-lights"; else -> "drivein" }, lesson.kind) +
                    (if (lesson.popOut) listOf("popout") else emptyList()) +
                    (if (lesson.rotateEvery != null) listOf("rotation") else emptyList()) +
                    (if (lesson.moveClockSec != null) listOf("clock") else emptyList()) +
                    (if (lesson.fadeAfter != null) listOf("fade") else emptyList()) + lesson.specialInventory.keys.map { it.lowercase() },
                revision = 1, isDoubleNeon = lesson.isDoubleNeon,
                tutorial = lesson.tutorialKey?.let { key ->
                    TutorialDto(listOf(TutorialStepDto(key, lesson.tutorialColumn?.let(::listOf).orEmpty(), lesson.tutorialColumn)))
                }, variants = variants)
            val errors = Validator.validateLevel(level)
            check(errors.isEmpty()) { "Invalid Drive-In level $id: $errors" }
            for ((difficulty, steps) in witnesses) {
                val certificate = Certificates.fromSteps(level, difficulty, steps)
                val certErrors = Certificates.verify(level, certificate)
                check(certErrors.isEmpty()) { "Invalid witness $id/$difficulty: $certErrors" }
                File(output, "certificates/$id/${difficultyKey(difficulty)}.json").apply {
                    parentFile.mkdirs()
                    writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
                }
            }
            File(output, "levels/$stopId/$id.json").apply {
                parentFile.mkdirs()
                writeText(levelJson(level) + "\n")
            }
        }
        Certifier.rebuildCatalog(output, GameEngine.ENGINE_VERSION, Files.levels(output))
    }

    private fun opening(base: VariantDto, lesson: DriveInLesson, rng: SplitMix64): List<PieceDto>? {
        val pairs = lesson.openingPairsMin + rng.nextInt(lesson.openingPairsMax - lesson.openingPairsMin + 1)
        var state = base.copy(seedHex = "0123456789abcdef", width = lesson.width, height = lesson.height, connect = lesson.connect,
            mask = List(lesson.height) { "X".repeat(lesson.width) }, pieces = emptyList(), rules = base.rules.copy(rotateEvery = null))
            .toState("drivein-opening", Difficulty.EASY)
        repeat(pairs * 2) {
            val choices = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }
                .filter { it.status == Status.InProgress }
            if (choices.isEmpty()) return null
            state = choices[rng.nextInt(choices.size)]
        }
        return state.pieces.mapIndexedNotNull { index, piece -> piece?.let {
            PieceDto(index / state.width, index % state.width, requireNotNull(it.owner).name)
        } }
    }

    private fun profiles(difficulty: Difficulty, popOut: Boolean, rivalId: String): List<AiDto> = when {
        rivalId == "wheel" -> listOf(when (difficulty) {
            Difficulty.EASY -> AiDto(2, 400, 420, 3, 4, 2, 3, blunderMayMiss = true)
            Difficulty.MEDIUM -> AiDto(2, 600, 260, 4, 5, 3, 3, blunderMayMiss = true)
            Difficulty.HARD -> AiDto(3, 1_000, 40, 5, 5, 3, 4)
        })
        rivalId == "skater" -> skaterProfiles(difficulty, popOut)
        rivalId == "diva" -> divaProfiles(difficulty, popOut)
        else -> when (difficulty) {
        Difficulty.EASY -> listOf(
            AiDto(3, 5_000, 160, 3, 4, 2, 3, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 5_000, 120, 3, 4, 2, 3, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 5_000, 80, 3, 4, 2, 3, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 8_000, 40, 4, 5, 3, 4, prefersPop = popOut),
            AiDto(4, 10_000, 0, 4, 5, 3, 4, prefersPop = popOut),
        )
        Difficulty.MEDIUM -> listOf(
            AiDto(1, 500, 600, 2, 3, 1, 2, prefersPop = popOut, blunderMayMiss = true),
            AiDto(1, 500, 500, 2, 3, 1, 2, prefersPop = popOut, blunderMayMiss = true),
            AiDto(1, 1_000, 400, 2, 3, 2, 2, prefersPop = popOut, blunderMayMiss = true),
            AiDto(2, 1_000, 500, 2, 3, 2, 3, prefersPop = popOut, blunderMayMiss = true),
            AiDto(2, 2_000, 350, 3, 4, 2, 3, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 3_000, 250, 3, 4, 2, 4, prefersPop = popOut, blunderMayMiss = true),
        )
        Difficulty.HARD -> listOf(
            AiDto(1, 500, 0, 2, 3, 1, 1, prefersPop = popOut),
            AiDto(1, 700, 0, 2, 3, 1, 1, prefersPop = popOut),
            AiDto(1, 500, 0, 2, 3, 1, 2, prefersPop = popOut),
            AiDto(2, 500, 0, 2, 3, 1, 1, prefersPop = popOut),
        )
        }
    }

    private fun divaProfiles(difficulty: Difficulty, popOut: Boolean): List<AiDto> = when (difficulty) {
        Difficulty.EASY -> listOf(
            AiDto(3, 5_000, 160, 3, 4, 2, 3, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 5_000, 120, 3, 4, 2, 3, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 5_000, 80, 3, 4, 2, 3, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 8_000, 40, 4, 5, 3, 4, mirror = true, prefersPop = popOut),
            AiDto(4, 10_000, 0, 4, 5, 3, 4, mirror = true, prefersPop = popOut),
        )
        Difficulty.MEDIUM -> listOf(
            AiDto(1, 500, 600, 2, 3, 1, 2, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(1, 500, 500, 2, 3, 1, 2, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(1, 1_000, 400, 2, 3, 2, 2, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(2, 1_000, 500, 2, 3, 2, 3, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(2, 2_000, 350, 3, 4, 2, 3, mirror = true, prefersPop = popOut, blunderMayMiss = true),
            AiDto(3, 3_000, 250, 3, 4, 2, 4, mirror = true, prefersPop = popOut, blunderMayMiss = true),
        )
        Difficulty.HARD -> listOf(
            AiDto(1, 500, 0, 2, 3, 1, 1, mirror = true, prefersPop = popOut),
            AiDto(1, 700, 0, 2, 3, 1, 1, mirror = true, prefersPop = popOut),
            AiDto(1, 500, 0, 2, 3, 1, 2, mirror = true, prefersPop = popOut),
            AiDto(2, 500, 0, 2, 3, 1, 1, mirror = true, prefersPop = popOut),
        )
    }

    private fun skaterProfiles(difficulty: Difficulty, popOut: Boolean): List<AiDto> = when (difficulty) {
        Difficulty.EASY -> listOf(
            AiDto(2, 2_000, 140, 3, 4, 2, 3, blunderMayMiss = true),
            AiDto(2, 3_000, 100, 3, 4, 2, 3, blunderMayMiss = true),
            AiDto(3, 5_000, 60, 4, 4, 2, 3, blunderMayMiss = true),
        )
        Difficulty.MEDIUM -> listOf(
            AiDto(3, 5_000, 0, 3, 4, 2, 2),
            AiDto(3, 8_000, 0, 4, 5, 2, 3),
            AiDto(4, 12_000, 0, 4, 5, 3, 3),
        )
        Difficulty.HARD -> listOf(
            AiDto(1, 500, 0, 1, 1, 1, 1),
            AiDto(1, 500, 0, 1, 2, 1, 1),
            AiDto(1, 500, 0, 2, 2, 1, 1),
            AiDto(1, 500, 0, 2, 3, 1, 1),
            AiDto(1, 700, 0, 2, 3, 1, 1),
            AiDto(2, 500, 0, 2, 3, 1, 1),
            AiDto(3, 1_000, 0, 2, 3, 1, 1),
            AiDto(3, 3_000, 0, 3, 4, 2, 2),
            AiDto(3, 6_000, 0, 3, 4, 2, 3),
            AiDto(4, 2_000, 0, 4, 5, 3, 3),
        )
    }.map { it.copy(prefersPop = popOut) }

    private fun calibrate(start: GameState, rival: AiProfile, kind: KindDto, levelId: String, games: Int): Double {
        val policy = HumanAgent.HumanPolicy.forDifficulty(start.difficulty)
        val wins = java.util.stream.IntStream.range(0, games).parallel().filter { index ->
            val agent = HumanAgent(policy, SplitMix64(0xCA11B + index.toLong() * 31 + levelId.hashCode()))
            val result = if (kind.type == "defense") agent.playDefense(start, rival, requireNotNull(kind.surviveAiMoves))
                else agent.play(start, rival, kind.winInMoves)
            result.playerWon
        }.count()
        return wins.toDouble() / games
    }

    private fun signature(state: GameState): String =
        "${state.width}x${state.height}:${state.playable.joinToString("") { if (it) "X" else "#" }}:pop=${state.popOut}:${DinerEntry.signature(state)}"
}

/** Certified continuation for Cinema Drive-In levels 221–230. */
object DriveInEpilogueService {
    private const val RECIPES = "templates/drivein-epilogue.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(root: File): DriveInContinuationRecipes =
        json.decodeFromString(DriveInContinuationRecipes.serializer(), File(root, RECIPES).readText()).also(::validate)

    fun validate(recipes: DriveInContinuationRecipes) {
        require(recipes.version == 1 && recipes.engineVersion == GameEngine.ENGINE_VERSION)
        require(recipes.lessons.map { it.ordinal } == (221..230).toList())
        require(recipes.lessons.map { it.kind } == listOf("duel", "duel", "puzzle", "defense", "duel", "puzzle", "duel", "defense", "puzzle", "duel"))
        require(recipes.lessons.filter { it.popOut }.map { it.ordinal } == listOf(222, 223, 225, 228, 230))
        require(recipes.lessons.map { it.isDoubleNeon } == (221..230).map { it == 230 })
        require(recipes.lessons.all { lesson ->
            lesson.titleEn.isNotBlank() && lesson.titlePt.isNotBlank() && lesson.familyId.startsWith("drivein-") &&
                lesson.width == 7 && lesson.height == 6 && lesson.connect == 4 && lesson.openingPairsMin in 3..16 &&
                lesson.openingPairsMax in lesson.openingPairsMin..16 && lesson.tutorialKey == null && lesson.tutorialColumn == null &&
                (lesson.sourceHardOrdinal == null || lesson.ordinal == 229 && lesson.sourceHardOrdinal == 219)
        })
        require(recipes.lessons.filter { it.kind == "puzzle" }.all { it.puzzleMoves == listOf(2, 3, 4) && it.surviveAiMoves == null })
        require(recipes.lessons.filter { it.ordinal != 229 }.all { it.sourceOrdinals == null })
        require(recipes.lessons.single { it.ordinal == 229 }.sourceOrdinals == listOf(219, 216, 219))
        require(recipes.lessons.single { it.ordinal == 229 }.sourceHardOrdinal == 219)
        require(recipes.lessons.filter { it.kind != "puzzle" }.all { it.sourceOrdinals == null })
        require(recipes.lessons.filter { it.kind == "defense" }.all { it.surviveAiMoves == listOf(4, 5, 6) && it.puzzleMoves == null })
        require(recipes.lessons.filter { it.kind == "duel" }.all { it.puzzleMoves == null && it.surviveAiMoves == null })
    }

    fun run(root: File, output: File, fromOrdinal: Int = 221, toOrdinal: Int = 230) {
        val recipes = load(root)
        require(fromOrdinal in 221..230 && toOrdinal in fromOrdinal..230)
        val selected = recipes.lessons.filter { it.ordinal in fromOrdinal..toOrdinal }
        DriveInContinuationService.generate(root, output, selected, fromOrdinal - 1, "drivein-epilogue", forcedWinNodeBudget = 150_000)
    }
}
