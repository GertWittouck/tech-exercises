# SimpleCache: code review

Reviewed for a highly concurrent service: thousands of reads per second, hundreds of writes per second, tens of threads.

The original code is kept as written in [`SimpleCacheOriginal.kt`](src/main/kotlin/exercises/simplecache/SimpleCacheOriginal.kt). Each issue below has a test in [`SimpleCacheOriginalIssuesTest`](src/test/kotlin/exercises/simplecache/SimpleCacheOriginalIssuesTest.kt) that reproduces it, and a test in [`SimpleCacheTest`](src/test/kotlin/exercises/simplecache/SimpleCacheTest.kt) that shows the fix in [`SimpleCache.kt`](src/main/kotlin/exercises/simplecache/SimpleCache.kt) works.

The good part: `ConcurrentHashMap` makes single reads and writes thread-safe, and `CacheEntry` is immutable, so readers never see a half-written entry. The issues are in what the cache does over time and under load.

## How the issues were investigated

### 1. Start from the load profile
The brief gives three numbers: thousands of reads per second, hundreds of writes per second, tens of threads. Those set the questions to ask. Checking that a single `put` followed by a `get` works isn't enough. The review asks what happens:
- **over hours and days** of running, not over one request
- **with many threads hitting the same key** at the same moment
- **at peak traffic**, including bursts of new keys
- **when something outside the code changes**, such as the system clock or the data behind the cache

### 2. Read the code through five lenses
Each lens is a question about the code. Every issue in this document came from one of them:

| Lens | Question asked | Issues found |
|------|----------------|--------------|
| Behaviour over time | What happens to an entry after it expires? Which clock decides that it has? | 1, 3, 5 |
| Resource limits | What stops memory growing? What is the most this cache can hold? | 1, 2 |
| Concurrency | Is every sequence of steps a caller needs atomic, or only each single call? | 4 |
| API and type safety | What can a caller do wrong that the compiler allows? What can't a caller do at all? | 6, 7, 8, 11 |
| Operability and testability | Can this be monitored, configured and tested without waiting in real time? | 7, 9, 10 |

The concurrency lens also confirmed what is **not** broken. `ConcurrentHashMap` makes each `get` and `put` safe on its own, and the entry is immutable, so there are no torn reads or lost writes within a single call. That kept the review from reporting problems that don't exist.

### 3. Rank by production impact
- **Blocker:** can take the service down on its own (out of memory).
- **High:** serves wrong data or can overload the systems behind the cache.
- **Medium:** wrong metrics, or missing controls that make incidents harder to handle.
- **Low:** slows the team down or hides problems, but doesn't break production by itself.

### 4. Prove each issue with a test
An issue found by reading is a claim until it's shown to happen. For each one, a test in [`SimpleCacheOriginalIssuesTest`](src/test/kotlin/exercises/simplecache/SimpleCacheOriginalIssuesTest.kt) runs against the **unchanged** original and asserts the faulty behaviour. These tests pass while the bug is there.
- **Time-based issues (1, 3, 5).** The original reads `System.currentTimeMillis()` directly, so a test can't move time forward. Instead the tests rewrite entry timestamps through reflection. That simulates time passing, or the clock moving, without sleeping. Needing reflection to test it at all is issue 9.
- **The thundering herd (4).** 32 threads start together behind a `CountDownLatch` and use the only pattern the API allows: get, then load, then put. The test counts loader calls and shows there is more than one.
- **Design issues (7, 8, 10, 11)** are about what the code lacks, so there is nothing to run. They are explained in prose and covered by tests of the fixed version.

