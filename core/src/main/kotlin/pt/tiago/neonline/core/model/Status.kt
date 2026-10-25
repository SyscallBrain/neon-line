package pt.tiago.neonline.core.model

enum class Difficulty { EASY, MEDIUM, HARD }

/** Shape objectives (campaign levels): only lines that obey the rule end the match for the
 * objective holder (P1). The rival's lines always count. */
enum class ShapeRule {
    DIAGONAL_ONLY,
    HORIZONTAL_ONLY,
    VERTICAL_ONLY,
    THROUGH_TARGET,
}

/** Match status inside the engine. Campaign objectives (level win/loss) are layered on top in :core.objectives. */
sealed interface Status {
    data object InProgress : Status
    data class Won(val player: Player) : Status
    data class Draw(val reason: String) : Status
}

/** What a completed match means, once the engine detects it. */
sealed interface MatchOutcome {
    data class Win(val player: Player, val lines: List<Line>) : MatchOutcome
    data class Draw(val reason: String) : MatchOutcome
}

/** A winning line: the player it belongs to and the cell indices (row-major) it covers. */
data class Line(val player: Player, val cells: List<Int>)
