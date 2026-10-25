package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.ui.art.*
import java.nio.file.Files

class BoardThemesTest {
    /** Synthetic future-campaign progress, not an alteration to packaged levels. */
    private fun stars(amount: Int): Profile {
        val ids = (1..((amount + 2) / 3)).associate { ordinal ->
            ProfileReducer.variantId(ordinal, Difficulty.EASY) to LevelBest(minOf(3, amount - (ordinal - 1) * 3), 4, 0)
        }
        return Profile(progressByDifficulty = mapOf(Difficulty.EASY to DifficultyProgress(ids.keys, ids, ids.size)))
    }
    @Test fun `default theme is always available and unknown preference falls back`() {
        assertEquals(BoardSkin.NEON_ROAD, BoardThemes.resolve("neon_road", 0))
        assertEquals(BoardSkin.NEON_ROAD, BoardThemes.resolve("unknown", 5400))
        assertTrue(BoardThemes.isUnlocked(BoardSkin.NEON_ROAD, 0))
    }
    @Test fun `themes unlock at exact thresholds and only count campaign stars`() {
        for (skin in BoardSkin.entries.drop(1)) {
            assertFalse(BoardThemes.isUnlocked(skin, skin.requiredStars - 1))
            assertTrue(BoardThemes.isUnlocked(skin, skin.requiredStars))
            assertEquals(BoardSkin.NEON_ROAD, BoardThemes.resolve(skin.key, skin.requiredStars - 1))
            assertEquals(skin, BoardThemes.resolve(skin.key, skin.requiredStars))
        }
        val local = Profile(localSession = SavedLocalSession(wins1 = 999, wins2 = 999))
        assertEquals(local, BoardThemes.select(local, BoardSkin.SYNTHWAVE))
    }
    @Test fun `selecting a locked theme does not touch revision or profile`() {
        val profile = stars(399).copy(savedMatch = SavedMatch("level-0001", Difficulty.EASY))
        assertEquals(profile, BoardThemes.select(profile, BoardSkin.SYNTHWAVE))
    }
    @Test fun `selection changes only the preference and revision`() {
        val profile = stars(400).copy(localSession = SavedLocalSession(), savedMatch = SavedMatch("level-0001", Difficulty.HARD),
            achievements = listOf(Achievement("future-badge")), settings = Settings(soundVolume = .3f, confirmMove = true))
        val selected = BoardThemes.select(profile, BoardSkin.SYNTHWAVE)
        assertEquals(profile.revision + 1, selected.revision)
        assertEquals("synthwave", selected.settings.skin)
        assertEquals(profile.settings.copy(skin = "synthwave"), selected.settings)
        assertEquals(profile.progressByDifficulty, selected.progressByDifficulty)
        assertEquals(profile.statsByDifficulty, selected.statsByDifficulty)
        assertEquals(profile.savedMatch, selected.savedMatch)
        assertEquals(profile.localSession, selected.localSession)
        assertEquals(profile.achievements, selected.achievements)
        assertEquals(selected, BoardThemes.select(selected, BoardSkin.SYNTHWAVE))
    }
    @Test fun `threshold crossing reports one theme and repeats never report it twice`() {
        val before = stars(399)
        val after = stars(400)
        assertEquals(listOf(BoardSkin.SYNTHWAVE), BoardThemes.newlyUnlocked(before, after))
        assertTrue(BoardThemes.newlyUnlocked(after, after).isEmpty())
        assertTrue(BoardThemes.newlyUnlocked(after, before).isEmpty())
        assertEquals(listOf(BoardSkin.SYNTHWAVE, BoardSkin.CIRCUIT, BoardSkin.AURORA), BoardThemes.newlyUnlocked(Profile(), stars(1800)))
    }
    @Test fun `stars from all difficulties contribute without counting repeated results`() {
        var profile = stars(399)
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.MEDIUM, 1, 5, 1)
        assertTrue(BoardThemes.isUnlocked(BoardSkin.SYNTHWAVE, profile.totalStars()))
        profile = ProfileReducer.recordWin(profile, 1, Difficulty.MEDIUM, 1, 8, 2)
        assertEquals(400, profile.totalStars())
    }
    @Test fun `theme remains selected through storage and backup and reset falls back safely`() {
        val dir = Files.createTempDirectory("theme-test").toFile()
        try {
            val selected = BoardThemes.select(stars(400), BoardSkin.SYNTHWAVE)
            val store = ProfileStore(dir); store.save(selected)
            val loaded = ProfileStore(dir).load()
            assertEquals(BoardSkin.SYNTHWAVE, BoardThemes.resolve(loaded.settings.skin, loaded.totalStars()))
            assertEquals(selected, ProfileBackup.decode(ProfileBackup.encode(loaded)))
            val reset = store.resetProgress()
            assertEquals("synthwave", reset.settings.skin)
            assertEquals(BoardSkin.NEON_ROAD, BoardThemes.resolve(reset.settings.skin, reset.totalStars()))
            store.importProfile(selected)
            assertEquals(BoardSkin.SYNTHWAVE, BoardThemes.resolve(store.load().settings.skin, store.load().totalStars()))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `preview metadata has distinct boards and LED holes are square`() {
        assertEquals(4, BoardSkin.entries.map { BoardStyles.style(it) }.toSet().size)
        assertTrue(BoardStyles.style(BoardSkin.CIRCUIT).squareHoles)
        assertFalse(BoardStyles.style(BoardSkin.NEON_ROAD).squareHoles)
        assertFalse(BoardStyles.style(BoardSkin.SYNTHWAVE).squareHoles)
        assertFalse(BoardStyles.style(BoardSkin.AURORA).squareHoles)
        assertTrue(BoardSkin.entries.all { BoardStyles.style(it).corner > 0 && BoardStyles.style(it).corner < .5f })
    }
    @Test fun `wild piece is neutral white in every theme regardless of who placed it`() {
        for (skin in BoardSkin.entries) for (owner in pt.tiago.neonline.core.model.Player.entries) {
            assertEquals(0xFFFFFFFF.toInt(), BoardStyles.pieceColor(skin, owner, pt.tiago.neonline.core.model.PieceKind.WILD))
            assertEquals(if (owner == pt.tiago.neonline.core.model.Player.P1) BoardStyles.palette(skin).p1 else BoardStyles.palette(skin).p2,
                BoardStyles.pieceColor(skin, owner, pt.tiago.neonline.core.model.PieceKind.NORMAL))
        }
    }
    @Test fun `player palettes match reference and have distinct owner colors`() {
        assertEquals(0xFFFF2BD6.toInt(), BoardStyles.palette(BoardSkin.SYNTHWAVE).p1)
        assertEquals(0xFF00E5FF.toInt(), BoardStyles.palette(BoardSkin.SYNTHWAVE).p2)
        assertEquals(0xFFFF3B5C.toInt(), BoardStyles.palette(BoardSkin.CIRCUIT).p1)
        assertEquals(0xFF3D8BFF.toInt(), BoardStyles.palette(BoardSkin.CIRCUIT).p2)
        assertEquals(0xFFB98CFF.toInt(), BoardStyles.palette(BoardSkin.AURORA).p1)
        assertEquals(0xFF5FF5C8.toInt(), BoardStyles.palette(BoardSkin.AURORA).p2)
        assertTrue(BoardSkin.entries.all { BoardStyles.palette(it).p1 != BoardStyles.palette(it).p2 })
    }
}