### 5. Fix test-first, then challenge the fix
- Tests describing the intended behaviour came first, in [`SimpleCacheTest`](src/test/kotlin/exercises/simplecache/SimpleCacheTest.kt), using a fake clock so TTL tests run instantly. [`SimpleCache.kt`](src/main/kotlin/exercises/simplecache/SimpleCache.kt) was then written to pass them.
- The fixed version was then put through the same lenses, with edge cases a quick fix tends to miss. Each has a test:
  - the exact TTL boundary, one nanosecond before and at expiry
  - `System.nanoTime()` overflowing past `Long.MAX_VALUE`
  - a loader that throws while other threads are waiting for it
  - another thread finishing its load just after `getOrPut` has missed. Without the re-check in `getOrPut`, this would cause a second load.
  - another thread writing a new value while an expired entry is being removed. `map.remove(key, entry)` only removes the exact entry that was checked, so the new value survives.
- The last two are races, which normally depend on luck. The tests force them on every run with the injected clock. The cache reads the clock in the middle of each operation, and the test's clock does the "other thread's" write during that read, so the other thread acts at exactly the worst moment. To check the tests really guard the fixes, each fix was briefly removed (the conditional remove replaced with a plain `map.remove(key)`, and the re-check in `getOrPut` deleted). The matching test then failed.
- The multi-threaded tests are repeated within each run, and the whole suite was run 10 times in a row to check they are stable.
- What the fix still doesn't handle is listed under [Known limitations](#known-limitations-of-the-fixed-version) rather than left out.

## Summary

| #  | Issue                                      | Severity    | Production impact                                                       |
|----|--------------------------------------------|-------------|-------------------------------------------------------------------------|
| 1  | Expired entries are never removed          | **Blocker** | Memory grows until the JVM runs out of memory                           |
| 2  | No size limit                              | **Blocker** | Memory grows with the number of distinct keys, even within the TTL      |
| 3  | Expiry uses wall-clock time                | **High**    | Entries expire early or live far too long when the clock changes        |
| 4  | No atomic get-or-load (thundering herd)    | **High**    | A hot key expiring sends every concurrent reader to the backend at once |
| 5  | `size()` counts expired entries            | Medium      | Metrics and capacity decisions are wrong                                |
| 6  | Nullable `K` and `V`                       | Medium      | A null key throws at runtime; a cached null looks like a miss           |
| 7  | TTL hard-coded as an `Int` of milliseconds | Medium      | One TTL for every use; no way to tune it without a code change          |
| 8  | No `remove` or `clear`                     | Medium      | Stale data can't be invalidated when the source changes                 |
| 9  | Time can't be injected                     | Low         | TTL behaviour can only be tested by sleeping, which is slow and flaky   |
| 10 | No hit, miss or eviction counts            | Low         | The cache can't be monitored or tuned                                   |
| 11 | Shared mutable values and keys             | Low         | One caller's change leaks to all others; mutated keys are lost          |

---

## 1. Expired entries are never removed (memory leak)

**Problem.** `get` hides an expired entry by returning `null`, but leaves it in the map. Nothing else ever removes it. An entry only goes away if the same key is written again.

**Production impact.** With hundreds of writes per second, every key that is written once and not written again (session IDs, request IDs, one-off lookups) stays in memory for the life of the process. Heap use climbs steadily, GC pauses get longer as the old generation fills, and eventually the service fails with `OutOfMemoryError`. This is a slow leak, so it tends to show up days after a deploy rather than in testing.

**Fix.** `get` removes an expired entry when it finds one, using `map.remove(key, entry)`. That call only removes the exact entry that was checked, so a value another thread has just written is not lost. Keys that are never read again are removed by a sweep that runs at most once per TTL, started by a write. `cleanUp()` runs the sweep on demand.

**Tests.** Issue: `Issue 1: memory leak`. Fix: `Expiry › an entry is a miss once its TTL has passed, and is removed`, `Expiry › expired entries that are never read again are swept by later writes` and `Expiry › a value written while an expired entry is being removed is kept`.

## 2. No size limit

**Problem.** Nothing limits the number of entries.

