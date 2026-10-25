package pt.tiago.neonline.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.model.*
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.game.MatchReplay
import java.nio.file.Files

class SolidFloorFreePlayTest {
    private fun saved(config: FreePlayConfig): SavedFreePlay {
        var state = config.initial()
        val steps = listOf(Move.Drop(1),Move.Drop(1),Move.Drop(1,PieceKind.BOMB)).map { move ->
            val before=state; val result=GameEngine.apply(state,move)
            assertTrue(result.events.none { it is GameEvent.MoveRejected }); state=result.state
            MatchReplay.step(before,move,state)
        }
        return SavedFreePlay(config,SavedMatch("free-play",config.difficulty,initialHash=StateHasher.hash(config.initial()),stateHash=StateHasher.hash(state),
            steps=steps,hintsLeft=config.hints(),undosLeft=config.undos()))
    }
    @Test fun `new shaped specials use engine three while all old configurations stay on two`() {
        for(version in 1..4) {
            val config=FreePlayConfig(version=version)
            assertEquals(2,config.initial().engineVersion)
            assertEquals(config.initial(),config.copy(version=5).initial().copy(engineVersion=2))
            assertEquals(config.ai(),config.copy(version=5).ai())
            assertEquals(2,SavedFreePlay(config,SavedMatch("free-play",Difficulty.EASY)).engineVersion)
        }
        for(shape in listOf(FreePlayBoardShape.PILLARS,FreePlayBoardShape.STEPS)) {
            val config=FreePlayConfig(version=5,rules=FreePlayRules(shape=shape,specials=true))
            assertTrue(config.isValid()); assertEquals(3,config.initial().engineVersion)
            assertTrue(config.ai()!!.nodeBudget<=1000); assertTrue(config.hintAi()!!.nodeBudget<=1000)
            assertFalse(config.copy(version=4).isValid())
            assertFalse(config.copy(rules=config.rules.copy(popOut=true)).isValid())
            assertFalse(config.copy(rules=config.rules.copy(rotation=true)).isValid())
        }
    }
    @Test fun `new selector preserves special inventory opt in while legacy helper keeps its old meaning`() {
        val rules=FreePlayRules(specials=true,popOut=true,rotation=true)
        val modern=rules.withShape(FreePlayBoardShape.PILLARS,allowMaskedSpecials=true)
        assertTrue(modern.specials); assertFalse(modern.popOut); assertFalse(modern.rotation)
        assertTrue(modern.isValid(allowMaskedSpecials=true)); assertFalse(modern.isValid())
        assertFalse(rules.withShape(FreePlayBoardShape.PILLARS).specials)
    }
    @Test fun `bomb survivor journals round trip with correct version and reject mismatched envelopes`() {
        for(shape in listOf(FreePlayBoardShape.PILLARS,FreePlayBoardShape.STEPS)) for((width,height) in listOf(7 to 6,9 to 7)) {
            val config=FreePlayConfig(version=5,rules=FreePlayRules(width=width,height=height,shape=shape,specials=true))
            val match=saved(config)
            assertEquals(3,match.engineVersion)
            val state=FreePlay.restore(match)!!.state
            assertTrue(state.pieces.indices.all { state.pieces[it]==null || state.playable[it] })
            assertEquals(1,state.pieces.filterNotNull().size)
            assertNull(FreePlay.restore(match.copy(engineVersion=2)))
            assertNull(FreePlay.restore(match.copy(config=config.copy(version=4))))
            val encoded=Json.encodeToString(SavedFreePlay.serializer(),match)
            assertEquals(match,Json.decodeFromString(SavedFreePlay.serializer(),encoded))
        }
    }
    @Test fun `expanded matches preserve profile backup reset and legacy serialization defaults`() {
        val dir=Files.createTempDirectory("solid-floor").toFile()
        try {
            val config=FreePlayConfig(version=5,rules=FreePlayRules(shape=FreePlayBoardShape.PILLARS,specials=true))
            val profile=Profile(savedFreePlay=saved(config),freePlayPeeks=mapOf(Difficulty.EASY to 3L))
            val store=ProfileStore(dir); store.save(profile)
            assertEquals(profile,ProfileStore(dir).load())
            assertEquals(profile,ProfileBackup.decode(ProfileBackup.encode(profile)))
            assertEquals(profile.savedFreePlay,store.resetProgress().savedFreePlay)
            assertNotNull(FreePlay.restore(store.load().savedFreePlay!!))
            val old=Json.decodeFromString(SavedFreePlay.serializer(),"{\"config\":{},\"match\":{\"levelId\":\"free-play\",\"difficulty\":\"EASY\"}}")
            assertEquals(2,old.engineVersion); assertEquals(1,old.config.version)
        } finally { dir.deleteRecursively() }
    }
}
