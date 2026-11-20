package dev.logicgrid.app

import dev.logicgrid.core.ManifestJson
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import dev.logicgrid.core.Ref
import dev.logicgrid.core.cellOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * "Check my work" against a real bundled puzzle.
 *
 * The contract is narrow on purpose: it says whether the board can still be
 * right, and nothing else. A player who is told *how many* marks are wrong
 * knows a single wrong one must be recent; a player who is told *which* has
 * been given that part of the answer. So the only thing tested here beyond
 * clean-vs-wrong is that the verdict never outlives the board it described.
 */
class CheckMyWorkTest {

    /** The first puzzle in the APK's own content set. */
    private fun bundled(): Puzzle {
        val dir = File("src/main/assets/content")
        val entry = ManifestJson.parse(File(dir, "manifest.json").readText()).first()
        return PuzzleJson.parse(File(dir, entry.path).readText())
    }

    private fun model(puzzle: Puzzle) = SolveViewModel(puzzle, InMemoryProgressStore())

    /** A pair of refs in different categories that the solution agrees on. */
    private fun trueCell(puzzle: Puzzle): Pair<Ref, Ref> {
        val (a, b) = puzzle.schema.pairs.first()
        for (x in puzzle.schema.category(a).refs()) {
            for (y in puzzle.schema.category(b).refs()) {
                if (puzzle.solution.sameEntity(x, y)) return x to y
            }
        }
        error("no true cell in ${puzzle.pid}")
    }

    private fun falseCell(puzzle: Puzzle): Pair<Ref, Ref> {
        val (a, b) = puzzle.schema.pairs.first()
        for (x in puzzle.schema.category(a).refs()) {
            for (y in puzzle.schema.category(b).refs()) {
                if (!puzzle.solution.sameEntity(x, y)) return x to y
            }
        }
        error("no false cell in ${puzzle.pid}")
    }

    /** Marks through the UI's own path: select the cell, then press O or X. */
    private fun SolveViewModel.mark(cell: Pair<Ref, Ref>, value: Boolean) {
        val (a, b) = cell
        val where = geometry.displayOf(cellOf(a, b))
            ?: error("$a x $b is not on the chart")
        select(where)
        markSelected(value)
    }

    @Test
    fun `an untouched board has no verdict until it is asked for`() {
        val model = model(bundled())
        assertNull(model.checked, "nothing is claimed before the player asks")
        model.check()
        assertEquals(SolveViewModel.Checked.Clean, model.checked)
    }

    @Test
    fun `a right mark checks clean`() {
        val puzzle = bundled()
        val model = model(puzzle)
        model.mark(trueCell(puzzle), true)
        model.check()
        assertEquals(SolveViewModel.Checked.Clean, model.checked)
    }

    @Test
    fun `a wrong mark is caught`() {
        val puzzle = bundled()
        val model = model(puzzle)
        // Circling a pair the solution separates. Its forced eliminations are
        // wrong too, which is why checking the closure rather than the
        // player's own marks catches this at the first opportunity.
        model.mark(falseCell(puzzle), true)
        model.check()
        assertEquals(SolveViewModel.Checked.Wrong, model.checked)
    }

    @Test
    fun `an X where the answer says X is not a mistake`() {
        val puzzle = bundled()
        val model = model(puzzle)
        model.mark(falseCell(puzzle), false)
        model.check()
        assertEquals(SolveViewModel.Checked.Clean, model.checked)
    }

    @Test
    fun `the verdict does not outlive the board it described`() {
        val puzzle = bundled()
        val model = model(puzzle)
        model.check()
        assertEquals(SolveViewModel.Checked.Clean, model.checked)

        model.mark(trueCell(puzzle), true)
        assertNull(model.checked, "a new mark invalidates it -- a stale 'clean' is a lie")

        model.check()
        model.undo()
        assertNull(model.checked, "and so does an undo")
    }

    @Test
    fun `a completed board checks clean`() {
        val puzzle = bundled()
        val model = model(puzzle)
        for ((a, b) in puzzle.schema.pairs) {
            for (x in puzzle.schema.category(a).refs()) {
                for (y in puzzle.schema.category(b).refs()) {
                    model.mark(x to y, puzzle.solution.sameEntity(x, y))
                }
            }
        }
        assertEquals(SolveViewModel.Outcome.Solved, model.outcome)
        model.check()
        assertEquals(SolveViewModel.Checked.Clean, model.checked)
    }
}
