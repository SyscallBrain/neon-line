package pt.tiago.neonline.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher

class FreePlayExactEndgameTest {
    @Test fun `policy opts in only new hard classic and preserves old configs`() {
        for (v in 1..6) for (d in Difficulty.entries) {
            val c = FreePlayConfig(version = v, difficulty = d)
            assertNotNull(c.ai())
            assertEquals(v == 6 && d == Difficulty.HARD, c.exactEndgameRival())
            assertEquals(if (v >= 5) 3 else 2, c.engineVersion())
            assertEquals(if (v >= 2) 512 else null, c.maxPlies())
        }
        val c = FreePlayConfig(version = 6, difficulty = Difficulty.HARD)
        for (r in listOf(FreePlayRules(popOut = true), FreePlayRules(rotation = true), FreePlayRules(specials = true),
            FreePlayRules(fade = true), FreePlayRules(clockSeconds = 10), FreePlayRules(connect = 5),
            FreePlayRules(width = 9, height = 7), FreePlayRules(shape = FreePlayBoardShape.PILLARS))) {
            assertFalse(c.copy(rules = r).exactEndgameRival())
        }
        assertFalse(c.copy(version = 999).exactEndgameRival())
        assertEquals(c.copy(version = 5).ai(), c.ai())
        assertEquals(StateHasher.hash(c.copy(version = 5).initial()), StateHasher.hash(c.initial()))
    }
    @Test fun `new config storage backup and restoration preserve legacy save independently`() {
        val dir = Files.createTempDirectory("free-exact").toFile()
        try {
            for (v in 1..6) {
                val c = FreePlayConfig(version = v, difficulty = Difficulty.HARD, starter = Player.P2)
                val hash = StateHasher.hash(c.initial())
                val match = SavedMatch("free-play", Difficulty.HARD, initialHash = hash, stateHash = hash, hintsLeft = c.hints(), undosLeft = c.undos())
                val p = FreePlay.checkpoint(Profile(savedMatch = SavedMatch("level-0002", Difficulty.EASY)), c, match, null)
                val store = ProfileStore(dir); store.save(p)
                assertEquals(p, ProfileStore(dir).load())
                assertEquals(p, ProfileBackup.decode(ProfileBackup.encode(p)))
                assertEquals(c.initial(), FreePlay.restore(p.savedFreePlay!!)!!.state)
                assertEquals(v, p.savedFreePlay!!.config.version)
            }
        } finally { dir.deleteRecursively() }
    }
}