**Production impact.** Even if issue 1 is fixed, a minute of writes at a few hundred per second over many distinct keys is tens of thousands of entries, and a traffic spike or a key with high cardinality (user ID × locale × currency) multiplies that. Memory use is controlled by traffic rather than by configuration, so one burst can take the service down. Retries or a crawler hitting unique URLs make it worse.

**Fix.** A `maxSize` constructor argument. When a write takes the cache over the limit, expired entries are dropped first. If that isn't enough, the oldest-written entries are dropped until the cache is at 90% of the limit, which spreads the cost of eviction over many writes. Under concurrent writes the limit can be exceeded briefly, by at most the number of writers in flight.

**Tests.** Issue: `Issue 2: no size limit`. Fix: `Size limit › …` (3 tests) and `Under concurrent load › …`.

## 3. Expiry uses wall-clock time

**Problem.** `System.currentTimeMillis()` follows the system clock, which can be moved by NTP corrections, VM migration or snapshot restore, leap-second handling, or an operator.

**Production impact.** If the clock moves **back**, `now - timestamp` gets smaller or negative, so entries are served long after their TTL. A one-hour adjustment means an hour of stale data, for example prices or permissions. If the clock moves **forward**, the whole cache expires at once, every reader misses together, and issue 4 follows. These events are rare, hard to reproduce, and hit every instance whose clock changed at the same moment.

**Fix.** Use a monotonic source: `System.nanoTime()` behind a `Ticker` interface. Expiry is checked as `now - expiresAt >= 0`, which stays correct when `nanoTime` overflows.

**Tests.** Issue: `Issue 3: wall-clock time` (both directions). Fix: `Expiry › expiry is correct when the monotonic ticker wraps around Long_MAX_VALUE`.

## 4. No atomic get-or-load (thundering herd)

**Problem.** The API only offers `get` and `put`, so callers have to write `cache.get(k) ?: load(k).also { cache.put(k, it) }`. That check and the write are separate steps, and nothing stops other threads from doing the same at the same time.

**Production impact.** With thousands of reads per second, a popular key is read by dozens of threads in the moment it expires. They all miss, and they all call the backend for the same value. The more popular the key, the bigger the spike, and it happens again every TTL. If the backend slows down, each load takes longer and more callers pile up, which can turn a slowdown into an outage. The test shows 32 concurrent callers causing up to 32 loads of one key.

**Fix.** `getOrPut(key, loader)`. The first caller to miss registers a `CompletableFuture` for the key and runs the loader. Other callers wait on that future and get the same result or exception. A failed load is not cached, so the next call tries again.

**Tests.** Issue: `Issue 4: thundering herd`. Fix: `getOrPut › concurrent misses on the same key call the loader once` (repeated 5 times), `› a load that finishes just after our miss is used instead of loading again`, `› a loader failure reaches every waiting caller` and `› a failing loader caches nothing and the next call retries`.

## 5. `size()` counts expired entries

**Problem.** `size()` returns the map size, including entries that `get` would treat as missing.

**Production impact.** Any dashboard, alert or capacity decision based on `size()` is wrong. Because of issue 1, the number only goes up, so it looks like healthy usage while it is actually showing the leak.

**Fix.** Renamed to `estimatedSize()` so the name says what it returns. Expired entries are removed on read, by the regular sweep and by `cleanUp()`, so the estimate stays close. Call `cleanUp()` first when an exact count matters.

**Tests.** Issue: `Issue 5: size() counts expired entries`. Fix: `Expiry › cleanUp removes expired entries on demand`.

## 6. Nullable `K` and `V`

**Problem.** `K` and `V` have no upper bound, so they can be nullable types. `ConcurrentHashMap` doesn't accept null keys. Values are wrapped in `CacheEntry`, so a null value is accepted, but `get` returns `V?`. IntelliJ flags this on the original code too: *"Java collection 'ConcurrentHashMap' is parameterized with a nullable type"*.

