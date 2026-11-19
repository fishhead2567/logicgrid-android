package dev.logicgrid.core

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The answer sheet must follow from the marks, not from where the ink is.
 *
 * On a three-category grid the chart has three blocks, and any two of them
 * determine the third.  A player who circles builder-score and builder-biome
 * has solved the puzzle; the biome-score block is then redundant, and filling
 * it in is bookkeeping a solver reasonably skips.
 *
 * [Board.answer] used to read each category only against the spine, so that
 * player's answer sheet came back all nulls and the board withheld `solved`
 * until they filled the third block -- unless assist happened to be on, which
 * made correctness of the readout depend on a preference that is supposed only
 * to change who does the pencil work.
 */
class AnswerReadoffTest {

    private fun threeCategoryPuzzle(): Puzzle {
        val file = File("src/test/resources/fixtures").listFiles { f ->
            f.name.endsWith(".board.json")
        }!!.sortedBy { it.name }.first { f ->
            val root = PuzzleJson.json.parseToJsonElement(f.readText()).jsonObject
            PuzzleJson.fromObject(root.getValue("puzzle").jsonObject)
                .schema.categories.size == 3
        }
        val root = PuzzleJson.json.parseToJsonElement(file.readText()).jsonObject
        return PuzzleJson.fromObject(root.getValue("puzzle").jsonObject)
    }

    /** Circle the true cells of two blocks only, leaving the third blank. */
    private fun markTwoBlocks(puzzle: Puzzle, assist: Boolean): Closure {
        val schema = puzzle.schema
        val hub = schema.otherCategories()[0]
        val leaf = schema.otherCategories()[1]
        var state = BoardState(assist = assist)
        for (ref in hub.refs()) {
            for (other in schema.spine.refs() + leaf.refs()) {
                if (!puzzle.solution.sameEntity(ref, other)) continue
                state = (Board.setMark(puzzle, state, ref, other, true)
                    as MarkResult.Ok).state
            }
        }
        // The point of the test: nothing was ever marked in spine x leaf.
        val untouched: List<Cell> = schema.spine.refs().flatMap { s ->
            leaf.refs().map { l -> cellOf(s, l) }
        }
        val closure = (Board.closure(puzzle, state) as ClosureResult.Ok).closure
        if (!assist) {
            untouched.forEach { assertNull(closure.marks[it], "expected $it unmarked") }
        }
        return closure
    }

    @Test
    fun `two blocks determine the answer with assist off`() {
        val puzzle = threeCategoryPuzzle()
        val closure = markTwoBlocks(puzzle, assist = false)
        val answer = Board.answer(puzzle, closure)

        assertTrue(
            Board.answerComplete(answer),
            "two of three blocks determine the grid, so the answer sheet must " +
                "be complete without the third: $answer",
        )
        assertTrue(Board.mistakes(puzzle, closure).isEmpty(), "no mark was wrong")
        assertTrue(Board.solved(puzzle, closure), "a determined grid is solved")
    }

    @Test
    fun `the readout does not depend on the assist setting`() {
        val puzzle = threeCategoryPuzzle()
        val off = Board.answer(puzzle, markTwoBlocks(puzzle, assist = false))
        val on = Board.answer(puzzle, markTwoBlocks(puzzle, assist = true))
        assertEquals(on, off, "assist must change the pencil work, not the answer")
    }

    @Test
    fun `the answer agrees with the recorded solution`() {
        val puzzle = threeCategoryPuzzle()
        val answer = Board.answer(puzzle, markTwoBlocks(puzzle, assist = false))
        val schema = puzzle.schema
        for (cat in schema.otherCategories()) {
            schema.spine.items.forEachIndexed { i, spineItem ->
                val expected = cat.refs().single { ref ->
                    puzzle.solution.sameEntity(ref, Ref(schema.spineName, spineItem))
                }.item
                assertEquals(
                    expected,
                    answer.getValue(cat.name)[i],
                    "${cat.name} on the $spineItem row",
                )
            }
        }
    }

    @Test
    fun `one block alone does not determine the answer`() {
        // The guard against over-correcting: chaining circles must not invent
        // an answer where the player genuinely has not pinned one.
        val puzzle = threeCategoryPuzzle()
        val schema = puzzle.schema
        val hub = schema.otherCategories()[0]
        var state = BoardState(assist = false)
        for (ref in hub.refs()) {
            for (other in schema.spine.refs()) {
                if (!puzzle.solution.sameEntity(ref, other)) continue
                state = (Board.setMark(puzzle, state, ref, other, true)
                    as MarkResult.Ok).state
            }
        }
        val closure = (Board.closure(puzzle, state) as ClosureResult.Ok).closure
        val answer = Board.answer(puzzle, closure)
        assertEquals(
            schema.spine.items.size,
            answer.getValue(hub.name).count { it != null },
            "the block that was filled reads off",
        )
        assertFalse(
            Board.answerComplete(answer),
            "the untouched category is not determined by one block",
        )
    }
}
