package pt.tiago.neonline.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Bounded, app-private history used only by the player's explicit diagnostic export. */
object PerfectSearchDiagnostics {
    const val MAX_ENTRIES = 200
    const val MAX_LINE_CHARS = 256
    private val sources = listOf("book", "exact_solver", "heuristic_fallback")
    private const val FILE_NAME = "perfect-search.log"
    private val lock = Any()

    data class Summary(
        val sampleCount: Int,
        val p95ElapsedMs: Long?,
        val maxElapsedMs: Long?,
        val timeoutCount: Int,
        val sourceCounts: Map<String, Int>,
    )

    fun append(filesDir: File, record: String) = synchronized(lock) {
        val destination = File(File(filesDir, "diagnostics"), FILE_NAME)
        require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) {
            "cannot create diagnostics directory"
        }
        val records = (readUnlocked(destination) + record.singleLine()).takeLast(MAX_ENTRIES)
        val temporary = File.createTempFile("perfect-search-", ".tmp", destination.parentFile)
        try {
            temporary.writeText(records.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun read(filesDir: File): List<String> = synchronized(lock) {
        readUnlocked(File(File(filesDir, "diagnostics"), FILE_NAME)).takeLast(MAX_ENTRIES)
    }

    /** Nearest-rank p95 over valid structured search records; malformed lines are ignored. */
    fun summarize(records: List<String>): Summary {
        val samples = records.mapNotNull(::parseRecord)
        val sortedTimes = samples.map { it.elapsedMs }.sorted()
        val p95Index = if (sortedTimes.isEmpty()) -1 else ((95L * sortedTimes.size + 99L) / 100L).toInt() - 1
        val sourceCounts = sources.associateWith { source -> samples.count { it.source == source } }
        return Summary(
            sampleCount = samples.size,
            p95ElapsedMs = sortedTimes.getOrNull(p95Index),
            maxElapsedMs = sortedTimes.lastOrNull(),
            timeoutCount = samples.count { it.timedOut },
            sourceCounts = sourceCounts,
        )
    }

    private data class Sample(val elapsedMs: Long, val source: String, val timedOut: Boolean)

    private fun parseRecord(record: String): Sample? {
        if (!record.startsWith("perfect_search ")) return null
        val fields = record.split(' ').drop(1).mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
        }.toMap()
        val elapsedMs = fields["elapsed_ms"]?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val source = fields["source"]?.takeIf { it in sources } ?: return null
        val timedOut = fields["timed_out"]?.toBooleanStrictOrNull() ?: return null
        return Sample(elapsedMs, source, timedOut)
    }

    private fun readUnlocked(file: File): List<String> =
        if (file.isFile) file.readLines(Charsets.UTF_8).map { it.singleLine() } else emptyList()

    private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ').take(MAX_LINE_CHARS)
}
