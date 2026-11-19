package dev.logicgrid.core

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Brute-force soundness: is every mark the closure derives actually implied?
 *
 * The conformance fixtures prove the Kotlin agrees with the Python. They
 * cannot prove either is *right* -- a wrong deduction shared by both would
 * pass every fixture. This checks the closure against an oracle derived from
 * nothing but the puzzle's own rules: enumerate every complete assignment
 * consistent with the player's marks, and require each derived mark to hold in
 * all of them.
 *
 * Soundness only. Completeness is deliberately **not** asserted: the assist
 * rules are weaker than full arc-consistency by design, so there are cells
 * every completion agrees on that assist does not claim. That is a feature --
 * assist is meant to do the bookkeeping, not solve the puzzle.
 *
 * Limited to shapes where the enumeration is small; a soundness bug in the
 * closure is not shape-specific, so the small grids are a fair test.
 */
class BoardSoundnessTest {

    private companion object {
        const val MAX_COMPLETIONS = 60_000L
    }

    private fun permutations(n: Int): List<List<Int>> {
        if (n == 0) return listOf(emptyList())
        val out = mutableListOf<List<Int>>()
        fun recurse(current: MutableList<Int>, remaining: MutableList<Int>) {
            if (remaining.isEmpty()) { out += current.toList(); return }
            for (i in remaining.indices) {
                val v = remaining.removeAt(i)
                current += v
                recurse(current, remaining)
                current.removeAt(current.size - 1)
                remaining.add(i, v)
            }
        }
        recurse(mutableListOf(), (0 until n).toMutableList())
        return out
    }

    /** Every assignment of non-spine categories to positions. */
    private fun completions(puzzle: Puzzle): Sequence<Map<Ref, Int>> {
        val schema = puzzle.schema
        val others = schema.otherCategories()
        val perms = permutations(schema.size)
        return sequence {
            val counters = IntArray(others.size)
            while (true) {
                val assignment = HashMap<Ref, Int>()
                schema.spine.items.forEachIndexed { p, item ->
                    assignment[Ref(schema.spineName, item)] = p
                }
                others.forEachIndexed { ci, cat ->
                    val perm = perms[counters[ci]]
                    cat.items.forEachIndexed { ii, item ->
                        assignment[Ref(cat.name, item)] = perm[ii]
                    }
                }
                yield(assignment)

                var carry = others.size - 1
                while (carry >= 0) {
                    counters[carry]++
                    if (counters[carry] < perms.size) break
                    counters[carry] = 0
                    carry--
                }
                if (carry < 0) break
            }
        }
    }

    private fun countCompletions(puzzle: Puzzle): Long {
        var total = 1L
        var factorial = 1L
        for (i in 2..puzzle.schema.size) factorial *= i
        repeat(puzzle.schema.otherCategories().size) {
            total *= factorial
            if (total > MAX_COMPLETIONS * 10) return Long.MAX_VALUE
        }
        return total
    }

    @TestFactory
    fun `every derived mark is implied by all consistent completions`(): List<DynamicTest> {
        val dir = File("src/test/resources/fixtures")
        val files = dir.listFiles { f -> f.name.endsWith(".board.json") }!!.sortedBy { it.name }
        val tests = mutableListOf<DynamicTest>()

        for (file in files) {
            val root = PuzzleJson.json.parseToJsonElement(file.readText()).jsonObject
            val puzzle = PuzzleJson.fromObject(root.getValue("puzzle").jsonObject)
            if (countCompletions(puzzle) > MAX_COMPLETIONS) continue

            tests += DynamicTest.dynamicTest(
                "${file.name} (${puzzle.categoryCount}x${puzzle.size})"
            ) {
                val random = java.util.Random(puzzle.pid.hashCode().toLong())
                val refs = puzzle.schema.allRefs
                var checked = 0

                // A handful of independent starting states per puzzle.
                repeat(6) {
                    var state = BoardState(assist = true)
                    var placed = 0
                    var guard = 0
                    while (placed < 4 && guard++ < 200) {
                        val a = refs[random.nextInt(refs.size)]
                        val b = refs[random.nextInt(refs.size)]
                        if (a.cat == b.cat) continue
                        val r = Board.setMark(puzzle, state, a, b, random.nextInt(3) > 0)
                        if (r is MarkResult.Ok) { state = r.state; placed++ }
                    }

                    val closure = (Board.closure(puzzle, state) as ClosureResult.Ok).closure

                    // Completions that satisfy everything the player asserted.
                    val consistent = completions(puzzle).filter { assignment ->
                        state.userMarks.all { (cell, value) ->
                            (assignment.getValue(cell.a) == assignment.getValue(cell.b)) == value
                        }
                    }.toList()

                    // A state the closure accepted must have somewhere to live.
                    assertTrue(
                        consistent.isNotEmpty(),
                        "${file.name}: closure accepted a state with no completion at all",
                    )

                    for ((cell, derived) in closure.marks) {
                        val holdsInAll = consistent.all { assignment ->
                            (assignment.getValue(cell.a) == assignment.getValue(cell.b)) == derived
                        }
                        assertTrue(
                            holdsInAll,
                            "${file.name}: derived ${cell.a.key}/${cell.b.key}=$derived, " +
                                "but ${consistent.count { a ->
                                    (a.getValue(cell.a) == a.getValue(cell.b)) != derived
                                }} of ${consistent.size} valid completions disagree",
                        )
                        checked++
                    }
                }
                assertTrue(checked > 0, "${file.name}: nothing was checked")
            }
        }

        assertTrue(tests.isNotEmpty(), "no puzzle small enough to brute force")
        return tests
    }

    /** The real solution must itself be a consistent completion. Sanity on the oracle. */
    @TestFactory
    fun `the oracle agrees the shipped solution is valid`(): List<DynamicTest> {
        val dir = File("src/test/resources/fixtures")
        return dir.listFiles { f -> f.name.endsWith(".board.json") }!!
            .sortedBy { it.name }
            .mapNotNull { file ->
                val root = PuzzleJson.json.parseToJsonElement(file.readText()).jsonObject
                val puzzle = PuzzleJson.fromObject(root.getValue("puzzle").jsonObject)
                if (countCompletions(puzzle) > MAX_COMPLETIONS) return@mapNotNull null
                DynamicTest.dynamicTest(file.name) {
                    val truth = puzzle.schema.allRefs.associateWith {
                        puzzle.solution.position(it)
                    }
                    assertEquals(
                        1,
                        completions(puzzle).count { it == truth },
                        "${file.name}: the shipped solution is not among the completions",
                    )
                }
            }
    }
}
