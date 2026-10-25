package pt.tiago.neonline.core.rules

import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.GameState
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

/** Test helper: builds states from ASCII boards. '.' empty, '#' blocked, '1'/'2' normal,
 * 'A'/'B' wild (owner P1/P2), 'L'/'M' lock (owner P1/P2). */
object Boards {
    fun state(
        vararg rows: String,
        connect: Int = 4,
        popOut: Boolean = false,
        rotateEvery: Int? = null,
        fadeAfter: Int? = null,
        moveClockSec: Int? = null,
        seedHex: String = "0123456789abcdef",
        inventory: Map<Player, Map< PieceKind, Int>>? = null,
        firstToMove: Player = Player.P1,
        targets: Set<Int> = emptySet(),
        shapeRule: pt.tiago.neonline.core.model.ShapeRule? = null,
    ): GameState {
        val width = rows[0].length
        val height = rows.size
        val playable = ArrayList<Boolean>(width * height)
        val pieces = ArrayList<Piece?>(width * height)
        rows.forEach { row ->
            require(row.length == width) { "ragged board" }
            row.forEach { ch ->
                when (ch) {
                    '.' -> { playable += true; pieces += null }
                    '#' -> { playable += false; pieces += null }
                    '1' -> { playable += true; pieces += Piece(Player.P1, PieceKind.NORMAL, 0) }
                    '2' -> { playable += true; pieces += Piece(Player.P2, PieceKind.NORMAL, 0) }
                    'A' -> { playable += true; pieces += Piece(Player.P1, PieceKind.WILD, 0) }
                    'B' -> { playable += true; pieces += Piece(Player.P2, PieceKind.WILD, 0) }
                    'L' -> { playable += true; pieces += Piece(Player.P1, PieceKind.LOCK, 0) }
                    'M' -> { playable += true; pieces += Piece(Player.P2, PieceKind.LOCK, 0) }
                    else -> error("unknown cell '$ch'")
                }
            }
        }
        return GameState.initial(
            levelId = "test",
            difficulty = Difficulty.EASY,
            engineVersion = GameEngine.ENGINE_VERSION,
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
            inventory = inventory ?: GameState.emptyInventory(),
            firstToMove = firstToMove,
        )
    }

    /** A blank playable board of the given size. */
    fun blank(
        width: Int = 7,
        height: Int = 6,
        connect: Int = 4,
        popOut: Boolean = false,
        rotateEvery: Int? = null,
        fadeAfter: Int? = null,
        moveClockSec: Int? = null,
        seedHex: String = "0123456789abcdef",
        inventory: Map<Player, Map< PieceKind, Int>>? = null,
        firstToMove: Player = Player.P1,
    ): GameState = state(
        *Array(height) { ".".repeat(width) },
        connect = connect,
        popOut = popOut,
        rotateEvery = rotateEvery,
        fadeAfter = fadeAfter,
        moveClockSec = moveClockSec,
        seedHex = seedHex,
        inventory = inventory,
        firstToMove = firstToMove,
    )
}

/** No piece may float: every playable empty cell below a piece is a content error. */
fun assertNoFloating(state: GameState) {
    for (c in 0 until state.width) {
        for (r in 0 until state.height - 1) {
            val above = state.pieceAt(r, c)
            val below = state.pieceAt(r + 1, c)
            if (above != null && below == null && state.isPlayable(state.index(r + 1, c))) {
                throw AssertionError("piece floats at row $r col $c")
            }
        }
    }
}
