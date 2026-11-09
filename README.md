# Take-home assessments: SimpleCache and Hierarchy

This repository holds two exercises:

- **SimpleCache:** a review of a cache implementation meant for a highly concurrent service, plus a corrected version. Described below.
- **Hierarchy:** an implementation of `Hierarchy.filter` with tests. See [Hierarchy](#hierarchy) and [`Hierarchy_solution.md`](Hierarchy_solution.md).

## SimpleCache

A review of a `SimpleCache` implementation meant for a highly concurrent service, plus a corrected version. The original task is in [`Assessment - SimpleCache.md`](Assessment%20-%20SimpleCache.md).

**Start with [`SimpleCache_issues.md`](SimpleCache_issues.md).** It is the review: each issue, what it does in production, and how it is fixed.

### Approach

How the issues were found, ranked and proved is described in [How the issues were investigated](SimpleCache_issues.md#how-the-issues-were-investigated). In short, the project is set up like this:

1. **The original is kept as submitted** in [`SimpleCacheOriginal.kt`](src/main/kotlin/exercises/simplecache/SimpleCacheOriginal.kt), so the issues can be shown running rather than only described.
2. **Each issue is reproduced by a test.** [`SimpleCacheOriginalIssuesTest`](src/test/kotlin/exercises/simplecache/SimpleCacheOriginalIssuesTest.kt) asserts the faulty behaviour, so these tests pass while the bug is there. The original has no way to inject time, so these tests change entry timestamps through reflection.
3. **The fixed version is tested against its intended behaviour.** [`SimpleCacheTest`](src/test/kotlin/exercises/simplecache/SimpleCacheTest.kt) uses Given/When/Then tests with a fake clock, plus multi-threaded tests: 32 threads doing mixed reads and writes, and 32 threads loading one key at once.
4. **[`SimpleCache`](src/main/kotlin/exercises/simplecache/SimpleCache.kt)** fixes the issues and adds the missing API:

   ```kotlin
   val cache = SimpleCache<String, Product>(ttl = 5.minutes, maxSize = 50_000)

   cache.put(id, product)
   cache.get(id)                              // null means not cached or expired
   cache.getOrPut(id) { repository.load(id) } // loads once, however many threads ask
   cache.remove(id); cache.clear()
   cache.estimatedSize(); cache.cleanUp(); cache.stats()
   ```

### Assumptions

- The load in the brief (thousands of reads/s, hundreds of writes/s, tens of threads) means reads matter most, so `get` takes no locks.
- A fixed TTL from the time of writing is what's wanted. Reading an entry doesn't extend its TTL.
- A size limit that can be exceeded briefly is acceptable. A strict limit would need a lock on every write.
- Evicting the oldest-written entries is good enough. True LRU would need a shared ordering structure updated on every read, which costs more than it gains here.
- Cleanup runs on the threads calling the cache rather than on a background thread, so the cache needs no lifecycle (`close()`), at the cost of an occasional slower write.
- Null keys and values aren't supported. Callers wrap values if they need to cache "not found".
- In a real service I'd use [Caffeine](https://github.com/ben-manes/caffeine) instead. See the end of the issues document.

## Hierarchy

The task, described in [`Hierarchy.kt`](src/main/kotlin/exercises/hierarchy/Hierarchy.kt), is to implement `Hierarchy.filter(predicate)`: keep a node only if it and all of its ancestors pass the predicate.

**Approach.** A kept node's ancestors are all kept, so its depth never changes. That makes `filter` a single O(n) pass: when a node fails, skip the nodes after it with a greater depth (its subtree). The predicate is never called for those skipped nodes. [`Hierarchy_solution.md`](Hierarchy_solution.md) explains this with a worked example, and covers complexity, assumptions, the tests, and notes on the provided code.

**Tests.** [`FilterTest.kt`](src/test/kotlin/exercises/hierarchy/FilterTest.kt) keeps the provided test and adds edge cases, subtree-removal cases and predicate-call checks. It also compares `filter` with a simple reference version on 2000 random forests generated from a fixed seed.

## Running locally

Requires JDK 25. The Gradle wrapper downloads everything else.

```bash
./gradlew test   # runs all tests: SimpleCache issues, SimpleCache, and Hierarchy.filter
```

Test reports are written to `build/reports/tests/test/index.html`.

## Layout

```
src/main/kotlin/exercises/simplecache/
  SimpleCache.kt              fixed implementation
  SimpleCacheOriginal.kt      code under review, unchanged
src/main/kotlin/exercises/hierarchy/
  Hierarchy.kt                task file, with filter() implemented
src/test/kotlin/exercises/simplecache/
  SimpleCacheTest.kt                 behaviour of the fixed cache
  SimpleCacheOriginalIssuesTest.kt   reproduces each issue in the original
  ConcurrencyTestSupport.kt          shared multi-threaded test helper
src/test/kotlin/exercises/hierarchy/
  FilterTest.kt               tests for Hierarchy.filter
Assessment - SimpleCache.md    the original SimpleCache task
SimpleCache_issues.md          the SimpleCache review
Hierarchy_solution.md          the Hierarchy approach and tests
```
