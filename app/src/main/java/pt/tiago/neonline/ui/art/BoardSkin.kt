package pt.tiago.neonline.ui.art

import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.data.ThemeUnlocks

/** Stable preference keys and campaign star thresholds. */
enum class BoardSkin(val key: String, val requiredStars: Int) {
    NEON_ROAD("neon_road", 0), SYNTHWAVE("synthwave", ThemeUnlocks.SYNTHWAVE),
    CIRCUIT("circuit", ThemeUnlocks.CIRCUIT), AURORA("aurora", ThemeUnlocks.AURORA),
}

/** Stable keys already stored by Settings; unknown imported keys use the theme's normal colors. */
enum class ColorPalette(val key: String) {
    NORMAL("normal"), DEUTERANOPIA("deuteranopia"), PROTANOPIA("protanopia"), TRITANOPIA("tritanopia");
    companion object {
        fun resolve(key: String): ColorPalette = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

data class SkinPalette(val p1: Int, val p2: Int, val accent: Int)
data class BoardStyle(val top: Int, val bottom: Int, val edge: Int, val hole: Int, val corner: Float, val squareHoles: Boolean = false)

/** Colors and geometry from docs/design/neonline-design.html; no Android dependency. */
object BoardStyles {
    @JvmOverloads
    fun pieceColor(skin: BoardSkin, owner: Player?, kind: PieceKind, colorPalette: String = "normal"): Int =
        if (kind == PieceKind.WILD || owner == null) 0xFFFFFFFF.toInt()
        else if (owner == Player.P1) palette(skin, colorPalette).p1 else palette(skin, colorPalette).p2

    @JvmOverloads
    fun palette(skin: BoardSkin, colorPalette: String = "normal"): SkinPalette {
        val normal = normalPalette(skin)
        return when (ColorPalette.resolve(colorPalette)) {
            ColorPalette.NORMAL -> normal
            ColorPalette.DEUTERANOPIA -> normal.copy(p1 = 0xFF56B4E9.toInt(), p2 = 0xFFE69F00.toInt())
            ColorPalette.PROTANOPIA -> normal.copy(p1 = 0xFF56B4E9.toInt(), p2 = 0xFFF0E442.toInt())
            ColorPalette.TRITANOPIA -> normal.copy(p1 = 0xFFFF80AB.toInt(), p2 = 0xFF64FFDA.toInt())
        }
    }
    private fun normalPalette(skin: BoardSkin): SkinPalette = when (skin) {
        BoardSkin.NEON_ROAD -> SkinPalette(0xFFFF4F9A.toInt(), 0xFFFFB547.toInt(), 0xFF6FE3FF.toInt())
        BoardSkin.SYNTHWAVE -> SkinPalette(0xFFFF2BD6.toInt(), 0xFF00E5FF.toInt(), 0xFFB07CFF.toInt())
        BoardSkin.CIRCUIT -> SkinPalette(0xFFFF3B5C.toInt(), 0xFF3D8BFF.toInt(), 0xFFD9A441.toInt())
        BoardSkin.AURORA -> SkinPalette(0xFFB98CFF.toInt(), 0xFF5FF5C8.toInt(), 0xFF7FE8FF.toInt())
    }
    fun style(skin: BoardSkin): BoardStyle = when (skin) {
        BoardSkin.NEON_ROAD -> BoardStyle(0xFF170F1F.toInt(), 0xFF170F1F.toInt(), 0xFF6FE3FF.toInt(), 0xFF09060D.toInt(), .34f)
        BoardSkin.SYNTHWAVE -> BoardStyle(0xFF5B2AA8.toInt(), 0xFF2E1170.toInt(), 0xFFB07CFF.toInt(), 0xFF12062A.toInt(), .24f)
        BoardSkin.CIRCUIT -> BoardStyle(0xFF0F1A1E.toInt(), 0xFF0F1A1E.toInt(), 0xFFD9A441.toInt(), 0xFF05080A.toInt(), .09f, squareHoles = true)
        BoardSkin.AURORA -> BoardStyle(0xFF26263E.toInt(), 0xFF15182A.toInt(), 0xFFB1BDCC.toInt(), 0xFF050614.toInt(), .39f)
    }
}
