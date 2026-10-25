package pt.tiago.neonline.data

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfectSearchDiagnosticsTest {
    private lateinit var directory: java.io.File

    @Before fun setup() { directory = Files.createTempDirectory("perfect-search-diagnostics").toFile() }

    @After fun teardown() {
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `records persist as a bounded rolling log`() {
        repeat(205) { PerfectSearchDiagnostics.append(directory, "perfect_search ply=$it") }

        val records = PerfectSearchDiagnostics.read(directory)
        assertEquals(PerfectSearchDiagnostics.MAX_ENTRIES, records.size)
        assertEquals("perfect_search ply=5", records.first())
        assertEquals("perfect_search ply=204", records.last())
    }

    @Test
    fun `records are single line and absent logs read empty`() {
        assertTrue(PerfectSearchDiagnostics.read(directory).isEmpty())
        PerfectSearchDiagnostics.append(directory, "entry\nwith\rbreaks")

        assertEquals(listOf("entry with breaks"), PerfectSearchDiagnostics.read(directory))
    }

    @Test
    fun `summary reports nearest rank p95 timeout count and sources`() {
        val records = (1..20).map { index ->
            val source = when {
                index <= 10 -> "book"
                index <= 15 -> "exact_solver"
                else -> "heuristic_fallback"
            }
            "perfect_search ply=$index elapsed_ms=$index source=$source exact=true timed_out=${index == 20} nodes=$index"
        }

        val summary = PerfectSearchDiagnostics.summarize(records)

        assertEquals(20, summary.sampleCount)
        assertEquals(19L, summary.p95ElapsedMs)
        assertEquals(20L, summary.maxElapsedMs)
        assertEquals(1, summary.timeoutCount)
        assertEquals(mapOf("book" to 10, "exact_solver" to 5, "heuristic_fallback" to 5), summary.sourceCounts)
    }
}
