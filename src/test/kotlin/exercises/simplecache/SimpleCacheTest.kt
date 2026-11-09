package exercises.simplecache

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@DisplayName("SimpleCache")
class SimpleCacheTest {

    private val ticker = FakeTicker()

    private fun <K : Any, V : Any> newCache(ttl: Duration = 1.minutes, maxSize: Int = 1_000) =
        SimpleCache<K, V>(ttl = ttl, maxSize = maxSize, ticker = ticker)

    @Nested
    @DisplayName("Reading and writing")
    inner class ReadWrite {
        @Test
        fun `a written value is returned`() {
            // Given
            val cache = newCache<String, String>()
            // When
            cache.put("key", "value")
            // Then
            assertEquals("value", cache.get("key"))
        }

        @Test
        fun `a key that was never written is a miss`() {
            val cache = newCache<String, String>()
            assertNull(cache.get("absent"))
        }

        @Test
        fun `writing a key again replaces its value`() {
            val cache = newCache<String, String>()
            cache.put("key", "first")
            cache.put("key", "second")
            assertEquals("second", cache.get("key"))
            assertEquals(1, cache.estimatedSize())
        }

        @Test
        fun `invalid settings are rejected`() {
            assertFailsWith<IllegalArgumentException> { SimpleCache<String, String>(ttl = Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { SimpleCache<String, String>(ttl = (-1).seconds) }
            assertFailsWith<IllegalArgumentException> { SimpleCache<String, String>(maxSize = 0) }
        }
    }

    @Nested
    @DisplayName("Expiry (fixes issues 1, 3, 5 and 9)")
    inner class Expiry {
        @Test
        fun `an entry is served until just before its TTL`() {
            // Given an entry with a 1 minute TTL
            val cache = newCache<String, String>(ttl = 1.minutes)
            cache.put("key", "value")
            // When 1 nanosecond less than the TTL passes
            ticker.advance(1.minutes - Duration.parse("1ns"))
            // Then it is still served
            assertEquals("value", cache.get("key"))
        }

        @Test
        fun `an entry is a miss once its TTL has passed, and is removed`() {
            // Given an entry with a 1 minute TTL
            val cache = newCache<String, String>(ttl = 1.minutes)
            cache.put("key", "value")
            // When the TTL passes
            ticker.advance(1.minutes)
            // Then it is a miss and no longer held
            assertNull(cache.get("key"))
            assertEquals(0, cache.estimatedSize())
        }

        @Test
        fun `expired entries that are never read again are swept by later writes`() {
            // Given 100 entries that expire and are never read again
            val cache = newCache<Int, String>(ttl = 1.minutes)
            repeat(100) { cache.put(it, "value") }
            ticker.advance(1.minutes)
            // When a single new entry is written after the sweep interval
            cache.put(1_000, "fresh")
            // Then only the fresh entry is held
            assertEquals(1, cache.estimatedSize())
        }

        @Test
        fun `cleanUp removes expired entries on demand`() {
            val cache = newCache<Int, String>(ttl = 1.minutes)
            repeat(10) { cache.put(it, "value") }
            ticker.advance(30.seconds)
            cache.put(10, "younger")
            ticker.advance(30.seconds)

            cache.cleanUp()

            assertEquals(1, cache.estimatedSize())
            assertEquals("younger", cache.get(10))
        }

        @Test
        fun `writing a key again restarts its TTL`() {
            val cache = newCache<String, String>(ttl = 1.minutes)
            cache.put("key", "first")
            ticker.advance(45.seconds)
            cache.put("key", "second")
            ticker.advance(45.seconds)
            assertEquals("second", cache.get("key"))
        }

        @Test
        fun `a value written while an expired entry is being removed is kept`() {
            // Given an expired entry, and a clock that lets us act at the exact moment get checks the time.
            // get looks the entry up, reads the clock to check expiry, then removes it. Writing a new value
            // during that clock read reproduces the race deterministically.
            val ticker = RaceTicker()
            val cache = SimpleCache<String, String>(ttl = 1.minutes, maxSize = 100, ticker = ticker)
            cache.put("key", "old")
            ticker.now = 1.minutes.inWholeNanoseconds
            ticker.onNextRead = { cache.put("key", "new") } // another thread writing at the worst moment

            // When get finds the old entry expired and removes it
            assertNull(cache.get("key"))

            // Then only the old entry was removed: the new value survives
            assertEquals("new", cache.get("key"))
        }

        @Test
        fun `expiry is correct when the monotonic ticker wraps around Long_MAX_VALUE`() {
            // Given a ticker just below overflow (System.nanoTime has an arbitrary origin)
            ticker.set(Long.MAX_VALUE - 10.seconds.inWholeNanoseconds)
            val cache = newCache<String, String>(ttl = 1.minutes)
            cache.put("key", "value")
            // When it passes the overflow point but not the TTL
            ticker.advance(30.seconds)
            // Then the entry is still served
            assertEquals("value", cache.get("key"))
            // And it expires on time afterwards
            ticker.advance(30.seconds)
            assertNull(cache.get("key"))
        }
    }

    @Nested
    @DisplayName("Size limit (fixes issue 2)")
    inner class SizeLimit {
        @Test
        fun `the cache never holds more than maxSize entries`() {
            // Given a cache limited to 100 entries
            val cache = newCache<Int, Int>(maxSize = 100)
            // When 10 000 distinct keys are written
            repeat(10_000) {
                cache.put(it, it)
                ticker.advance(Duration.parse("1ms"))
            }
            // Then it holds at most 100
            assertTrue(cache.estimatedSize() <= 100, "size was ${cache.estimatedSize()}")
        }

        @Test
        fun `the oldest entries are evicted first`() {
            // Given a full cache of 10 entries written one second apart
            val cache = newCache<Int, Int>(maxSize = 10)
            repeat(10) {
                cache.put(it, it)
                ticker.advance(1.seconds)
            }
            // When one more entry is written
            cache.put(10, 10)
            // Then the oldest entry is gone and the newest are kept
            assertNull(cache.get(0))
            assertEquals(9, cache.get(9))
            assertEquals(10, cache.get(10))
        }

        @Test
        fun `expired entries are evicted before live ones`() {
            // Given a full cache in which one entry has expired
            val cache = newCache<Int, Int>(ttl = 1.minutes, maxSize = 3)
            cache.put(0, 0)
            ticker.advance(50.seconds)
            cache.put(1, 1)
            cache.put(2, 2)
            ticker.advance(10.seconds) // key 0 has expired, 1 and 2 are live
            // When one more entry is written
            cache.put(3, 3)
            // Then only the expired entry was dropped
            assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).mapNotNull { cache.get(it) })
        }
    }

