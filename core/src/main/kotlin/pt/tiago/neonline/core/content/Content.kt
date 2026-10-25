package pt.tiago.neonline.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.ShapeRule
import pt.tiago.neonline.core.objectives.LevelKind
import pt.tiago.neonline.core.random.SplitMix64

@Serializable
data class RulesDto(
    val popOut: Boolean = false,
    val rotateEvery: Int? = null,
    val fadeAfter: Int? = null,
    val moveClockSec: Int? = null,
)

@Serializable
data class AiDto(
    val maxDepth: Int,
    val nodeBudget: Int,
    val blunderRate: Int,
    val attack: Int,
    val defense: Int,
    val center: Int,
    val trap: Int,
    val mirror: Boolean = false,
    val prefersPop: Boolean = false,
    val blunderMayMiss: Boolean = false,
)

@Serializable
data class KindDto(
    val type: String,
    val winInMoves: Int? = null,
    val surviveAiMoves: Int? = null,
    val shapeRule: String? = null,
    val bestOf: Int? = null,
)

@Serializable
data class StarsDto(val two: Int, val three: Int)

@Serializable
data class MetricsDto(val humanWinRate: Double, val solverMoves: Int)

@Serializable
data class BossRoundDto(val seedHex: String, val firstToMove: String, val pieces: List<PieceDto>)

@Serializable
data class VariantDto(
    val seedHex: String,
    val kind: KindDto,
    val width: Int,
    val height: Int,
    val connect: Int,
    /** "XXXXXXX" per row, X = playable and # = blocked. */
    val mask: List<String>,
    val pieces: List<PieceDto>,
    val targets: List<Int> = emptyList(),
    val shapeRule: String? = null,
    val rules: RulesDto = RulesDto(),
    val inventory: Map<String, Map<String, Int>> = emptyMap(),
    val firstToMove: String = "P1",
    val rival: String,
    val ai: AiDto,
    val stars: StarsDto,
    val hints: Int,
    val expectedMetrics: MetricsDto,
    val bossRounds: List<BossRoundDto> = emptyList(),
)

@Serializable
data class PieceDto(val r: Int, val c: Int, val owner: String, val kind: String = "normal", val placedAtPly: Int = 0)

@Serializable
data class TutorialStepDto(val textKey: String, val highlightCols: List<Int> = emptyList(), val forcedCol: Int? = null)

@Serializable
data class TutorialDto(val steps: List<TutorialStepDto>)

@Serializable
data class LevelFileDto(
    val schemaVersion: Int = 1,
    val engineVersion: Int,
    val id: String,
    val ordinal: Int,
    val stopId: String,
    val titlePt: String,
    val titleEn: String,
    val familyId: String,
    val learningTags: List<String> = emptyList(),
    val revision: Int = 1,
    val isDoubleNeon: Boolean = false,
    val tutorial: TutorialDto? = null,
    val variants: Map<String, VariantDto>,
)

@Serializable
data class CertificateStepDto(
    val move: String,
    val afterStateHash: String,
    /** The AI stream state before this move, so the replay is verbatim. */
    val rngHex: String? = null,
)

@Serializable
data class CertificateDto(
    val levelId: String,
    val difficulty: String,
    val engineVersion: Int,
    val revision: Int,
    val seedHex: String,
    /** The human's moves with the rival's expected answer after each one. */
    val steps: List<CertificateStepDto>,
    val humanMoves: Int,
    val aiNodes: Int,
    val stars: Int,
    val outcome: String,
    val createdAt: String,
)

@Serializable
data class CatalogStopDto(val id: String, val namePt: String, val nameEn: String, val from: Int, val to: Int)

@Serializable
data class CatalogEntryDto(val path: String, val revision: Int, val sha256: String)

@Serializable
data class CatalogDto(
    val schemaVersion: Int = 1,
    val engineVersion: Int,
    val stops: List<CatalogStopDto>,
    val levels: List<CatalogEntryDto>,
)

