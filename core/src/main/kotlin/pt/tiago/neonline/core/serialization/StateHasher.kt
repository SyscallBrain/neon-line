package pt.tiago.neonline.core.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.Status
import java.security.MessageDigest

/** Versioned canonical logical-state encoding for certificates and deterministic replay. */
object StateHasher {
    const val HASH_VERSION = 1

    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Serializable
    private data class PieceDto(val cell: Int, val owner: Int, val kind: Int, val placedAtPly: Int)

    @Serializable
    private data class StateDto(
        val hashVersion: Int,
        val engineVersion: Int,
        val levelId: String,
        val difficulty: Int,
        val seedHex: String,
        val width: Int,
        val height: Int,
        val connect: Int,
        val playable: String,
        val pieces: List<PieceDto>,
        val targets: List<Int>,
        val shapeRule: String?,
        val popOut: Boolean,
        val rotateEvery: Int?,
        val fadeAfter: Int?,
        val moveClockSec: Int?,
        val inventory: List<List<Int>>,
        val lockedCol: Int?,
        val lockedFor: Int?,
        val orientation: Int,
        val toMove: Int,
        val ply: Int,
        val auxStateHex: String,
        val aiStateHex: String,
        val status: String,
    )

    /** The canonical JSON of a state: every field, in a fixed order, no ambiguity. */
    fun canonical(state: GameState): String = canonicalJson.encodeToString(
        StateDto(
            hashVersion = HASH_VERSION,
            engineVersion = state.engineVersion,
            levelId = state.levelId,
            difficulty = state.difficulty.ordinal,
            seedHex = state.seedHex.lowercase(),
            width = state.width,
            height = state.height,
            connect = state.connect,
            playable = state.playable.joinToString("") { if (it) "X" else "#" },
            pieces = state.pieces.mapIndexedNotNull { cell, piece ->
                piece?.let { PieceDto(cell, it.owner.ordinal, it.kind.ordinal, it.placedAtPly) }
            },
            targets = state.targets.sorted(),
            shapeRule = state.shapeRule?.name?.lowercase(),
            popOut = state.popOut,
            rotateEvery = state.rotateEvery,
            fadeAfter = state.fadeAfter,
            moveClockSec = state.moveClockSec,
            inventory = Player.entries.map { player ->
                PieceKind.entries.map { kind -> state.inventory[player]?.get(kind) ?: 0 }
            },
            lockedCol = state.lockedCol,
            lockedFor = state.lockedFor?.ordinal,
            orientation = state.orientation,
            toMove = state.toMove.ordinal,
            ply = state.ply,
            auxStateHex = state.auxStateHex.lowercase(),
            aiStateHex = state.aiStateHex.lowercase(),
            status = when (val status = state.status) {
                Status.InProgress -> "in_progress"
                is Status.Won -> "won:${status.player.ordinal}"
                is Status.Draw -> "draw:${status.reason}"
            },
        ),
    )

    /** Lowercase SHA-256 hex of the canonical encoding. */
    fun hash(state: GameState): String = sha256Hex(canonical(state))

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