    @Nested
    @DisplayName("Invalidation (fixes issue 8)")
    inner class Invalidation {
        @Test
        fun `remove drops a single entry`() {
            val cache = newCache<String, String>()
            cache.put("a", "1")
            cache.put("b", "2")

            cache.remove("a")

            assertNull(cache.get("a"))
            assertEquals("2", cache.get("b"))
        }

        @Test
        fun `clear drops every entry`() {
            val cache = newCache<String, String>()
            cache.put("a", "1")
            cache.put("b", "2")

            cache.clear()

            assertEquals(0, cache.estimatedSize())
        }
    }

    @Nested
    @DisplayName("getOrPut (fixes issue 4)")
    inner class GetOrPut {
        @Test
        fun `a cached value is returned without calling the loader`() {
            val cache = newCache<String, String>()
            cache.put("key", "cached")
            assertEquals("cached", cache.getOrPut("key") { error("loader must not run") })
        }

        @Test
        fun `a missing value is loaded and cached`() {
            val cache = newCache<String, String>()
            assertEquals("loaded", cache.getOrPut("key") { "loaded" })
            assertEquals("loaded", cache.get("key"))
        }

        @Test
        fun `an expired value is reloaded`() {
            val cache = newCache<String, String>()
            cache.put("key", "old")
            ticker.advance(1.minutes)
            assertEquals("new", cache.getOrPut("key") { "new" })
        }

        @Test
        fun `a failing loader caches nothing and the next call retries`() {
            val cache = newCache<String, String>()
            val failure = IllegalStateException("backend down")

            val thrown = assertFailsWith<IllegalStateException> { cache.getOrPut("key") { throw failure } }

            assertSame(failure, thrown)
            assertNull(cache.get("key"))
            assertEquals("recovered", cache.getOrPut("key") { "recovered" })
        }

        @RepeatedTest(5)
        fun `concurrent misses on the same key call the loader once`() {
            // Given an empty cache and a slow loader
            val cache = SimpleCache<String, String>(ttl = 1.minutes, maxSize = 100)
            val loaderCalls = AtomicInteger()
            val results = java.util.concurrent.ConcurrentLinkedQueue<String>()

            // When 32 threads ask for the same missing key at the same time
            runConcurrently(threads = 32) {
                results += cache.getOrPut("hot-key") {
                    loaderCalls.incrementAndGet()
                    Thread.sleep(50)
                    "loaded"
                }
            }

            // Then the loader ran once and every caller got its value
            assertEquals(1, loaderCalls.get())
            assertEquals(List(32) { "loaded" }, results.toList())
        }

        @Test
        fun `a load that finishes just after our miss is used instead of loading again`() {
            // Given an expired entry, and a clock that lets us act at the exact moment our miss is decided.
            // getOrPut first calls get, which reads the clock to find the entry expired. Another thread finishing
            // its load during that read means our miss is out of date before we claim the load ourselves.
            val ticker = RaceTicker()
            val cache = SimpleCache<String, String>(ttl = 1.minutes, maxSize = 100, ticker = ticker)
            cache.put("key", "old")
            ticker.now = 1.minutes.inWholeNanoseconds
            ticker.onNextRead = { cache.put("key", "loaded by another thread") }
            val loaderCalls = AtomicInteger()

            // When getOrPut misses, then claims the load
            val value = cache.getOrPut("key") {
                loaderCalls.incrementAndGet()
                "loaded again"
            }

            // Then it re-checks the cache, finds the other thread's value, and does not call the loader
            assertEquals("loaded by another thread", value)
            assertEquals(0, loaderCalls.get())
        }

        @Test
        fun `a loader failure reaches every waiting caller`() {
            val cache = SimpleCache<String, String>(ttl = 1.minutes, maxSize = 100)
            val failures = AtomicInteger()

            runConcurrently(threads = 8) {
                try {
                    cache.getOrPut("key") {
                        Thread.sleep(50)
                        throw IllegalStateException("backend down")
                    }
                } catch (_: IllegalStateException) {
                    failures.incrementAndGet()
                }
            }

            assertEquals(8, failures.get())
        }
    }