**Production impact.** `SimpleCache<String?, …>` compiles, and `put(null, …)` throws `NullPointerException` at runtime. A cached `null` (for example "this user has no profile") comes back looking exactly like a miss, so callers reload it every time, which is the opposite of what negative caching is for.

**Fix.** `SimpleCache<K : Any, V : Any>`. Null keys and values are rejected at compile time, and `null` from `get` only ever means "not cached". Callers that need negative caching wrap the value, for example in `Optional`.

**Tests.** Issue: `Issue 6: nullable type parameters`. Fix: enforced by the compiler.

## 7. TTL hard-coded as an `Int` of milliseconds

**Problem.** `private val ttlMs = 60000` can't be configured, and an unlabelled `Int` of milliseconds is easy to get wrong.

**Production impact.** Every cache has the same one-minute TTL whatever the data is. Changing it means a code change and a deploy, and it can't be tuned per environment or while handling an incident.

**Fix.** A constructor argument `ttl: Duration`, default 1 minute, checked to be positive and finite.

**Tests.** `Reading and writing › invalid settings are rejected` and every expiry test.

## 8. No `remove` or `clear`

**Problem.** There is no way to remove an entry.

**Production impact.** When the source of truth changes (a price update, a permission revoked), the cache keeps serving the old value for up to a minute and nothing can stop it. Revoked permissions that stay valid are a security problem, not only a stale-data one.

**Fix.** `remove(key)` and `clear()`.

**Tests.** `Invalidation › …`.

## 9. Time can't be injected

**Problem.** Time is read directly from `System.currentTimeMillis()`.

**Production impact.** Testing TTL behaviour means sleeping for a minute, or using reflection as the issue tests here have to. In practice expiry ends up untested, which is how issues 1, 3 and 5 got through.

**Fix.** A `Ticker` constructor argument that defaults to `System.nanoTime()`. Tests use a fake ticker, so they run instantly and give the same result every time.

**Tests.** All of `SimpleCacheTest` uses `FakeTicker`.

## 10. No hit, miss or eviction counts

**Problem.** The cache reports nothing about how well it works.

**Production impact.** Without a hit rate you can't tell if the cache helps, if the TTL or size is right, or if a sudden drop in hits explains backend load. The leak in issue 1 would also show up here as evictions staying at zero.

**Fix.** `stats()` returns hits, misses and evictions, counted with `LongAdder` so busy threads don't slow each other down. These can be exported to the service's metrics system.

**Tests.** `Statistics › hits, misses and evictions are counted`.

## 11. Shared mutable values and keys (minor)

**Problem.** Every caller gets the same value instance. Keys are hashed when they are written.

**Production impact.** If a caller changes a cached mutable object (a `MutableList`, a mutable entity), every other thread sees the change, without any synchronisation. If a key object is changed after being written, its hash changes and the entry can't be found again, but it still uses memory.

**Fix.** Documented in the class KDoc: values should be immutable and keys must have stable `equals`/`hashCode`. Copying values on each read would cost too much at this read rate.

---

## Known limitations of the fixed version

- **Eviction is approximate.** It drops the oldest-written entries, not the least recently used, and the limit can be exceeded briefly under concurrent writes.
- **Eviction cost.** Going over the limit sorts the map, which is O(n log n). Evicting down to 90% means this happens about once per `maxSize / 10` writes. Sweeps run on the writing thread, which adds an occasional latency spike to one write.
- **A load can overwrite a newer write.** If `remove` or `put` is called while a `getOrPut` load is running, the loaded value is stored after it.
- **No load timeout.** Callers waiting in `getOrPut` wait as long as the loader takes, so the loader should have its own timeout.

For production use I'd use [Caffeine](https://github.com/ben-manes/caffeine) rather than maintain this code. It handles all of the above with better eviction (W-TinyLFU), refresh-ahead and built-in stats. `SimpleCache` shows how the issues are fixed without hiding the reasoning behind a library.
