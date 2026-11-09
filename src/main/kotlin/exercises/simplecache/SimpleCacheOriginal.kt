package exercises.simplecache

import java.util.concurrent.ConcurrentHashMap

/**
 * The implementation submitted for review, kept verbatim (apart from the package and class name)
 * so that `SimpleCacheOriginalIssuesTest` can reproduce each issue described in `SimpleCache_issues.md`.
 *
 * Do not use: see [SimpleCache] for the corrected implementation.
 */
@Deprecated("Has production issues, see SimpleCache_issues.md", ReplaceWith("SimpleCache"))
class SimpleCacheOriginal<K, V> {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val ttlMs = 60000 // 1 minute

    data class CacheEntry<V>(val value: V, val timestamp: Long)

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(value, System.currentTimeMillis())
    }

    fun get(key: K): V? {
        val entry = cache[key]
        if (entry != null) {
            if (System.currentTimeMillis() - entry.timestamp < ttlMs) {
                return entry.value
            }
        }
        return null
    }

    fun size(): Int {
        return cache.size
    }
}
