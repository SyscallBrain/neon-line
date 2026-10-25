package pt.tiago.neonline.leveltool

import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.ai.AiProfile
import pt.tiago.neonline.core.ai.AiSearch
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.MatchOutcome
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import pt.tiago.neonline.core.objectives.LevelEvaluator
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.objectives.LevelObjective
import pt.tiago.neonline.core.objectives.LevelOutcome
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.VariantDto
import pt.tiago.neonline.core.content.AiDto
import pt.tiago.neonline.core.content.CertificateDto
import pt.tiago.neonline.core.content.CertificateStepDto
import pt.tiago.neonline.core.content.difficultyKey
import pt.tiago.neonline.core.content.kindEnum
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.forBossRound

/** Generates and replays the certificates the build verifies. */
object Certificates {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One recorded half-move with the AI stream state it was found with. */
    data class Step(val move: Move, val rngHex: String)

    private class SearchResult(val steps: List<Step>, val humanMoves: Int, val finalState: GameState)

    fun decodeLevel(text: String): LevelFileDto = json.decodeFromString(LevelFileDto.serializer(), text)

    fun decodeCertificate(text: String): CertificateDto =
        json.decodeFromString(CertificateDto.serializer(), text)

    /** Builds a three-star certificate for one variant, or null when it cannot be proven. */
    fun solve(level: LevelFileDto, difficulty: Difficulty): CertificateDto? {
        val variant = level.variants[difficultyKey(difficulty)] ?: return null
        val state = variant.toState(level.id, difficulty)
        val certificate = when (variant.kindEnum()) {
            LevelKind.PUZZLE -> solvePuzzle(level, variant, state)
            else -> solveBySearch(level, variant, state)
        }
        // A certificate that does not prove three stars is not a certificate.
        return if (certificate != null && certificate.stars == 3) certificate else null
    }

    /** Prove the human strategy against every defence, then record the actual app rival. */
    private fun solvePuzzle(level: LevelFileDto, variant: VariantDto, start: GameState): CertificateDto? {
        val limit = variant.kind.winInMoves ?: return null
        val proof = ForcedWin()
        if (proof.canWin(start, limit) != true) return null
        val steps = mutableListOf<Step>()
        var state = start
        var humanMoves = 0
        while (state.status == Status.InProgress && humanMoves < limit) {
            val move = proof.winningMoves(state, limit - humanMoves)?.firstOrNull() ?: return null
            steps += Step(move, state.aiStateHex)
            state = GameEngine.apply(state, move).state
            humanMoves++
            if (state.status != Status.InProgress) break
            val reply = AiSearch(variant.ai.toProfile()).chooseMove(state) ?: return null
            steps += Step(reply.move, reply.aiStateHex)
            state = GameEngine.apply(state.copy(aiStateHex = reply.aiStateHex), reply.move).state
        }
        return build(level, variant, start, steps)
    }

    /** Build a certificate for a generated witness; the verifier still checks the actual AI. */
    internal fun fromSteps(level: LevelFileDto, difficulty: Difficulty, steps: List<Step>, round: Int = 0): CertificateDto {
        val variant = level.variants.getValue(difficultyKey(difficulty)).forBossRound(round)
        return build(level, variant, variant.toState(level.id, difficulty), steps)
    }

