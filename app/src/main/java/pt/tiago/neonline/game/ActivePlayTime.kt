package pt.tiago.neonline.game

/** Monotonic active intervals, independent of wall time and the deterministic engine. */
class ActivePlayTime(initialMs: Long = 0L, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var accumulated = initialMs.also { require(it >= 0L) }
    private var startedAt: Long? = null

    val elapsedMs: Long get() {
        val delta = startedAt?.let { (nowMs() - it).coerceAtLeast(0L) } ?: 0L
        return accumulated + minOf(delta, Long.MAX_VALUE - accumulated)
    }

    fun resume() { if (startedAt == null) startedAt = nowMs() }
    fun pause() { accumulated = elapsedMs; startedAt = null }
    fun reset() { accumulated = 0L; startedAt = null }
}
