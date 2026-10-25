package pt.tiago.neonline.data

import java.util.concurrent.CancellationException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.*

class RushPackTest {
    private fun text():String = listOf(java.io.File("../content/rush/pool.json"),java.io.File("content/rush/pool.json")).first { it.isFile }.readText()
    @Test fun `dedicated pack has five hundred distinct physical puzzles with exact staged source identities`() {
        val dto=Json.decodeFromString(RushPoolDto.serializer(),text())
        val pack=RushPack.from(dto)
        assertEquals(500,pack.puzzles.size)
        assertEquals(mapOf(1 to 167,2 to 167,3 to 166),pack.puzzles.groupingBy { it.moves }.eachCount())
        val keys=HashSet<String>()
        for(p in pack.puzzles) {
            val state=pack.stateFor(p)!!
            assertEquals(p.entryId,state.levelId)
            assertEquals(Status.InProgress,state.status)
            assertEquals(Player.P1,state.toMove)
            assertTrue(state.ply in 28..36)
            assertTrue(keys.add(rushPositionKey(state)))
            assertNull(pack.stateFor(p.copy(moves=if(p.moves==1)2 else 1)))
        }
        assertTrue(PuzzleRush.ready(pack.puzzles))
        assertEquals(3,PuzzleRush.choose(pack.puzzles,RushRun(Difficulty.EASY,"0123456789abcdef",1000,solved=5))!!.moves)
    }
    @Test fun `manifest checked repository caches success refuses tampering future versions and cancellation`() {
        val original=text()
        val hash=MessageDigest.getInstance("SHA-256").digest(original.toByteArray()).joinToString("") { "%02x".format(it) }
        val source=object:AssetSource { override fun readText(path:String):String? = when(path) {
            "manifest.txt" -> "format=neonline-assets-1\nrush/pool.json=$hash\n"
            "rush/pool.json" -> original
            else -> null
        } }
        val repo=CatalogRepository(source)
        assertNotNull(repo.rushPack())
        assertSame(repo.rushPack(),repo.rushPack())
        val damaged=CatalogRepository(object:AssetSource { override fun readText(path:String)=source.readText(path)?.let { if(path=="rush/pool.json") it+" " else it } })
        assertNull(damaged.rushPack())
        val dto=Json.decodeFromString(RushPoolDto.serializer(),original)
        assertThrows(IllegalArgumentException::class.java) { RushPack.from(dto.copy(version=999)) }
        var checks=0
        assertThrows(CancellationException::class.java) {
            RushPack.from(dto) { if(++checks==5) throw CancellationException() }
        }
        assertEquals(5,checks)
        assertEquals(500,RushPack.from(dto).puzzles.size)
    }
}
