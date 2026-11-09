@file:Suppress("DEPRECATION")

package exercises.simplecache

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests pass because they assert the faulty behaviour of [SimpleCacheOriginal].
 * Each one reproduces an issue described in `SimpleCache_issues.md`.
 *
 * The original cache reads `System.currentTimeMillis()` directly, so time cannot be injected.
 * To avoid sleeping for a minute, the tests move entry timestamps through reflection instead
 * (see [setTimestamp]). Needing to do that is itself issue 9.
 */
@DisplayName("SimpleCacheOriginal issues")
class SimpleCacheOriginalIssuesTest {

    @Nested
    @DisplayName("Issue 1: memory leak")
    inner class MemoryLeak {
        @Test
        fun `expired entries are never removed, even after being read`() {
            // Given a cache holding 1000 entries that have all expired
            val cache = SimpleCacheOriginal<Int, String>()
            repeat(1000) { cache.put(it, "value-$it") }
            cache.allKeys().forEach { cache.setTimestamp(it, System.currentTimeMillis() - 61_000) }

            // When every entry is read
            repeat(1000) { assertNull(cache.get(it)) }

            // Then all of them are still held in memory
            assertEquals(1000, cache.size())
        }
    }

    @Nested
    @DisplayName("Issue 2: no size limit")
    inner class Unbounded {
        @Test
        fun `the cache grows with every distinct key written`() {
            // Given an empty cache
            val cache = SimpleCacheOriginal<Int, Int>()

            // When 200 000 distinct keys are written within the TTL
            repeat(200_000) { cache.put(it, it) }

            // Then all 200 000 are kept: nothing bounds memory use
            assertEquals(200_000, cache.size())
        }
    }

    @Nested
    @DisplayName("Issue 3: wall-clock time")
    inner class WallClock {
        @Test
        fun `an entry written before the clock was set back an hour lives for more than an hour`() {
            // Given an entry written when the wall clock read one hour later than it does now
            // (the same state as an NTP correction moving the clock back by an hour)
            val cache = SimpleCacheOriginal<String, String>()
            cache.put("key", "value")
            cache.setTimestamp("key", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))

            // When it is read: from the entry's point of view only "-1 hour" has passed
            // Then it is still served, although its 1 minute TTL is long gone
            assertEquals("value", cache.get("key"))
        }

        @Test
        fun `an entry expires early when the clock jumps forward`() {
            // Given an entry that was written just now
            val cache = SimpleCacheOriginal<String, String>()
            cache.put("key", "value")

            // When the wall clock jumps forward by 2 minutes (simulated by moving the write back)
            cache.setTimestamp("key", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2))

            // Then the entry is treated as expired after only a moment of real time
            assertNull(cache.get("key"))
        }
    }

    @Nested
    @DisplayName("Issue 4: thundering herd")
    inner class ThunderingHerd {
        @Test
        fun `concurrent misses on the same key all call the loader`() {
            // Given an empty cache, and callers using the only pattern the API allows: get, then load, then put
            val cache = SimpleCacheOriginal<String, String>()
            val loaderCalls = AtomicInteger()
            val loadThroughCache = {
                cache.get("hot-key") ?: run {
                    loaderCalls.incrementAndGet()
                    Thread.sleep(50) // a slow backend call
                    "loaded".also { cache.put("hot-key", it) }
                }
            }

            // When 32 threads ask for the same missing key at the same time
            runConcurrently(threads = 32) { loadThroughCache() }

            // Then the backend is hit by many of them instead of once
            assertTrue(loaderCalls.get() > 1, "expected more than one loader call, got ${loaderCalls.get()}")
        }
    }

    @Nested
    @DisplayName("Issue 5: size() counts expired entries")
    inner class SizeCountsExpired {
        @Test
        fun `size reports an entry that get no longer returns`() {
            // Given an expired entry
            val cache = SimpleCacheOriginal<String, String>()
            cache.put("key", "value")
            cache.setTimestamp("key", System.currentTimeMillis() - 61_000)

            // When it is read, then it is a miss
            assertNull(cache.get("key"))

            // But size still counts it
            assertEquals(1, cache.size())
        }
    }

    @Nested
    @DisplayName("Issue 6: nullable type parameters")
    inner class Nullability {
        @Test
        fun `a null key compiles but throws at runtime`() {
            // Given a cache whose key type is nullable, which the unbounded K allows
            val cache = SimpleCacheOriginal<String?, String>()

            // When a null key is written, then ConcurrentHashMap throws
            assertFailsWith<NullPointerException> { cache.put(null, "value") }
        }

        @Test
        fun `a cached null value cannot be told apart from a miss`() {
            // Given a cached null value
            val cache = SimpleCacheOriginal<String, String?>()
            cache.put("present", null)

            // When it is read, then the result is the same as for a key that was never written
            assertEquals(cache.get("absent"), cache.get("present"))
            assertEquals(1, cache.size())
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <K : Any, V> SimpleCacheOriginal<K, V>.backingMap(): ConcurrentHashMap<K, SimpleCacheOriginal.CacheEntry<V>> =
        SimpleCacheOriginal::class.java.getDeclaredField("cache").apply { isAccessible = true }
            .get(this) as ConcurrentHashMap<K, SimpleCacheOriginal.CacheEntry<V>>

    private fun <K : Any, V> SimpleCacheOriginal<K, V>.allKeys(): Set<K> = backingMap().keys.toSet()

    /** Rewrites an entry's write time, which simulates the wall clock moving. */
    private fun <K : Any, V> SimpleCacheOriginal<K, V>.setTimestamp(key: K, timestamp: Long) {
        backingMap().computeIfPresent(key) { _, entry -> entry.copy(timestamp = timestamp) }
    }
}
