package dev.logicgrid.app

import dev.logicgrid.core.ContentFetcher
import dev.logicgrid.core.ContentSource
import dev.logicgrid.core.ContentSync
import dev.logicgrid.core.PuzzleProgress
import dev.logicgrid.core.sha256
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The decisions the player sees, which all live here rather than in the sync
 * engine: when to say nothing, when to say something, and what survives an
 * update that cannot keep everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentModelTest {

    /** A content set in memory: both somewhere to read from and somewhere to fetch from. */
    private class Served(val manifest: String, val bodies: Map<String, String>) :
        ContentSource, ContentFetcher {
        var asked = 0
            private set

        override fun manifestText() = manifest
        override fun bytes(path: String) = bodies[path]?.encodeToByteArray()
        override fun fetch(path: String): ByteArray {
            asked++
            return if (path == "manifest.json") manifest.encodeToByteArray()
            else bodies[path]?.encodeToByteArray() ?: error("404 $path")
        }
    }

    private fun served(generated: String, puzzles: List<Pair<String, String>>): Served {
        val bodies = puzzles.mapIndexed { i, (_, body) -> "puzzles/0${i + 1}-t.json" to body }
        val manifest = buildString {
            append("""{"schema":1,"generated":"$generated",""")
            append(""""generator":"logicgrid 0.1.0","count":${puzzles.size},"puzzles":[""")
            puzzles.forEachIndexed { i, (pid, body) ->
                if (i > 0) append(',')
                val bytes = body.encodeToByteArray()
                append("""{"pid":"$pid","id":"0${i + 1}-t","title":"Puzzle ${i + 1}",""")
                append(""""theme":"t","profile":"easy","rating":3,""")
                append(""""categories":3,"items":4,"clues":5,""")
                append(""""puzzle":{"path":"${bodies[i].first}",""")
                append(""""sha256":"${sha256(bytes)}","bytes":${bytes.size}}}""")
            }
            append("]}")
        }
        return Served(manifest, bodies.toMap())
    }

    private val shipped = served(
        "2026-11-19T00:00:00+00:00",
        listOf("aaa" to "A", "bbb" to "B", "ccc" to "C"),
    )

    /** Prose changed, so the files move; the puzzles did not, so the pids hold. */
    private val reworded = served(
        "2026-11-20T00:00:00+00:00",
        listOf("aaa" to "A, reworded", "bbb" to "B", "ccc" to "C"),
    )

    /** A new seed: three different puzzles under three different pids. */
    private val reseeded = served(
        "2026-11-21T00:00:00+00:00",
        listOf("xxx" to "X", "yyy" to "Y", "zzz" to "Z"),
    )

    private fun TestScope.model(
        root: File,
        remote: ContentFetcher,
        store: ProgressStore,
    ) = ContentModel(
        content = ContentSync(root, shipped, remote),
        store = store,
        scope = this,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun storeWith(vararg progress: Pair<String, PuzzleProgress>) =
        InMemoryProgressStore(progress.toMap())

    // -- the quiet path ------------------------------------------------------

    @Test
    fun `a reworded set updates without a word`(@TempDir root: File) = runTest {
        val store = storeWith("aaa" to PuzzleProgress(solved = true))
        val model = model(root, reworded, store)

        model.refresh()
        advanceUntilIdle()

        assertTrue(model.status is SyncUi.Updated, "an update is reported, not asked about")
        assertEquals(0, (model.status as SyncUi.Updated).lost)
        assertEquals(listOf("aaa", "bbb", "ccc"), model.library.map { it.pid })
        assertTrue(store.read("aaa").solved, "the pid did not move, so the progress stays")
    }

    @Test
    fun `an unreachable repo says nothing at all`(@TempDir root: File) = runTest {
        val broken = object : ContentFetcher {
            override fun fetch(path: String): ByteArray = error("no route to host")
        }
        val model = model(root, broken, InMemoryProgressStore())

        model.refresh()
        advanceUntilIdle()

        assertEquals(SyncUi.Quiet, model.status, "a failed sync is not the player's problem")
        assertEquals(3, model.library.size, "and the library is still all there")
    }

    @Test
    fun `the check happens once per process`(@TempDir root: File) = runTest {
        val model = model(root, reseeded, InMemoryProgressStore())
        model.refresh()
        advanceUntilIdle()
        val after = reseeded.asked
        model.refresh()
        model.refresh()
        advanceUntilIdle()
        assertEquals(after, reseeded.asked, "returning to the menu does not re-check")
    }

    // -- the warned path -----------------------------------------------------

    @Test
    fun `a set that costs progress warns first, and applies when acknowledged`(
        @TempDir root: File,
    ) = runTest {
        val store = storeWith(
            "aaa" to PuzzleProgress(solved = true),
            "bbb" to PuzzleProgress(solved = true),
            "ccc" to PuzzleProgress(solved = false, assist = true),
        )
        val model = model(root, reseeded, store)

        model.refresh()
        advanceUntilIdle()

        val warning = model.status as SyncUi.Warning
        assertEquals(3, warning.puzzles)
        assertEquals(2, warning.started, "ccc was opened but never marked")
        assertEquals(2, warning.solved)
        // Nothing has been replaced yet, and only the manifest was fetched to
        // work all this out.
        assertEquals(listOf("aaa", "bbb", "ccc"), model.library.map { it.pid })
        assertEquals(1, reseeded.asked)

        model.acknowledge()
        advanceUntilIdle()

        assertEquals(listOf("xxx", "yyy", "zzz"), model.library.map { it.pid })
        assertEquals(2, (model.status as SyncUi.Updated).lost)
    }

    @Test
    fun `the solved tally survives the puzzles that earned it`(@TempDir root: File) = runTest {
        val store = storeWith(
            "aaa" to PuzzleProgress(solved = true),
            "bbb" to PuzzleProgress(solved = true),
        )
        assertEquals(2, store.solvedEver().size)

        val model = model(root, reseeded, store)
        model.refresh()
        advanceUntilIdle()
        model.acknowledge()
        advanceUntilIdle()

        // The grids cannot be kept -- their marks name items that no longer
        // exist -- but the fact of having solved them is not a grid.
        assertTrue(store.startedPids().isEmpty(), "no progress remains for a departed puzzle")
        assertTrue(store.solvedPids().isEmpty())
        assertEquals(2, store.solvedEver().size, "the tally is the thing that survives")
    }

    @Test
    fun `nothing is touched while the notice is still up`(@TempDir root: File) = runTest {
        val store = storeWith("aaa" to PuzzleProgress(solved = true))
        val model = model(root, reseeded, store)

        model.refresh()
        advanceUntilIdle()

        assertTrue(model.status is SyncUi.Warning)
        assertTrue(store.read("aaa").solved, "progress is still here")
        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            model.library.map { it.pid },
            "and so is the old library",
        )
    }
}
