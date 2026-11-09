package exercises.simplecache

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [task] on [threads] threads that all start at the same moment, and waits for every one to finish.
 *
 * The threads first wait on a shared start gate, so they hit the cache together rather than one after another
 * as the pool starts them. Any exception thrown by a task fails the calling test.
 */
internal fun runConcurrently(threads: Int, task: () -> Unit) {
    val pool = Executors.newFixedThreadPool(threads)
    try {
        val startGate = CountDownLatch(1)
        val futures = (1..threads).map { pool.submit { startGate.await(); task() } }
        startGate.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
