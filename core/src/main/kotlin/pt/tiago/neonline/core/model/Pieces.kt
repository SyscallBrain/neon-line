package pt.tiago.neonline.core.model

/** The two players. P1 is always the human in campaign levels. */
enum class Player { P1, P2;

    fun opponent(): Player = if (this == P1) P2 else P1
}

/** Piece kinds. Specials only exist in levels that grant an inventory. */
enum class PieceKind {
    NORMAL, WILD, LOCK,

    /** Bombs are never placed: they explode on landing and leave no piece behind. */
    BOMB,
    ;

    companion object {
        /** Kinds that can rest on the board. */
        val PLACED = listOf(NORMAL, WILD, LOCK)
    }
}

/** A single piece on the board. [placedAtPly] powers the fading-neon rule and the UI. */
data class Piece(
    val owner: Player,
    val kind: PieceKind,
    val placedAtPly: Int,
)
