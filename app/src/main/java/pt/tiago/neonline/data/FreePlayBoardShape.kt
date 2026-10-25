package pt.tiago.neonline.data

import kotlinx.serialization.Serializable

/** Fixed symmetric floors, generated in row-major order for either supported geometry. */
@Serializable
enum class FreePlayBoardShape {
    RECTANGLE, PILLARS, STEPS;

    fun playableMask(width: Int, height: Int): List<Boolean> {
        require((width to height) in setOf(7 to 6, 9 to 7))
        return List(width * height) { index ->
            val column = index % width
            val blockedHeight = when (this) {
                RECTANGLE -> 0
                PILLARS -> if (column == 1 || column == width - 2) 2 else 0
                STEPS -> kotlin.math.abs(column - width / 2) / 2
            }
            index / width < height - blockedHeight
        }
    }
}