    @Nested
    @DisplayName("Statistics (fixes issue 10)")
    inner class Statistics {
        @Test
        fun `hits, misses and evictions are counted`() {
            val cache = newCache<String, String>(ttl = 1.minutes, maxSize = 1)
            cache.put("a", "1")
            cache.get("a") // hit
            cache.get("b") // miss
            cache.put("b", "2") // evicts "a"
            ticker.advance(1.minutes)
            cache.get("b") // miss, expired

            assertEquals(CacheStats(hits = 1, misses = 2, evictions = 2), cache.stats())
        }
    }

    @Nested
    @DisplayName("Under concurrent load")
    inner class Concurrency {
        @RepeatedTest(5)
        fun `mixed concurrent reads and writes keep the size limit and lose no values`() {
            // Given a cache limited to 500 entries
            val cache = SimpleCache<Int, Int>(ttl = 1.minutes, maxSize = 500)
            val wrongValues = AtomicInteger()

            // When 32 threads each run 10 000 mixed reads and writes over 2 000 keys
            runConcurrently(threads = 32) {
                val random = java.util.concurrent.ThreadLocalRandom.current()
                repeat(10_000) {
                    val key = random.nextInt(2_000)
                    if (random.nextInt(10) < 8) {
                        val value = cache.get(key)
                        if (value != null && value != key * 2) wrongValues.incrementAndGet()
                    } else {
                        cache.put(key, key * 2)
                    }
                }
            }

            // Then no reader saw a value for another key, and the limit held once writers finished
            assertEquals(0, wrongValues.get())
            assertTrue(cache.estimatedSize() <= 500, "size was ${cache.estimatedSize()}")
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private class FakeTicker : Ticker {
        private val nanos = AtomicLong(0)
        override fun read(): Long = nanos.get()
        fun advance(by: Duration) { nanos.addAndGet(by.inWholeNanoseconds) }
        fun set(value: Long) = nanos.set(value)
    }

    /** A fixed clock that runs [onNextRead] once, inside the next read, to simulate another thread acting then. */
    private class RaceTicker : Ticker {
        var now = 0L
        var onNextRead: (() -> Unit)? = null

        override fun read(): Long {
            val action = onNextRead
            onNextRead = null // cleared first, so reads made by the action itself don't trigger it again
            action?.invoke()
            return now
        }
    }
}
