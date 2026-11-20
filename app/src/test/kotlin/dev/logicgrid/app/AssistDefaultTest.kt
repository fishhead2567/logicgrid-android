package dev.logicgrid.app

import dev.logicgrid.core.ManifestJson
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import dev.logicgrid.core.PuzzleProgress
import dev.logicgrid.core.cellOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The assist *default* is not the assist *setting*.
 *
 * Assist decides which marks the board holds, so it is board state and is
 * saved with the board. The settings switch only decides what a board that
 * does not exist yet starts out as -- and the distinction matters, because the
 * alternative is a global switch that reaches back into saved puzzles and
 * silently fills in deductions the player had deliberately left alone.
 */
class AssistDefaultTest {

    private fun bundled(): Puzzle {
        val dir = File("src/main/assets/content")
        val entry = ManifestJson.parse(File(dir, "manifest.json").readText()).first()
        return PuzzleJson.parse(File(dir, entry.path).readText())
    }

    @Test
    fun `a puzzle never opened takes the default`() {
        val puzzle = bundled()
        val store = InMemoryProgressStore().apply { assistByDefault = true }
        assertTrue(SolveViewModel(puzzle, store).assist)

        val off = InMemoryProgressStore().apply { assistByDefault = false }
        assertFalse(SolveViewModel(puzzle, off).assist)
    }

    @Test
    fun `a board saved with assist off keeps it off when the default changes`() {
        val puzzle = bundled()
        // Someone played a little with assist off.
        val (a, b) = puzzle.schema.pairs.first()
        val mark = cellOf(
            puzzle.schema.category(a).refs().first(),
            puzzle.schema.category(b).refs().first(),
        )
        val store = InMemoryProgressStore(
            mapOf(puzzle.pid to PuzzleProgress(userMarks = mapOf(mark to false), assist = false))
        )
        // ...and then turned the default on for new puzzles.
        store.assistByDefault = true

        assertFalse(
            SolveViewModel(puzzle, store).assist,
            "their board was saved with assist off, and reopening it must not " +
                "start doing their deductions for them",
        )
    }

    @Test
    fun `a board saved with assist on keeps it on when the default is off`() {
        val puzzle = bundled()
        val (a, b) = puzzle.schema.pairs.first()
        val mark = cellOf(
            puzzle.schema.category(a).refs().first(),
            puzzle.schema.category(b).refs().first(),
        )
        val store = InMemoryProgressStore(
            mapOf(puzzle.pid to PuzzleProgress(userMarks = mapOf(mark to false), assist = true))
        )
        store.assistByDefault = false
        assertTrue(SolveViewModel(puzzle, store).assist)
    }
}