    /** Duels and friends: single-agent search over the player's moves; the rival answers with the AI. */
    private fun solveBySearch(level: LevelFileDto, variant: VariantDto, start: GameState): CertificateDto? {
        val profile = variant.ai.toProfile()
        val budget = variant.stars.three.coerceAtLeast(1)
        val nodeCap = 150_000
        var nodes = 0
        var found: SearchResult? = null

        fun search(state: GameState, steps: MutableList<Step>, depth: Int, humanMoves: Int): Boolean {
            if (nodes > nodeCap || found != null) return false
            if (state.status != Status.InProgress) {
                if (humanMoves <= budget && starCount(variant, state, humanMoves) == 3) {
                    found = SearchResult(steps.toList(), humanMoves, state)
                    return true
                }
                return false
            }
            if (variant.kindEnum() == LevelKind.DEFENSE && variant.kind.surviveAiMoves?.let { steps.size - humanMoves >= it } == true) return false
            if (depth > 2 * budget + 4) return false
            for (move in orderedHumanMoves(state, profile)) {
                nodes++
                steps += Step(move, state.aiStateHex)
                val after = GameEngine.apply(state, move).state
                if (after.status != Status.InProgress) {
                    if (search(after, steps, depth + 1, humanMoves + 1)) return true
                } else {
                    val choice = AiSearch(profile).chooseMove(after)
                    if (choice == null) {
                        steps.removeAt(steps.size - 1)
                        continue
                    }
                    steps += Step(choice.move, choice.aiStateHex)
                    val afterReply =
                        GameEngine.apply(after.copy(aiStateHex = choice.aiStateHex), choice.move).state
                    if (search(afterReply, steps, depth + 2, humanMoves + 1)) return true
                    steps.removeAt(steps.size - 1)
                }
                steps.removeAt(steps.size - 1)
            }
            return false
        }

        search(start, mutableListOf(), 0, 0)
        return found?.let { build(level, variant, start, it.steps) }
    }

    private fun orderedHumanMoves(state: GameState, profile: AiProfile): List<Move> {
        val middle = (state.width - 1) / 2
        val centreOut = GameEngine.validMoves(state).sortedBy { move ->
            when (move) {
                is Move.Drop -> Math.abs(move.col - middle)
                is Move.Pop -> 100
                Move.TimeUp -> 200
            }
        }
        // The AI suggests the player's move first: it finds the double
        // threats that a centre-out ordering reaches only after exploring dead branches.
        val suggestion = try {
            AiSearch(profile).chooseMove(state)?.move
        } catch (_: Exception) {
            null
        }
        return if (suggestion != null && suggestion in centreOut) {
            listOf(suggestion) + centreOut.filter { it != suggestion }
        } else {
            centreOut
        }
    }

    private fun starCount(variant: VariantDto, state: GameState, humanMoves: Int): Int {
        val objective = LevelObjective(
            kind = variant.kindEnum(),
            winInMoves = variant.kind.winInMoves,
            surviveAiMoves = variant.kind.surviveAiMoves,
            starsTwo = variant.stars.two,
            starsThree = variant.stars.three,
        )
        val evaluated = LevelEvaluator.evaluate(objective, matchOutcome(state), humanMoves)
        return (evaluated as? LevelOutcome.Won)?.stars ?: 0
    }

    private fun matchOutcome(state: GameState): MatchOutcome? = when (val status = state.status) {
        is Status.Won -> MatchOutcome.Win(status.player, emptyList())
        is Status.Draw -> MatchOutcome.Draw(status.reason)
        Status.InProgress -> null
    }

    /**
     * Writes the certificate from the recorded steps. Because each step carries the AI stream it
     * was found with, the replay here is verbatim and the verifier reproduces it exactly.
     */
    private fun build(
        level: LevelFileDto,
        variant: VariantDto,
        start: GameState,
        steps: List<Step>,
    ): CertificateDto {
        var replay = start
        val hashes = mutableListOf<String>()
        var humanMoves = 0
        for (step in steps) {
            if (replay.toMove == Player.P1) humanMoves++
            replay = replay.copy(aiStateHex = step.rngHex)
            replay = GameEngine.apply(replay, step.move).state
            hashes += StateHasher.hash(replay)
        }
        val finalState = replay
        return CertificateDto(
            levelId = level.id,
            difficulty = start.difficulty.name.lowercase(),
            engineVersion = level.engineVersion,
            revision = level.revision,
            seedHex = variant.seedHex,
            steps = steps.mapIndexed { index, step ->
                CertificateStepDto(step.move.toString(), hashes[index], step.rngHex)
            },
            humanMoves = humanMoves,
            aiNodes = 0,
            stars = starCount(variant, finalState, humanMoves),
            outcome = when (val outcome = matchOutcome(finalState)) {
                is MatchOutcome.Win -> "win:${outcome.player.name}"
                is MatchOutcome.Draw -> "draw:${outcome.reason}"
                null -> "in_progress"
            },
            createdAt = "2026-10-13",
        )
    }

