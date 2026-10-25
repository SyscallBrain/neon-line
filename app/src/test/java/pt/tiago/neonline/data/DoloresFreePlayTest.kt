package pt.tiago.neonline.data

import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.serialization.StateHasher

class DoloresFreePlayTest {
    @Test fun `Dolores requires encounter and new configuration while previous rivals remain pinned`() {
        val levels=listOf(RivalLevel("level-0051",51,Difficulty.EASY,"dolores",false))
        val config=FreePlayConfig(version=8,rival="dolores")
        assertNotNull(config.ai());assertTrue(config.ai()!!.mirror)
        assertNull(config.copy(version=7).ai())
        assertFalse(FreePlay.canStart(Profile(),levels,config))
        val encountered=RivalGallery.record(Profile(),"dolores",Difficulty.EASY,null)
        assertTrue(FreePlay.canStart(encountered,levels,config))
        assertEquals(4,config.engineVersion())
        assertEquals(FreePlayConfig(version=7).ai(),FreePlayConfig(version=8).ai())
        assertTrue(config.copy(difficulty=Difficulty.HARD).exactEndgameRival())
        val hash=StateHasher.hash(config.initial())
        val saved=SavedFreePlay(config,SavedMatch("free-play",Difficulty.EASY,initialHash=hash,stateHash=hash,hintsLeft=3,undosLeft=3))
        assertEquals(config.initial(),FreePlay.restore(saved)?.state)
        assertEquals(saved,ProfileBackup.decode(ProfileBackup.encode(Profile(savedFreePlay=saved))).savedFreePlay)
    }
}
