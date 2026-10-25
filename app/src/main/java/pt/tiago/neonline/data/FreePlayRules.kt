package pt.tiago.neonline.data

import kotlinx.serialization.Serializable

/** Free-play combinations are validated before creating or restoring an engine state. */
@Serializable
data class FreePlayRules(val width: Int = 7, val height: Int = 6, val connect: Int = 4,
    val popOut: Boolean = false, val rotation: Boolean = false, val specials: Boolean = false, val clockSeconds: Int = 0, val fade: Boolean = false,
    val shape: FreePlayBoardShape = FreePlayBoardShape.RECTANGLE) {
    @JvmOverloads
    fun isValid(allowMaskedSpecials: Boolean = false): Boolean = (width to height) in setOf(7 to 6, 9 to 7) && connect in 4..5 &&
        clockSeconds in setOf(0, 10, 20) && (!rotation || width == 7) && (shape == FreePlayBoardShape.RECTANGLE || (!popOut && !rotation && (!specials || allowMaskedSpecials)))
    fun playableMask(): List<Boolean> = shape.playableMask(width, height)
    fun withShape(selected: FreePlayBoardShape, allowMaskedSpecials: Boolean = false): FreePlayRules = copy(shape = selected,
        popOut = popOut && selected == FreePlayBoardShape.RECTANGLE,
        rotation = rotation && selected == FreePlayBoardShape.RECTANGLE,
        specials = specials && (selected == FreePlayBoardShape.RECTANGLE || allowMaskedSpecials))
    fun fadeAfter(difficulty: pt.tiago.neonline.core.model.Difficulty): Int? = if (!fade) null else when (difficulty) {
        pt.tiago.neonline.core.model.Difficulty.EASY -> 5
        pt.tiago.neonline.core.model.Difficulty.MEDIUM -> 3
        pt.tiago.neonline.core.model.Difficulty.HARD -> 2
    }
    fun isClassic(): Boolean = this == FreePlayRules()
    fun hasComplexSearch(): Boolean = width != 7 || connect != 4 || popOut || rotation || shape != FreePlayBoardShape.RECTANGLE
}