    /** Replays a certificate and reports every mismatch. */
    fun verify(level: LevelFileDto, certificate: CertificateDto): List<String> {
        val errors = mutableListOf<String>()
        val difficulty = Difficulty.valueOf(certificate.difficulty.uppercase())
        val base = level.variants[difficultyKey(difficulty)] ?: return listOf("missing variant")
        val variant = if (base.bossRounds.isNotEmpty()) {
            val index = base.bossRounds.indexOfFirst { it.seedHex == certificate.seedHex }
            if (index < 0) return listOf("certificate seed mismatch")
            base.forBossRound(index)
        } else base
        if (certificate.engineVersion != level.engineVersion) errors += "certificate engineVersion mismatch"
        if (certificate.revision != level.revision) errors += "certificate revision mismatch"
        if (certificate.seedHex != variant.seedHex) errors += "certificate seed mismatch"
        val profile = variant.ai.toProfile()
        var humanMoves = 0
        if (certificate.levelId != level.id) errors += "certificate level mismatch"
        var rivalMoves = 0
        var state = variant.toState(level.id, difficulty)
        for ((index, step) in certificate.steps.withIndex()) {
            if (variant.kindEnum() == LevelKind.DEFENSE && variant.kind.surviveAiMoves?.let { rivalMoves >= it } == true) {
                errors += "defense already complete at step $index"
                break
            }
            if (state.status != Status.InProgress) {
                errors += "match already over at step $index"
                break
            }
            val move = parseMove(step.move) ?: run {
                errors += "unreadable move at step $index: ${step.move}"
                break
            }
            if (move !in GameEngine.validMoves(state)) {
                errors += "illegal move at step $index: ${step.move}"
                break
            }
            val mover = state.toMove
            if (mover == Player.P1) {
                humanMoves++
                if (step.rngHex != state.aiStateHex) errors += "human move changes AI stream at step $index"
            } else {
                rivalMoves++
                val expected = AiSearch(profile).chooseMove(state)
                if (expected == null || move != expected.move || step.rngHex != expected.aiStateHex) {
                    errors += "rival move or stream mismatch at step $index"
                }
            }
            state = state.copy(aiStateHex = step.rngHex ?: state.aiStateHex)
            state = GameEngine.apply(state, move).state
            if (StateHasher.hash(state) != step.afterStateHash) errors += "after-state hash mismatch at step $index"
        }
        if (state.status == Status.InProgress) errors += "certificate does not end the match"
        val actualOutcome = when (val status = state.status) {
            is Status.Won -> "win:${status.player.name}"
            is Status.Draw -> "draw:${status.reason}"
            Status.InProgress -> "in_progress"
        }
        if (certificate.outcome != actualOutcome) {
            errors += "certificate outcome ${certificate.outcome} does not match the replay ($actualOutcome)"
        }
        if (certificate.humanMoves != humanMoves) errors += "human move count mismatch"
        if (starCount(variant, state, humanMoves) != certificate.stars) errors += "star count mismatch"
        if (certificate.stars != 3) errors += "certificate is not a three star run (${certificate.stars})"
        return errors
    }

    internal fun parseMove(text: String): Move? = when {
        text.startsWith("Drop(col=") -> {
            val col = text.substringAfter("Drop(col=").substringBefore(",").trim().toIntOrNull() ?: return null
            val kind = when {
                text.contains("WILD") -> PieceKind.WILD
                text.contains("LOCK") -> PieceKind.LOCK
                text.contains("BOMB") -> PieceKind.BOMB
                else -> PieceKind.NORMAL
            }
            Move.Drop(col, kind)
        }
        text.startsWith("Pop(col=") ->
            Move.Pop(text.substringAfter("Pop(col=").substringBefore(")").trim().toIntOrNull() ?: return null)
        text == "TimeUp" -> Move.TimeUp
        else -> null
    }
}

internal fun AiDto.toProfile() = AiProfile(
    maxDepth = maxDepth,
    nodeBudget = nodeBudget,
    attack = attack,
    defense = defense,
    center = center,
    trap = trap,
    blunderRate = blunderRate,
    mirror = mirror,
    prefersPop = prefersPop,
    blunderMayMiss = blunderMayMiss,
)
