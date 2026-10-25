package pt.tiago.neonline.leveltool

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Evaluate bounded batches concurrently, accepting the first seed in input order. */
internal object DeterministicSearch {
    fun <T : Any> first(attempts: Int, parallelism: Int = 4, evaluate: (Int) -> T?): T? {
        require(attempts > 0 && parallelism in 1..4)
        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            val batchSize = parallelism * 4
            var from = 0
            while (from < attempts) {
                val until = minOf(from + batchSize,attempts)
                val futures = executor.invokeAll((from until until).map { index -> Callable { evaluate(index) } })
                for (future in futures) future.get()?.let { return it }
                from = until
                if (from % 2000 == 0) println("diner-search: checked $from/$attempts seeds")
            }
            return null
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            executor.shutdownNow()
        }
    }
}
