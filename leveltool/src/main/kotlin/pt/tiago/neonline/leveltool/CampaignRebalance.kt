package pt.tiago.neonline.leveltool

import java.io.File
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.ai.*
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.random.SplitMix64
import pt.tiago.neonline.core.rules.GameEngine

/** Reproducible replacement of trivial openings; writes to staging until all variants are proven. */
object CampaignRebalance {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val puzzles = setOf(1, 2, 6, 7, 8, 12, 16, 21, 25, 29, 33, 37, 41, 45)
    private val human = AiProfile(6, 18_000, 6, 6, 4, 5)

    fun run(root: File, output: File, from: Int, to: Int) {
        require(root.canonicalFile != output.canonicalFile) { "Use a staging directory" }
        val source = Files.levels(root).filter { it.ordinal in from..to }
        for (original in source) {
            val out = File(output, "levels/${original.stopId}/${original.id}.json")
            if (out.isFile) { println("rebalance: keeping ${original.id} already staged"); continue }
            val variants = linkedMapOf<String, VariantDto>()
            val witnesses = linkedMapOf<Difficulty, List<Certificates.Step>>()
            for (difficulty in Difficulty.entries) {
                val key = difficultyKey(difficulty)
                val isPuzzle = original.ordinal in puzzles
                val base = original.variants.getValue(key).copy(
                    kind = KindDto(if (original.ordinal == 50) "boss" else if (isPuzzle) "puzzle" else "duel",
                        winInMoves = if (isPuzzle) target(original.ordinal, difficulty) else null,
                        bestOf = if (original.ordinal == 50) 3 else null),
                    ai = profile(difficulty), stars = StarsDto(30, 26), rules = RulesDto(),
                    pieces = emptyList(), inventory = emptyMap(), targets = emptyList(), shapeRule = null,
                    firstToMove = "P1", rival = "sparky",
                )
                var found = false
                for (attempt in 0 until 3000) {
                    val seed = Generator.seedFor("${original.id}:$key:balance-2:$attempt", original.ordinal, 1)
                    val rng = SplitMix64.seedFromHex(seed).let(::SplitMix64)
                    val opening = if (original.ordinal == 1) {
                        listOf(PieceDto(5, 0, "P1"), PieceDto(5, 1, "P1"), PieceDto(5, 2, "P1"))
                    } else randomOpening(base, difficulty, isPuzzle, rng) ?: continue
                    val variant = base.copy(seedHex = seed, pieces = opening)
                    val start = variant.toState(original.id, difficulty)
                    if (original.ordinal != 1 && (winsNow(start, Player.P1) || winsNow(start, Player.P2))) continue
                    val steps = if (isPuzzle) puzzleWitness(start, variant) else duelWitness(start, variant, difficulty)
                    if (steps == null) continue
                    val humanMoves = (steps.size + 1) / 2
                    val calibration = if (original.ordinal == 1) 16 else calibrationWins(start, variant, original.id)
                    val special = original.ordinal % 10 == 0
                    val accepted = when (difficulty) {
                        Difficulty.EASY -> if (special) 10..13 else 14..16
                        Difficulty.MEDIUM -> if (isPuzzle) 12..16 else if (special) 5..8 else 9..13
                        Difficulty.HARD -> if (isPuzzle) 0..8 else if (special) 2..5 else 4..8
                    }
                    if (original.ordinal != 1 && calibration !in accepted) continue
                    val slack = when (difficulty) { Difficulty.EASY -> 3; Difficulty.MEDIUM -> 2; Difficulty.HARD -> 1 }
                    variants[key] = variant.copy(
                        stars = StarsDto(humanMoves + slack + 4, humanMoves + slack),
                        expectedMetrics = MetricsDto(calibration / 16.0, humanMoves),
                    )
                    witnesses[difficulty] = steps
                    println("rebalance: ${original.id}/$key attempt=$attempt pieces=${opening.size} solution=$humanMoves calibration=$calibration/16")
                    found = true
                    break
                }
                check(found) { "No acceptable ${original.id}/$key variant within deterministic attempt limit" }
            }
            val isPuzzle = original.ordinal in puzzles
            val level = original.copy(
                engineVersion = GameEngine.ENGINE_VERSION,
                revision = original.revision + 1,
                familyId = if (original.ordinal == 1) "guided-first-line" else if (isPuzzle) "forced-tactical-win" else "balanced-midgame-duel",
                titleEn = if (original.ordinal == 1) "Your first line" else if (isPuzzle) "Tactical challenge ${original.ordinal}" else "Desert duel ${original.ordinal}",
                titlePt = if (original.ordinal == 1) "A tua primeira linha" else if (isPuzzle) "Desafio tático ${original.ordinal}" else "Duelo no deserto ${original.ordinal}",
                learningTags = listOf("fundamentals", if (isPuzzle) "puzzle" else "duel", if (original.ordinal == 1) "opening-lesson" else "balanced-opening"),
                isDoubleNeon = original.ordinal % 10 == 0 && original.ordinal != 50,
                variants = variants,
            )
            check(Validator.validateLevel(level).isEmpty()) { "Invalid staged level ${level.id}" }
            for ((difficulty, steps) in witnesses) {
                val certificate = Certificates.fromSteps(level, difficulty, steps)
                check(Certificates.verify(level, certificate).isEmpty()) { "Witness failed for ${level.id}/$difficulty" }
                val certFile = File(output, "certificates/${level.id}/${difficultyKey(difficulty)}.json")
                certFile.parentFile.mkdirs()
                certFile.writeText(json.encodeToString(CertificateDto.serializer(), certificate) + "\n")
            }
            out.parentFile.mkdirs()
            out.writeText(levelJson(level) + "\n")
        }
        Certifier.rebuildCatalog(output, 1, Files.levels(output))
    }