/** Round zero mirrors the base opening; all rounds use the same rules and rival profile. */
fun VariantDto.forBossRound(index: Int): VariantDto {
    require(index in 0..2)
    val round = bossRounds.getOrNull(index) ?: return this.also { require(index == 0) }
    return copy(seedHex = round.seedHex, firstToMove = round.firstToMove, pieces = round.pieces, bossRounds = emptyList())
}

/** Converts a variant DTO into the engine's initial state. */
fun VariantDto.toState(levelId: String, difficulty: Difficulty): GameState {
    val playable = mask.flatMap { row -> row.map { it == 'X' } }
    val pieces = MutableList<Piece?>(width * height) { null }
    for (p in this.pieces) {
        val owner = if (p.owner == "P1") Player.P1 else Player.P2
        val kind = when (p.kind) {
            "wild" -> PieceKind.WILD
            "lock" -> PieceKind.LOCK
            else -> PieceKind.NORMAL
        }
        pieces[p.r * width + p.c] = Piece(owner, kind, p.placedAtPly)
    }
    val inventory = Player.entries.associateWith { player ->
        PieceKind.entries.associateWith { kind -> this.inventory[player.name]?.get(kind.name.lowercase()) ?: 0 }
    }
    return GameState.initial(
        levelId = levelId,
        difficulty = difficulty,
        engineVersion = pt.tiago.neonline.core.rules.GameEngine.ENGINE_VERSION,
        seedHex = seedHex,
        width = width,
        height = height,
        connect = connect,
        playable = playable,
        pieces = pieces,
        targets = targets.toSet(),
        shapeRule = shapeRule?.let { ShapeRule.valueOf(it.uppercase()) },
        popOut = rules.popOut,
        rotateEvery = rules.rotateEvery,
        fadeAfter = rules.fadeAfter,
        moveClockSec = rules.moveClockSec,
        inventory = inventory,
        firstToMove = if (firstToMove == "P2") Player.P2 else Player.P1,
    )
}

fun VariantDto.kindEnum(): LevelKind = when (kind.type) {
    "duel" -> LevelKind.DUEL
    "puzzle" -> LevelKind.PUZZLE
    "defense" -> LevelKind.DEFENSE
    "shape" -> LevelKind.SHAPE
    "boss" -> LevelKind.BOSS
    else -> error("unknown level kind ${kind.type}")
}

fun VariantDto.seedLong(): Long = SplitMix64.seedFromHex(seedHex)

/** Difficulty key used in the JSON: "easy", "medium", "hard". */
fun difficultyKey(d: Difficulty): String = d.name.lowercase()

internal val contentJson = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

/** Canonical serialization of a level file: the basis of the catalog sha256. */
fun levelJson(level: LevelFileDto): String = contentJson.encodeToString(LevelFileDto.serializer(), level)

enum class ContentError(val message: String) {
    ORDINAL_GAP("levels must be contiguous from 1"),
    STOP_SIZE("each stop must hold exactly 50 levels"),
    MASK_SHAPE("mask must be width x height of X and #"),
    MASK_ROTATION("rotating gravity requires a full rectangular mask"),
    PIECE_OUTSIDE("piece outside the mask"),
    PIECE_BLOCKED("piece on a blocked cell"),
    PIECE_FLOATS("pre-placed piece floats"),
    LINE_ALREADY("a line already exists at the start"),
    VARIANT_COUNT("level needs easy, medium and hard variants"),
    CONNECT("connect must be 4 or 5"),
    SIZE("width 4..9 and height 4..7"),
    STARS("stars.three must be <= stars.two"),
    AI("ai profile is out of range"),
    INVENTORY("inventory counts must be positive"),
    BOSS_STOP("level 50 of each stop must be a boss"),
    TUTORIAL("tutorial steps must name a text key"),
}
