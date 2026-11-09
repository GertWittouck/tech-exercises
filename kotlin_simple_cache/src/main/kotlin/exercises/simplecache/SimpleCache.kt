package exercises.simplecache

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A monotonic time source in nanoseconds. Only differences between two readings are meaningful. */
fun interface Ticker {
    fun read(): Long

    companion object {
        val SYSTEM: Ticker = Ticker { System.nanoTime() }
    }
}

data class CacheStats(val hits: Long, val misses: Long, val evictions: Long)

/**
 * A thread-safe in-memory cache with a fixed time-to-live per entry and an upper bound on its size.
 *
 * - Expiry uses a monotonic [Ticker], so wall-clock changes (NTP, DST, manual changes) have no effect.
 * - Expired entries are removed when they are read, and by a sweep that runs at most once per [ttl],
 *   triggered by a write. [cleanUp] runs that sweep on demand.
 * - When a write takes the cache above [maxSize], expired entries are dropped first; if that is not enough,
 *   the oldest-written entries are dropped until the cache is 10% under [maxSize]. Under concurrent writes the
 *   limit can be exceeded briefly by the number of writers in flight.
 * - [getOrPut] loads a missing key once, however many threads ask for it at the same time.
 *
 * Keys and values must not be null. Keys must have stable `equals`/`hashCode`, and values should be
 * immutable, because every caller receives the same instance.
 */
class SimpleCache<K : Any, V : Any>(
    private val ttl: Duration = 1.minutes,
    private val maxSize: Int = 10_000,
    private val ticker: Ticker = Ticker.SYSTEM,
) {
    init {
        require(ttl.isPositive() && ttl.isFinite()) { "ttl must be positive and finite, was $ttl" }
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    /** Identity equality on purpose: `map.remove(key, entry)` must only remove this exact instance. */
    private class Entry<V>(val value: V, val expiresAt: Long, val writeOrder: Long)

    private val ttlNanos = ttl.inWholeNanoseconds
    private val evictionTarget = maxSize - maxSize / 10
    private val map = ConcurrentHashMap<K, Entry<V>>()
    private val loadsInFlight = ConcurrentHashMap<K, CompletableFuture<V>>()
    private val writeCounter = AtomicLong()
    private val lastSweep = AtomicLong(ticker.read())
    private val evictionLock = Any()

    private val hits = LongAdder()
    private val misses = LongAdder()
    private val evictions = LongAdder()

    /** Returns the value for [key], or null if it is absent or has expired. */
    fun get(key: K): V? {
        val value = liveValue(key)
        if (value != null) hits.increment() else misses.increment()
        return value
    }

    fun put(key: K, value: V) {
        val now = ticker.read()
        map[key] = Entry(value, expiresAt = now + ttlNanos, writeOrder = writeCounter.incrementAndGet())
        sweepIfDue(now)
        if (map.size > maxSize) evictToFit()
    }

    /**
     * Returns the value for [key], calling [loader] to compute and cache it if it is absent or expired.
     *
     * Concurrent callers for the same key wait for a single [loader] call and all receive its result,
     * or its exception. A failed load caches nothing, so the next call tries again.
     * The loader runs on the calling thread and should have its own timeout.
     */
    fun getOrPut(key: K, loader: () -> V): V {
        get(key)?.let { return it }

        val load = CompletableFuture<V>()
        val inFlight = loadsInFlight.putIfAbsent(key, load)
        if (inFlight != null) return awaitLoad(inFlight)

        try {
            // Another thread may have finished loading between our miss and claiming the load.
            val value = liveValue(key) ?: loader().also { put(key, it) }
            load.complete(value)
            return value
        } catch (e: Throwable) {
            load.completeExceptionally(e)
            throw e
        } finally {
            loadsInFlight.remove(key, load)
        }
    }

    fun remove(key: K) {
        map.remove(key)
    }

    fun clear() {
        map.clear()
    }

    /** The number of entries held, which can include expired entries not yet swept. */
    fun estimatedSize(): Int = map.size

    /** Removes every expired entry now. */
    fun cleanUp() {
        val now = ticker.read()
        lastSweep.set(now)
        removeExpired(now)
    }

    fun stats() = CacheStats(hits = hits.sum(), misses = misses.sum(), evictions = evictions.sum())

    private fun liveValue(key: K): V? {
        val entry = map[key] ?: return null
        if (!entry.isExpired(ticker.read())) return entry.value
        // Conditional remove: a newer value written by another thread meanwhile is kept.
        if (map.remove(key, entry)) evictions.increment()
        return null
    }

    // Compare by subtraction rather than `now >= expiresAt`, so that System.nanoTime overflow is handled.
    private fun Entry<V>.isExpired(now: Long) = now - expiresAt >= 0

    private fun sweepIfDue(now: Long) {
        val last = lastSweep.get()
        // Only the thread that wins the compareAndSet sweeps; the others carry on.
        if (now - last >= ttlNanos && lastSweep.compareAndSet(last, now)) removeExpired(now)
    }

    private fun removeExpired(now: Long) {
        for ((key, entry) in map) {
            if (entry.isExpired(now) && map.remove(key, entry)) evictions.increment()
        }
    }

    private fun evictToFit() {
        synchronized(evictionLock) {
            if (map.size <= maxSize) return
            removeExpired(ticker.read())
            if (map.size <= maxSize) return

            // Evicting down to 90% of maxSize spreads the cost of this O(n log n) scan over many writes.
            val excess = map.size - evictionTarget
            map.entries
                .sortedBy { it.value.writeOrder }
                .take(excess)
                .forEach { (key, entry) -> if (map.remove(key, entry)) evictions.increment() }
        }
    }

    private fun awaitLoad(load: CompletableFuture<V>): V =
        try {
            load.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
}
