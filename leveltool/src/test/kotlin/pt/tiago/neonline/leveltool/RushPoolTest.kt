package pt.tiago.neonline.leveltool

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.solver.BoundedWinSolver

class RushPoolTest {
    @Test fun `generated pack has true minimal forced wins balanced quotas and complete winning roots`() {
        val pool=RushPool.generate(12,20261016L,1)
        assertEquals(12,pool.entries.size)
        assertEquals(mapOf(1 to 4,2 to 4,3 to 4),pool.entries.groupingBy { it.winInMoves }.eachCount())
        val states=validateRushPool(pool)
        for((entry,state) in pool.entries.zip(states)) {
            assertEquals(entry.winningColumns,BoundedWinSolver().winningMoves(state,entry.winInMoves)!!.map { (it as pt.tiago.neonline.core.model.Move.Drop).col }.sorted())
            if(entry.winInMoves>1) assertEquals(false,BoundedWinSolver().canWin(state,entry.winInMoves-1))
        }
        RushPool.verify(pool)
        assertEquals(pool,RushPool.generate(12,20261016L,1))
    }
    @Test fun `tampered goals roots hashes and duplicate mirror positions are refused`() {
        val p=RushPool.generate(9,20261016L,1)
        val e=p.entries.first()
        assertThrows(IllegalArgumentException::class.java) { RushPool.verify(p.copy(entries=p.entries.mapIndexed { i,x -> if(i==0) x.copy(stateSha256="bad") else x })) }
        assertThrows(IllegalArgumentException::class.java) { RushPool.verify(p.copy(entries=p.entries.mapIndexed { i,x -> if(i==0) x.copy(winningColumns=emptyList()) else x })) }
        val wrong=e.copy(winInMoves=if(e.winInMoves==1)2 else 1)
        assertThrows(IllegalArgumentException::class.java) { RushPool.verify(p.copy(entries=listOf(wrong)+p.entries.drop(1))) }
        val reflected=e.copy(id=p.entries[1].id,opening=e.opening.map { 6-it },winningColumns=e.winningColumns.map { 6-it }.sorted())
        val repeated=reflected.copy(stateSha256=pt.tiago.neonline.core.serialization.StateHasher.hash(reflected.toRushState(2)))
        assertThrows(IllegalArgumentException::class.java) { validateRushPool(p.copy(entries=listOf(e,repeated)+p.entries.drop(2))) }
    }
    @Test fun `opening replay refuses illegal short or already won histories and propagates cancellation`() {
        val e=RushPool.generate(3,20261016L,1).entries.first()
        assertThrows(IllegalArgumentException::class.java) { e.copy(opening=listOf(7)+e.opening.drop(1)).toRushState(2) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(opening=e.opening.take(24)).toRushState(2) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(opening=listOf(0,6,0,6,0,6,0)+e.opening.drop(7)).toRushState(2) }
        var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            e.toRushState(2) { if(++checks==6) throw java.util.concurrent.CancellationException() }
        }
        assertEquals(6,checks)
    }
    @Test fun `generation freezes versioned output and failed replacement preserves original bytes`() {
        val dir=Files.createTempDirectory("rush-pack").toFile()
        try {
            val out=java.io.File(dir,"pool.json")
            RushPool.write(out,6,20261016L,1)
            val first=out.readBytes()
            RushPool.write(out,6,20261016L,1)
            assertArrayEquals(first,out.readBytes())
            assertThrows(IllegalArgumentException::class.java) { RushPool.write(out,6,20261017L,1) }
            assertArrayEquals(first,out.readBytes())
            RushPool.write(out,6,20261017L,2)
            assertFalse(first.contentEquals(out.readBytes()))
        } finally { dir.deleteRecursively() }
    }
}
