package dev.logicgrid.app

import dev.logicgrid.core.ManifestJson
import dev.logicgrid.core.Narrative
import dev.logicgrid.core.NarrativeJson
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Narrated clues: the pairing, the default, and the toggle.
 *
 * The bundled set is used rather than a fixture, because the thing most worth
 * proving is that the files the app actually ships pair up -- a narrative is
 * two files held together by nothing but a pid, and the app had no code to
 * read the second one at all until now.
 */
class NarratedTextTest {

    private val dir = File("src/main/assets/content")

    private fun entries() = ManifestJson.parse(File(dir, "manifest.json").readText())

    private fun load(id: String): Pair<Puzzle, Narrative?> {
        val entry = entries().first { it.id == id }
        val puzzle = PuzzleJson.parse(File(dir, entry.path).readText())
        val narrative = entry.narrativePath
            ?.let { NarrativeJson.forPuzzle(File(dir, it).readText(), puzzle) }
        return puzzle to narrative
    }

    private fun firstNarrated() = entries().first { it.narrated }.id

    @Test
    fun `the bundled set carries narratives and they pair with their puzzles`() {
        val narrated = entries().filter { it.narrated }
        assertTrue(narrated.isNotEmpty(), "the bundled set has no narratives to show")
        for (entry in narrated) {
            val puzzle = PuzzleJson.parse(File(dir, entry.path).readText())
            val text = File(dir, entry.narrativePath!!).readText()
            val narrative = NarrativeJson.forPuzzle(text, puzzle)
            assertNotNull(narrative, "${entry.id}: narrative does not pair with its puzzle")
            // Every canonical fact has to be somewhere in the narrated clues,
            // so the count may differ -- the narrator may group them -- but an
            // empty or truncated list is a broken download, not a style.
            assertTrue(
                narrative!!.clues.isNotEmpty(),
                "${entry.id}: narrative has no clues",
            )
            assertTrue(
                narrative.preamble.isNotBlank(),
                "${entry.id}: no cast block, so the clues name people nobody introduced",
            )
        }
    }

    /**
     * No bundled clue may claim two subjects where the grid allows one.
     *
     * "Araminta presses in April, as does the maker accused of ropiness" --
     * who is Araminta. Each item belongs to exactly one subject, so two facts
     * that pin the same item are one subject named twice, and "as does" tells
     * the solver the opposite. A solver who believes it reaches a
     * contradiction and blames the puzzle.
     *
     * The generator refuses to write these and its publisher refuses to ship
     * them, which is where the rule belongs. This is here because the app's
     * bundled set is copied in by hand: the check upstream cannot see a copy
     * taken from a stale content repo, and this is the last thing between
     * that copy and a phone. Three such narratives were bundled before anyone
     * noticed.
     */
    @Test
    fun `no bundled clue claims two subjects where there can be one`() {
        val claims = listOf(
            Regex(""",\s*as (does|do|did|is|are|was|were|has|have)\b""", RegexOption.IGNORE_CASE),
            Regex("""\band so (does|do|did|is|are|was|were|has|have)\b""", RegexOption.IGNORE_CASE),
            Regex("""(^|\.\s+)So (does|do|did|is|are|was|were)\b"""),
            Regex("""\blikewise\b""", RegexOption.IGNORE_CASE),
            Regex(""",\s*too\b""", RegexOption.IGNORE_CASE),
            Regex(""",\s*as well\b""", RegexOption.IGNORE_CASE),
            Regex("""\bthe same (goes for|is true of|applies to|holds for)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bditto\b""", RegexOption.IGNORE_CASE),
        )
        val bad = mutableListOf<String>()
        for (entry in entries().filter { it.narrated }) {
            val puzzle = PuzzleJson.parse(File(dir, entry.path).readText())
            val narrative = NarrativeJson.forPuzzle(
                File(dir, entry.narrativePath!!).readText(), puzzle,
            ) ?: continue
            narrative.clues.forEachIndexed { i, clue ->
                claims.firstOrNull { it.containsMatchIn(clue) }?.let {
                    bad += "${entry.id} clue ${i + 1}: $clue"
                }
            }
        }
        assertTrue(bad.isEmpty(), "clues claiming two subjects for one:\n" + bad.joinToString("\n"))
    }

