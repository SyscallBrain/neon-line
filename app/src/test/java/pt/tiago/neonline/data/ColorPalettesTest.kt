package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.ui.art.*
import java.nio.file.Files

class ColorPalettesTest {
    @Test fun `accessibility palettes keep perceptual separation under color vision deficiency simulation`() {
        val simulations = mapOf(
            "deuteranopia" to arrayOf(
                doubleArrayOf(0.367322, 0.860646, -0.227968),
                doubleArrayOf(0.280085, 0.672501, 0.047413),
                doubleArrayOf(-0.011820, 0.042940, 0.968881),
            ),
            "protanopia" to arrayOf(
                doubleArrayOf(0.152286, 1.052583, -0.204868),
                doubleArrayOf(0.114503, 0.786281, 0.099216),
                doubleArrayOf(-0.003882, -0.048116, 1.051998),
            ),
            "tritanopia" to arrayOf(
                doubleArrayOf(1.255528, -0.076749, -0.178779),
                doubleArrayOf(-0.078411, 0.930809, 0.147602),
                doubleArrayOf(0.004733, 0.691367, 0.303900),
            ),
        )

        for ((paletteKey, matrix) in simulations) {
            for (skin in BoardSkin.entries) {
                val palette = BoardStyles.palette(skin, paletteKey)
                val background = BoardStyles.style(skin).hole
                val p1 = simulate(palette.p1, matrix)
                val p2 = simulate(palette.p2, matrix)
                val bg = simulate(background, matrix)
                assertTrue("$paletteKey on ${skin.key}: players ΔE=${deltaE(p1, p2)}", deltaE(p1, p2) >= 30.0)
                assertTrue("$paletteKey on ${skin.key}: P1/background ΔE=${deltaE(p1, bg)}", deltaE(p1, bg) >= 20.0)
                assertTrue("$paletteKey on ${skin.key}: P2/background ΔE=${deltaE(p2, bg)}", deltaE(p2, bg) >= 20.0)
            }
        }
    }

    @Test fun `accessibility palettes override players consistently across board themes`() {
        for (skin in BoardSkin.entries) {
            val deuteranopia = BoardStyles.palette(skin, "deuteranopia")
            assertEquals(0xFF56B4E9.toInt(), deuteranopia.p1)
            assertEquals(0xFFE69F00.toInt(), deuteranopia.p2)
            val protanopia = BoardStyles.palette(skin, "protanopia")
            assertEquals(0xFF56B4E9.toInt(), protanopia.p1)
            assertEquals(0xFFF0E442.toInt(), protanopia.p2)
            val tritanopia = BoardStyles.palette(skin, "tritanopia")
            assertEquals(0xFFFF80AB.toInt(), tritanopia.p1)
            assertEquals(0xFF64FFDA.toInt(), tritanopia.p2)
        }
    }
    @Test fun `unknown preference retains the current theme normal player colors`() {
        for (skin in BoardSkin.entries) {
            assertEquals(BoardStyles.palette(skin), BoardStyles.palette(skin, "normal"))
            assertEquals(BoardStyles.palette(skin), BoardStyles.palette(skin, "future-palette"))
        }
    }
    @Test fun `all player owned piece types share the palette while wild remains neutral`() {
        for (skin in BoardSkin.entries) for (palette in listOf("normal", "deuteranopia", "protanopia", "tritanopia")) {
            for (owner in Player.entries) {
                assertEquals(0xFFFFFFFF.toInt(), BoardStyles.pieceColor(skin, owner, PieceKind.WILD, palette))
                for (kind in listOf(PieceKind.NORMAL, PieceKind.LOCK, PieceKind.BOMB))
                    assertEquals(if (owner == Player.P1) BoardStyles.palette(skin, palette).p1 else BoardStyles.palette(skin, palette).p2,
                        BoardStyles.pieceColor(skin, owner, kind, palette))
            }
            assertEquals(0xFFFFFFFF.toInt(), BoardStyles.pieceColor(skin, null, PieceKind.NORMAL, palette))
        }
    }
    @Test fun `selecting palettes persists without changing any game sessions or progress`() {
        val dir = Files.createTempDirectory("palette-settings").toFile()
        try {
            val profile = ProfileReducer.recordWin(Profile(), 1, Difficulty.EASY, 3, 5, 0).copy(
                savedMatch = SavedMatch("level-0001", Difficulty.HARD), localSession = SavedLocalSession(wins1 = 4),
                savedDaily = SavedDaily("2026-10-14", 1, "a".repeat(64), SavedMatch("level-0002", Difficulty.EASY)))
            for (palette in listOf("deuteranopia", "protanopia", "tritanopia", "normal")) {
                val selected = ProfileReducer.withSettings(profile, profile.settings.copy(colorPalette = palette))
                assertEquals(profile.copy(revision = selected.revision, settings = selected.settings), selected)
                val store = ProfileStore(dir); store.save(selected)
                assertEquals(selected, ProfileStore(dir).load())
                assertEquals(selected, ProfileBackup.decode(ProfileBackup.encode(selected)))
                assertEquals(palette, store.resetProgress().settings.colorPalette)
            }
        } finally { dir.deleteRecursively() }
    }

    private data class Lab(val l: Double, val a: Double, val b: Double)

    private fun simulate(argb: Int, matrix: Array<DoubleArray>): Lab {
        fun linear(shift: Int): Double {
            val srgb = ((argb ushr shift) and 0xFF) / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val rgb = doubleArrayOf(linear(16), linear(8), linear(0))
        val transformed = DoubleArray(3) { row -> (0..2).sumOf { col -> matrix[row][col] * rgb[col] }.coerceIn(0.0, 1.0) }
        val x = (0.4124564 * transformed[0] + 0.3575761 * transformed[1] + 0.1804375 * transformed[2]) / 0.95047
        val y = 0.2126729 * transformed[0] + 0.7151522 * transformed[1] + 0.0721750 * transformed[2]
        val z = (0.0193339 * transformed[0] + 0.1191920 * transformed[1] + 0.9503041 * transformed[2]) / 1.08883
        fun labChannel(value: Double): Double = if (value > 216.0 / 24389.0) Math.cbrt(value) else (24389.0 / 27.0 * value + 16.0) / 116.0
        val fx = labChannel(x)
        val fy = labChannel(y)
        val fz = labChannel(z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun deltaE(left: Lab, right: Lab): Double =
        kotlin.math.sqrt((left.l - right.l) * (left.l - right.l) + (left.a - right.a) * (left.a - right.a) + (left.b - right.b) * (left.b - right.b))
}
