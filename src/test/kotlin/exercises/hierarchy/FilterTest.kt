package exercises.hierarchy

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Hierarchy.filter")
class FilterTest {

  /** The example from the task, kept unchanged. */
  @Test
  fun testFilter() {
    val unfiltered: Hierarchy = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2))
    val filteredActual: Hierarchy = unfiltered.filter { nodeId -> nodeId % 3 != 0 }
    val filteredExpected: Hierarchy = ArrayBasedHierarchy(
      intArrayOf(1, 2, 5, 8, 10, 11),
      intArrayOf(0, 1, 1, 0, 1, 2))
    assertEquals(filteredExpected.formatString(), filteredActual.formatString())
  }

  @Nested
  @DisplayName("Edge cases")
  inner class EdgeCases {
    @Test
    fun `an empty hierarchy stays empty`() {
      // Given an empty hierarchy
      val empty = hierarchyOf("")
      // When it is filtered
      val filtered = empty.filter { true }
      // Then the result is empty
      assertEquals(0, filtered.size)
    }

    @Test
    fun `when every node passes, the result equals the input`() {
      val forest = taskExample()
      assertSameHierarchy(forest, forest.filter { true })
    }

    @Test
    fun `when no node passes, the result is empty`() {
      assertEquals(0, taskExample().filter { false }.size)
    }

    @Test
    fun `a single node is kept or removed by the predicate`() {
      val single = hierarchyOf("7")
      assertSameHierarchy(single, single.filter { it == 7 })
      assertEquals(0, single.filter { it != 7 }.size)
    }
  }

  @Nested
  @DisplayName("Removing subtrees")
  inner class Subtrees {
    @Test
    fun `a failing root removes its whole tree, and the trees after it stay`() {
      // Given three trees
      val forest = hierarchyOf("""
        1
        - 2
        3
        - 4
        - - 5
        6
      """)
      // When the root of the middle tree fails
      val filtered = forest.filter { it != 3 }
      // Then that whole tree is gone, and the trees before and after it are untouched
      assertSameHierarchy(hierarchyOf("""
        1
        - 2
        6
      """), filtered)
    }

    @Test
    fun `a failing inner node removes its subtree, and its later siblings stay`() {
      val forest = hierarchyOf("""
        1
        - 2
        - - 3
        - - - 4
        - 5
        - - 6
      """)
      val filtered = forest.filter { it != 2 }
      assertSameHierarchy(hierarchyOf("""
        1
        - 5
        - - 6
      """), filtered)
    }

    @Test
    fun `failing leaves are removed on their own`() {
      val forest = hierarchyOf("""
        1
        - 2
        - 3
        - - 4
      """)
      val filtered = forest.filter { it != 2 && it != 4 }
      assertSameHierarchy(hierarchyOf("""
        1
        - 3
      """), filtered)
    }

    @Test
    fun `a deep chain is cut where the first node fails`() {
      val chain = hierarchyOf("""
        1
        - 2
        - - 3
        - - - 4
        - - - - 5
      """)
      val filtered = chain.filter { it != 3 }
      assertSameHierarchy(hierarchyOf("""
        1
        - 2
      """), filtered)
    }

    @Test
    fun `skipping stops at the first node that is not inside the removed subtree`() {
      // Given a removed subtree that ends with a jump up of several levels
      val forest = hierarchyOf("""
        1
        - 2
        - - 3
        - - - 4
        - - 5
        - 6
      """)
      // When node 3 fails
      val filtered = forest.filter { it != 3 }
      // Then 4 (its child) goes, but 5 (its sibling) and 6 (its uncle) stay
      assertSameHierarchy(hierarchyOf("""
        1
        - 2
        - - 5
        - 6
      """), filtered)
    }

    @Test
    fun `node ids need not be ordered or consecutive`() {
      val forest = hierarchyOf("""
        42
        - 7
        - - 100
        - 3
      """)
      val filtered = forest.filter { it != 7 }
      assertSameHierarchy(hierarchyOf("""
        42
        - 3
      """), filtered)
    }

    @Test
    fun `filtering does not change the original hierarchy`() {
      val forest = taskExample()
      val before = forest.formatString()
      forest.filter { it % 2 == 0 }
      assertEquals(before, forest.formatString())
    }
  }

  @Nested
  @DisplayName("Predicate calls")
  inner class PredicateCalls {
    @Test
    fun `the predicate is called once per node, and never for nodes inside a removed subtree`() {
      // Given the task example, where nodes 3, 6 and 9 fail
      val calls = mutableListOf<Int>()
      // When it is filtered
      taskExample().filter { nodeId ->
        calls += nodeId
        nodeId % 3 != 0
      }
      // Then 4 (child of 3) and 7 (child of 6) are never checked, and no node is checked twice
      assertEquals(listOf(1, 2, 3, 5, 6, 8, 9, 10, 11), calls)
    }
  }

  @Nested
  @DisplayName("Against a reference implementation")
  inner class Reference {
    @Test
    fun `filter matches a simple reference implementation on 2000 random forests`() {
      val random = Random(seed = 20261107) // fixed seed: any failure can be reproduced
      repeat(2_000) { run ->
        // Given a random valid forest of up to 40 nodes, and a random predicate
        val forest = randomHierarchy(random, size = random.nextInt(0, 41))
        val failing = (0..<forest.size).map { forest.nodeId(it) }.filter { random.nextInt(4) == 0 }.toSet()
        val predicate = { nodeId: Int -> nodeId !in failing }

        // When it is filtered
        val actual = forest.filter(predicate)

        // Then the result matches the reference, and still follows the depth rules
        val context = "run $run: forest ${forest.formatString()}, failing $failing"
        assertEquals(referenceFilter(forest, predicate).formatString(), actual.formatString(), context)
        assertFollowsDepthRules(actual, context)
      }
    }

    @Test
    fun `the reference implementation agrees with the task example`() {
      // Checks the checker: the reference must give the same answer as the expected result in the task.
      val expected = hierarchyOf("""
        1
        - 2
        - 5
        8
        - 10
        - - 11
      """)
      assertSameHierarchy(expected, referenceFilter(taskExample()) { it % 3 != 0 })
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** The forest from the KDoc of [Hierarchy]. */
  private fun taskExample() = ArrayBasedHierarchy(
    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2))

  /**
   * Builds a hierarchy from the same picture the KDoc uses: one node per line, with one `-` per level of depth.
   * ```
   * 1
   * - 2
   * - - 3
   * ```
   */
  private fun hierarchyOf(picture: String): Hierarchy {
    val lines = picture.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val ids = lines.map { line -> line.split(" ").last().toInt() }
    val depths = lines.map { line -> line.count { it == '-' } }
    return ArrayBasedHierarchy(ids.toIntArray(), depths.toIntArray())
  }

  private fun assertSameHierarchy(expected: Hierarchy, actual: Hierarchy) =
    assertEquals(expected.formatString(), actual.formatString())

  /** Checks the invariants listed in the KDoc of [Hierarchy]. */
  private fun assertFollowsDepthRules(hierarchy: Hierarchy, context: String) {
    if (hierarchy.size == 0) return
    assertEquals(0, hierarchy.depth(0), "first depth must be 0; $context")
    for (i in 1..<hierarchy.size) {
      val step = hierarchy.depth(i) - hierarchy.depth(i - 1)
      assertTrue(step <= 1, "depth can increase by at most one level, at index $i; $context")
      assertTrue(hierarchy.depth(i) >= 0, "depth must not be negative, at index $i; $context")
    }
  }

  /** A random forest that follows the depth rules, with distinct, shuffled node ids. */
  private fun randomHierarchy(random: Random, size: Int): Hierarchy {
    val ids = (1..size).shuffled(random).toIntArray()
    val depths = IntArray(size)
    for (i in 1..<size) {
      depths[i] = random.nextInt(0, depths[i - 1] + 2) // anything from 0 to one level deeper
    }
    return ArrayBasedHierarchy(ids, depths)
  }

  /**
   * A deliberately simple, slow version of filter, written straight from the definition in the task:
   * a node is kept if it and every one of its ancestors pass the predicate.
   * A node's parent is the nearest earlier node that is one level shallower.
   */
  private fun referenceFilter(hierarchy: Hierarchy, predicate: (Int) -> Boolean): Hierarchy {
    fun parentIndex(index: Int): Int? =
      (index - 1 downTo 0).firstOrNull { hierarchy.depth(it) == hierarchy.depth(index) - 1 }

    fun keptWithAncestors(index: Int): Boolean =
      generateSequence(index) { parentIndex(it) }.all { predicate(hierarchy.nodeId(it)) }

    val kept = (0..<hierarchy.size).filter { keptWithAncestors(it) }
    return ArrayBasedHierarchy(
      kept.map { hierarchy.nodeId(it) }.toIntArray(),
      kept.map { hierarchy.depth(it) }.toIntArray())
  }
}