    @Test
    fun `a narrative for another puzzle is refused`() {
        val (puzzle, narrative) = load(firstNarrated())
        assertNotNull(narrative)
        // The same file, one character of the pid changed: this is what a
        // stale narrative left behind by a reseed looks like, and pairing the
        // two files by name would show it against the wrong puzzle.
        val entry = entries().first { it.id == firstNarrated() }
        val text = File(dir, entry.narrativePath!!).readText()
        val wrong = text.replace("\"${puzzle.pid}\"", "\"000000000000\"")
        assertTrue(wrong != text, "the pid was not where the test thought it was")
        assertNull(
            NarrativeJson.forPuzzle(wrong, puzzle),
            "a narrative whose pid does not match the puzzle was accepted",
        )
    }

    @Test
    fun `a narrated puzzle opens narrated, and respects the preference`() {
        val (puzzle, narrative) = load(firstNarrated())
        val on = InMemoryProgressStore().apply { narratedText = true }
        assertTrue(SolveViewModel(puzzle, on, narrative = narrative).narrated)

        val off = InMemoryProgressStore().apply { narratedText = false }
        assertFalse(SolveViewModel(puzzle, off, narrative = narrative).narrated)
    }

    @Test
    fun `a puzzle with no narrative is never narrated, and the toggle does nothing`() {
        val plain = entries().first { !it.narrated }
        val puzzle = PuzzleJson.parse(File(dir, plain.path).readText())
        val store = InMemoryProgressStore().apply { narratedText = true }
        val model = SolveViewModel(puzzle, store, narrative = null)
        assertFalse(model.narrated, "narrated with nothing to narrate")
        model.toggleNarrated()
        assertFalse(model.narrated, "the toggle turned on text that does not exist")
        // And it must not have written the preference on the way through: the
        // menu item is absent for this puzzle, so nothing the player did here
        // should change what the next puzzle opens as.
        assertTrue(store.narratedText, "a no-op toggle wrote the preference")
    }

    @Test
    fun `toggling mid-puzzle sticks for the next puzzle`() {
        val (puzzle, narrative) = load(firstNarrated())
        val store = InMemoryProgressStore().apply { narratedText = true }
        val model = SolveViewModel(puzzle, store, narrative = narrative)
        model.toggleNarrated()
        assertFalse(model.narrated)
        assertFalse(store.narratedText, "the choice did not reach the preference")
        // A second puzzle opened afterwards honours it.
        assertFalse(SolveViewModel(puzzle, store, narrative = narrative).narrated)
    }

    @Test
    fun `switching the text cannot touch a single mark`() {
        val (puzzle, narrative) = load(firstNarrated())
        val store = InMemoryProgressStore().apply { narratedText = true }
        val model = SolveViewModel(puzzle, store, narrative = narrative)
        val before = model.state
        val closure = model.closure
        model.toggleNarrated()
        assertEquals(before, model.state, "the board changed when the prose did")
        assertEquals(closure, model.closure, "the closure changed when the prose did")
    }

    @Test
    fun `a malformed narrative is absent rather than fatal`() {
        val (puzzle, _) = load(firstNarrated())
        assertNull(NarrativeJson.forPuzzle("{not json at all", puzzle))
        assertNull(NarrativeJson.forPuzzle("{}", puzzle))
        // Right puzzle, no clues: nothing to play from, so not usable.
        assertNull(NarrativeJson.forPuzzle("""{"pid":"${puzzle.pid}","clues":[]}""", puzzle))
    }
}
