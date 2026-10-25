package pt.tiago.neonline.core.ai

/**
 * The personality of a rival. Weights shape the evaluation; [blunderRate] is the chance, per
 * thousand, of playing the second or third best move. Everything is integer arithmetic so the
 * search is bit-for-bit reproducible on the JVM and on the phone.
 */
data class AiProfile(
    val maxDepth: Int,
    val nodeBudget: Int,
    val attack: Int,
    val defense: Int,
    val center: Int,
    val trap: Int,
    /** Per mille chance of picking a slightly worse move, drawn from the AI stream. */
    val blunderRate: Int = 0,
    /** Plays the column symmetric to the opponent's last move when it is safe. */
    val mirror: Boolean = false,
    /** Prefers popping a piece over dropping when the profile leans that way. */
    val prefersPop: Boolean = false,
    /**
     * When true, a fired blunder may skip the never-miss-a-win/block safety rule. Only the Easy
     * preset sets it: Medium and Hard always take an immediate win and always block one.
     */
    val blunderMayMiss: Boolean = false,
) {
    init {
        require(maxDepth >= 1) { "maxDepth must be at least 1" }
        require(nodeBudget >= 1) { "nodeBudget must be at least 1" }
        require(blunderRate in 0..1000) { "blunderRate is per mille" }
    }

    companion object {
        /** Orientation values; the level JSON carries the exact profile. */
        val EASY = AiProfile(maxDepth = 3, nodeBudget = 5_000, attack = 3, defense = 4, center = 2, trap = 1, blunderRate = 220, blunderMayMiss = true)
        val MEDIUM = AiProfile(maxDepth = 6, nodeBudget = 60_000, attack = 4, defense = 5, center = 3, trap = 3, blunderRate = 40)
        val HARD = AiProfile(maxDepth = 9, nodeBudget = 400_000, attack = 6, defense = 5, center = 3, trap = 5, blunderRate = 0)
    }
}

/** A chosen move plus the AI stream state after the move's noise draw. */
data class AiChoice(val move: pt.tiago.neonline.core.model.Move, val aiStateHex: String)