    private fun calibrationWins(start: GameState, variant: VariantDto, levelId: String): Int {
        val policy = HumanAgent.HumanPolicy.forDifficulty(start.difficulty)
        return (0 until 16).count { index ->
            val agent = HumanAgent(policy, SplitMix64(0xBA1A + levelId.hashCode() + index.toLong() * 101))
            agent.play(start, variant.ai.toProfile(), variant.kind.winInMoves).playerWon
        }
    }

    private fun target(ordinal: Int, difficulty: Difficulty): Int = when {
        ordinal == 1 -> 1
        difficulty == Difficulty.EASY -> 2
        difficulty == Difficulty.MEDIUM -> 2
        else -> if (ordinal <= 25) 3 else 4
    }

    private fun profile(difficulty: Difficulty): AiDto = when (difficulty) {
        Difficulty.EASY -> AiDto(2, 1_000, 180, 3, 4, 2, 1, blunderMayMiss = true)
        Difficulty.MEDIUM -> AiDto(4, 4_000, 0, 4, 5, 3, 3)
        Difficulty.HARD -> AiDto(6, 12_000, 0, 6, 6, 4, 5)
    }

    private fun randomOpening(base: VariantDto, difficulty: Difficulty, puzzle: Boolean, rng: SplitMix64): List<PieceDto>? {
        var state = base.copy(seedHex = "0123456789abcdef").toState("candidate", difficulty)
        val count = if (puzzle) 18 + 2 * rng.nextInt(6) else when (difficulty) {
            Difficulty.EASY -> 6 + 2 * rng.nextInt(5)
            Difficulty.MEDIUM -> 10 + 2 * rng.nextInt(5)
            Difficulty.HARD -> 12 + 2 * rng.nextInt(5)
        }
        repeat(count) {
            val next = GameEngine.validMoves(state).map { GameEngine.apply(state, it).state }.filter { it.status == Status.InProgress }
            if (next.isEmpty()) return null
            state = next[rng.nextInt(next.size)]
        }
        return state.pieces.mapIndexedNotNull { index, piece -> piece?.let { PieceDto(index / state.width, index % state.width, it.owner!!.name) } }
    }

    private fun winsNow(state: GameState, player: Player): Boolean = GameEngine.validMoves(state.copy(toMove = player)).any {
        (GameEngine.apply(state.copy(toMove = player), it).state.status as? Status.Won)?.player == player
    }

    private fun puzzleWitness(start: GameState, variant: VariantDto): List<Certificates.Step>? {
        val target = variant.kind.winInMoves!!
        val proof = ForcedWin(120_000)
        if (target > 1 && proof.canWin(start, target - 1) != false) return null
        val winners = proof.winningMoves(start, target) ?: return null
        if (winners.isEmpty() || (start.difficulty != Difficulty.EASY && winners.size != 1)) return null
        var state = start
        var remaining = target
        val steps = mutableListOf<Certificates.Step>()
        while (state.status == Status.InProgress && remaining > 0) {
            val move = proof.winningMoves(state, remaining)?.firstOrNull() ?: return null
            steps += Certificates.Step(move, state.aiStateHex)
            state = GameEngine.apply(state, move).state
            remaining--
            if (state.status != Status.InProgress) break
            val reply = AiSearch(variant.ai.toProfile()).chooseMove(state) ?: return null
            steps += Certificates.Step(reply.move, reply.aiStateHex)
            state = GameEngine.apply(state.copy(aiStateHex = reply.aiStateHex), reply.move).state
        }
        return steps.takeIf { (state.status as? Status.Won)?.player == Player.P1 && (steps.size + 1) / 2 == target }
    }

    private fun duelWitness(start: GameState, variant: VariantDto, difficulty: Difficulty): List<Certificates.Step>? {
        if (ForcedWin(10_000).canWin(start, 2) != false) return null
        var state = start
        val steps = mutableListOf<Certificates.Step>()
        repeat(42) {
            if (state.status != Status.InProgress) return@repeat
            val mine = state.toMove == Player.P1
            val choice = AiSearch(if (mine) human else variant.ai.toProfile()).chooseMove(state) ?: return null
            val rng = if (mine) state.aiStateHex else choice.aiStateHex
            steps += Certificates.Step(choice.move, rng)
            state = GameEngine.apply(state.copy(aiStateHex = rng), choice.move).state
        }
        val minimum = when (difficulty) { Difficulty.EASY -> 3; Difficulty.MEDIUM -> 4; Difficulty.HARD -> 5 }
        return steps.takeIf { (state.status as? Status.Won)?.player == Player.P1 && (steps.size + 1) / 2 >= minimum }
    }
}
