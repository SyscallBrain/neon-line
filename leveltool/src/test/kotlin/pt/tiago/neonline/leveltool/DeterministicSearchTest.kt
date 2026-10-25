package pt.tiago.neonline.leveltool

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test

class DeterministicSearchTest {
    @Test fun `parallel candidates still select the earliest accepted seed`() {
        val barrier = CyclicBarrier(3)
        val visited = ConcurrentHashMap.newKeySet<Int>()
        val result = DeterministicSearch.first(3,3) { index ->
            visited += index
            barrier.await(5,TimeUnit.SECONDS)
            if(index==0) Thread.sleep(30)
            if(index<2) "candidate-$index" else null
        }
        assertEquals("candidate-0",result)
        assertEquals(setOf(0,1,2),visited)
    }

    @Test fun `exhausted search respects attempt limit and evaluator failures are propagated`() {
        val visited = ConcurrentHashMap.newKeySet<Int>()
        assertNull(DeterministicSearch.first<String>(7,2) { index -> visited += index;null })
        assertEquals((0..6).toSet(),visited)
        val error = assertThrows(IllegalStateException::class.java) {
            DeterministicSearch.first<String>(3,2) { throw IllegalStateException("bad candidate") }
        }
        assertEquals("bad candidate",error.message)
    }
}
