package pt.tiago.neonline.core.model

import pt.tiago.neonline.core.random.SplitMix64

/**
 * The complete logical state of a match. Immutable: [pt.tiago.neonline.core.rules.GameEngine.apply]
 * returns a new state with the events that explain the change. Row 0 is the top row; pieces fall
 * towards larger rows, relative to the current [orientation].
 *
 * The two RNG streams are carried as hex strings so the state stays immutable and hashable:
 * [auxStateHex] picks the column on a TimeUp move, [aiStateHex] feeds the rival personalities.
 * Both derive from [seedHex]; the same level with the same moves always produces the same answers.
 */
data class GameState(
    val levelId: String,
    val difficulty: Difficulty,
    val engineVersion: Int,
    val seedHex: String,
    val width: Int,
    val height: Int,
    val connect: Int,
    /** Row-major, width*height entries. False marks a blocked cell (pillar or ceiling). */
    val playable: List<Boolean>,
    /** Row-major, width*height entries. null means empty. */
    val pieces: List<Piece?>,
    /** Target cells of shape objectives (row-major indices). */
    val targets: Set<Int> = emptySet(),
    /** When set, the objective holder (P1) only wins with lines that obey this rule. */
    val shapeRule: ShapeRule? = null,
    // Rule set
    val popOut: Boolean = false,
    val rotateEvery: Int? = null,
    val fadeAfter: Int? = null,
    val moveClockSec: Int? = null,
    /** Special pieces each player may still spend: kind -> count. */
    val inventory: Map<Player, Map< PieceKind, Int>> = emptyInventory(),
    val lockedCol: Int? = null,
    val lockedFor: Player? = null,
    /** Number of 90-degree clockwise rotations applied (0..3). */
    val orientation: Int = 0,
    val toMove: Player = Player.P1,
    /** Number of half-moves played. */
    val ply: Int = 0,
    val auxStateHex: String,
    val aiStateHex: String,
    val status: Status = Status.InProgress,
    /** Repetition hashes of every position reached (without ply). Three equal entries mean a draw. */
    val history: List<Long> = emptyList(),
) {
    init {
        require(width in 4..9) { "width must be 4..9" }
        require(height in 4..7) { "height must be 4..7" }
        require(connect == 4 || connect == 5) { "connect must be 4 or 5" }
        require(playable.size == width * height) { "playable mask size mismatch" }
        require(pieces.size == width * height) { "pieces size mismatch" }
        require(orientation in 0..3) { "orientation must be 0..3" }
    }

    fun index(row: Int, col: Int): Int = row * width + col
    fun rowOf(cell: Int): Int = cell / width
    fun colOf(cell: Int): Int = cell % width
    fun pieceAt(cell: Int): Piece? = pieces[cell]
    fun isPlayable(cell: Int): Boolean = playable[cell]
    fun isEmpty(cell: Int): Boolean = playable[cell] && pieces[cell] == null
    fun pieceAt(row: Int, col: Int): Piece? = pieces[index(row, col)]

    fun auxRng(): SplitMix64 = SplitMix64.fromHex(auxStateHex)
    fun aiRng(): SplitMix64 = SplitMix64.fromHex(aiStateHex)

    /**
     * FNV-1a over the physical position (no ply, no RNG states, no placedAtPly): the Pop Out
     * repetition rule draws when the same hash appears three times.
     */
    fun repetitionHash(): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        fun mix(value: Long) {
            hash = hash xor value
            hash *= 0x100000001B3L
        }
        mix(width.toLong()); mix(height.toLong()); mix(connect.toLong()); mix(orientation.toLong())
        mix(if (popOut) 1 else 0)
        for (cell in 0 until width * height) {
            mix(if (playable[cell]) 1 else 0)
            val piece = pieces[cell]
            if (piece == null) {
                mix(0)
            } else {
                mix(piece.owner.ordinal + 1L)
                mix(piece.kind.ordinal + 1L)
            }
        }
        mix(lockedCol?.toLong() ?: -1L)
        mix(lockedFor?.ordinal?.toLong() ?: -1L)
        mix(toMove.ordinal.toLong())
        for (player in Player.entries) {
            for (kind in PieceKind.entries) {
                mix((inventory[player]?.get(kind) ?: 0).toLong())
            }
        }
        return hash
    }

    companion object {
        /** Derives both RNG streams from the variant seed and builds the initial state. */
        fun initial(
            levelId: String,
            difficulty: Difficulty,
            engineVersion: Int,
            seedHex: String,
            width: Int,
            height: Int,
            connect: Int,
            playable: List<Boolean>,
            pieces: List<Piece?>,
            targets: Set<Int> = emptySet(),
            shapeRule: pt.tiago.neonline.core.model.ShapeRule? = null,
            popOut: Boolean = false,
            rotateEvery: Int? = null,
            fadeAfter: Int? = null,
            moveClockSec: Int? = null,
            inventory: Map<Player, Map< PieceKind, Int>> = emptyInventory(),
            firstToMove: Player = Player.P1,
        ): GameState {
            val base = SplitMix64.seedFromHex(seedHex)
            val aux = SplitMix64(base)
            val ai = SplitMix64(base xor 0x9E3779B97F4A7C15UL.toLong())
            // Warm the auxiliary stream up so its first draws are decorrelated from the AI stream.
            repeat(8) { aux.nextLong() }
            return GameState(
                levelId = levelId,
                difficulty = difficulty,
                engineVersion = engineVersion,
                seedHex = seedHex,
                width = width,
                height = height,
                connect = connect,
                playable = playable,
                pieces = pieces,
                targets = targets,
                shapeRule = shapeRule,
                popOut = popOut,
                rotateEvery = rotateEvery,
                fadeAfter = fadeAfter,
                moveClockSec = moveClockSec,
                inventory = inventory,
                toMove = firstToMove,
                auxStateHex = aux.stateHex(),
                aiStateHex = ai.stateHex(),
            ).let { it.copy(history = listOf(it.repetitionHash())) }
        }

        fun emptyInventory(): Map<Player, Map< PieceKind, Int>> = mapOf(
            Player.P1 to PieceKind.entries.associateWith { 0 },
            Player.P2 to PieceKind.entries.associateWith { 0 },
        )
    }
}
