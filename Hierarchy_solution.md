# Hierarchy.filter: solution

The task in [`Hierarchy.kt`](src/main/kotlin/exercises/hierarchy/Hierarchy.kt): implement `Hierarchy.filter(nodeIdPredicate)` so that a node is kept if and only if it **and all of its ancestors** pass the predicate, and add test cases. The tests are in [`FilterTest.kt`](src/test/kotlin/exercises/hierarchy/FilterTest.kt).

## The key observation

A node is only kept when **all of its ancestors are kept**. So every kept node still has its parent in the result, at the same position relative to it, which means **a kept node's depth never changes**.

The filtered hierarchy is therefore just the subsequence of kept nodes, with their original depths. No tree has to be built, no depths recalculated, and no stack kept.

What remains is deciding which nodes to keep. In DFS order, a node's subtree is exactly the run of nodes straight after it with a **greater depth**. When a node fails, skip forward until the depth is back at or above (numerically ≤) the failed node's depth:

```kotlin
var removedDepth = Int.MAX_VALUE              // not skipping anything
for (index in 0 until size) {
  val depth = depth(index)
  if (depth > removedDepth) continue          // inside the removed subtree
  if (nodeIdPredicate(nodeId(index))) keep(index).also { removedDepth = Int.MAX_VALUE }
  else removedDepth = depth                   // remove this node and its subtree
}
```

Using the task example with `nodeId % 3 != 0`:

```
1        kept
- 2      kept
- - 3    fails       → skip while depth > 2
- - - 4  skipped     (depth 3, inside 3's subtree; predicate not called)
- 5      kept        (depth 1 ≤ 2: skipping stops)
6        fails       → skip while depth > 0
- 7      skipped
8        kept
- 9      fails       → skip while depth > 1
- 10     kept        (sibling of 9, depth 1 ≤ 1)
- - 11   kept
```

Result: `1:0, 2:1, 5:1, 8:0, 10:1, 11:2`, which matches the expected result in the task.

## Complexity

- **Time:** O(n). One pass, and each node is looked at once.
- **Memory:** O(n) for the result. Two `IntArray`s of the input's size are filled, then trimmed to the number of kept nodes. Nothing else grows with the input.
- **Predicate calls:** at most once per node, and **never for nodes inside a removed subtree**. That matters if the predicate is expensive, for example a permission check.

## Assumptions

- The input follows the depth rules described in the KDoc. `filter` doesn't validate them; see the notes below.
- The predicate has no side effects that depend on being called for every node. It is deliberately not called for descendants of a removed node.
- The result is a new `ArrayBasedHierarchy`. The input is never changed, and the result doesn't share arrays with it.

## How it was tested

Tests were written first and run against the empty placeholder, where most of them failed, before `filter` was implemented. They describe forests in the same picture format as the KDoc, so each case reads like the tree it tests:

```kotlin
val forest = hierarchyOf("""
  1
  - 2
  - - 3
""")
```

| Group | What is covered |
|-------|-----------------|
| Task example | The provided test, unchanged |
| Edge cases | Empty hierarchy; every node passes (result equals input); no node passes; a single node |
| Removing subtrees | Failing root removes its whole tree but not the next tree; failing inner node removes its subtree but not later siblings; failing leaves only; a deep chain cut partway down; skipping stops at the right node after a jump up of several levels; ids that aren't ordered or consecutive; the input isn't changed |
| Predicate calls | Called once per node, in order, and never for nodes inside a removed subtree |
| Reference comparison | 2000 random valid forests (fixed seed, so failures can be reproduced) checked against a deliberately simple reference version. The reference is written straight from the definition: walk up each node's ancestors and check them all. Every result is also checked against the depth rules. A separate test checks the reference itself against the task's expected result. |

**Checking that the tests catch mistakes.** Three deliberately broken versions of `filter` were run against the tests:

| Broken version | Tests that failed |
|----------------|-------------------|
| Forgets to skip removed subtrees | 8 |
| Skips one node too many (also removes siblings of a failed node) | 8 |
| Calls the predicate twice per node | 1 (the predicate-calls test) |

## Notes on the provided code

These are outside the scope of the task, so the provided API was left as it was. In a real codebase I would raise them:

- **No validation in `ArrayBasedHierarchy`.** It doesn't check that both arrays have the same length, or that the depths follow the rules (first depth 0, each step at most one level deeper). Invalid input gives wrong results or an `ArrayIndexOutOfBoundsException` later, far from where the mistake was made.
- **The arrays aren't copied.** `ArrayBasedHierarchy` keeps the caller's arrays, so a caller that changes an array afterwards also changes the hierarchy. `filter` always creates fresh arrays, so its results aren't affected.
- **`size` comes from `myDepths` only.** If `myNodeIds` is shorter, `nodeId(index)` fails for valid indexes.
- **No `equals`.** The provided test compares `formatString()` output instead. That works, but structural equality would make tests and callers simpler.
- **Layout.** The original file had no `package` line, and its test class lived in the main source set, which meant the main code needed `kotlin-test` to compile. The file now declares `package exercises.hierarchy`, and the tests are in `src/test`, where Gradle runs them.
