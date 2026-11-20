package dev.logicgrid.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * The sync, against two real content sets in two real directories.
 *
 * Every case here is one that actually happened to this project's content repo
 * during development, which is why they are the cases worth testing:
 *
 *  - a blurb fix rewrote 16 puzzle files and changed **no** pid, because a
 *    blurb is prose and `pid` hashes the schema, clues and solution;
 *  - a reseed rewrote all 50 and changed **every** pid.
 *
 * The first must be silent and must keep every grid. The second must say what
 * it is about to cost before it costs it.
 */
class ContentSyncTest {

    // -- a content set, built the way the generator builds one ---------------

    /** One puzzle's worth of the manifest, plus the bytes it describes. */
    private class Fake(
        val pid: String,
        val id: String,
        /** Stands in for a whole puzzle file; only its hash matters here. */
        val body: String,
        val narrative: String? = null,
    )

    private class Content(val generated: String, val puzzles: List<Fake>) {
        val files: Map<String, ByteArray> = buildMap {
            for (p in puzzles) {
                put("puzzles/${p.id}.json", p.body.encodeToByteArray())
                p.narrative?.let { put("narratives/${p.id}.json", it.encodeToByteArray()) }
            }
        }

        val manifest: String = buildString {
            append("""{"schema":1,"generated":"$generated",""")
            append(""""generator":"logicgrid 0.1.0","count":${puzzles.size},"puzzles":[""")
            puzzles.forEachIndexed { i, p ->
                if (i > 0) append(',')
                val body = p.body.encodeToByteArray()
                append("""{"pid":"${p.pid}","id":"${p.id}","title":"T ${p.id}",""")
                append(""""theme":"${p.id}","profile":"easy","rating":3,""")
                append(""""categories":3,"items":4,"clues":5,""")
                append(""""puzzle":{"path":"puzzles/${p.id}.json",""")
                append(""""sha256":"${sha256(body)}","bytes":${body.size}}""")
                p.narrative?.let {
                    val n = it.encodeToByteArray()
                    append(""","narrative":{"path":"narratives/${p.id}.json",""")
                    append(""""sha256":"${sha256(n)}","bytes":${n.size}}""")
                }
                append('}')
            }
            append("]}")
        }

        /** Lay this set down as a directory, the way a cache holds one. */
        fun writeTo(dir: File) {
            dir.mkdirs()
            File(dir, "manifest.json").writeText(manifest)
            for ((path, bytes) in files) {
                File(dir, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
        }

        fun source(): ContentSource = object : ContentSource {
            override fun manifestText() = manifest
            override fun bytes(path: String) = files[path]
        }
    }

    /** Serves a set, counts what was asked for, and can be told to break. */
    private class FakeFetcher(
        private val set: Content,
        /** Paths to fail on, to stand in for a dropped connection. */
        private val broken: Set<String> = emptySet(),
        /** Paths to serve short, to stand in for a truncated download. */
        private val truncate: Set<String> = emptySet(),
        /**
         * Paths to serve as the wrong content *at the right length*.
         *
         * Separate from [truncate] on purpose. A short read is caught by the
         * byte count alone, so a test that only truncates passes even with the
         * hash check deleted -- which is how a useless test gets written. Only
         * the digest can catch this one.
         */
        private val corrupt: Set<String> = emptySet(),
    ) : ContentFetcher {
        val asked = mutableListOf<String>()

        override fun fetch(path: String): ByteArray {
            asked += path
            if (path in broken) error("connection reset")
            if (path == "manifest.json") return set.manifest.encodeToByteArray()
            val bytes = set.files[path] ?: error("404 $path")
            return when (path) {
                in truncate -> bytes.copyOf(bytes.size / 2)
                in corrupt -> ByteArray(bytes.size) { i -> bytes[i].inc() }
                else -> bytes
            }
        }
    }

    private fun content(generated: String, bodies: List<Pair<String, String>>) =
        Content(generated, bodies.mapIndexed { i, (pid, body) ->
            Fake(pid, "%02d-theme".format(i + 1), body)
        })

    /** Five puzzles, as shipped in the APK. */
    private val shipped = content(
        "2026-11-19T23:08:21+00:00",
        listOf(
            "aaa111" to "clues A",
            "bbb222" to "clues B",
            "ccc333" to "clues C",
            "ddd444" to "clues D",
            "eee555" to "clues E",
        ),
    )

    /** The blurb fix: the same five puzzles, three files rewritten, no pid moved. */
    private val reworded = content(
        "2026-11-20T09:00:00+00:00",
        listOf(
            "aaa111" to "clues A, better blurb",
            "bbb222" to "clues B, better blurb",
            "ccc333" to "clues C, better blurb",
            "ddd444" to "clues D",
            "eee555" to "clues E",
        ),
    )

    /** The reseed: five different puzzles, so five different pids. */
    private val reseeded = content(
        "2026-11-20T10:00:00+00:00",
        listOf(
            "zzz999" to "clues Z",
            "yyy888" to "clues Y",
            "xxx777" to "clues X",
            "www666" to "clues W",
            "vvv555" to "clues V",
        ),
    )

    private fun sync(root: File, remote: Content, fetcher: ContentFetcher = FakeFetcher(remote)) =
        ContentSync(root, shipped.source(), fetcher)

    // -- the two cases the player can tell apart -----------------------------

    @Test
    fun `a reworded set changes files but costs no progress`(@TempDir root: File) {
        val sync = sync(root, reworded)
        // Progress on two of the five, one of them solved.
        val (remote, plan) = sync.check(startedPids = setOf("aaa111", "ddd444"), solvedPids = setOf("ddd444"))!!

        assertFalse(plan.warns, "no pid is leaving, so nothing is lost: ${plan.losesStarted}")
        assertEquals(
            listOf("puzzles/01-theme.json", "puzzles/02-theme.json", "puzzles/03-theme.json"),
            plan.fetch.map { it.path },
            "only the three rewritten files need fetching",
        )

        val result = runBlocking { sync.apply(remote, plan) } as SyncResult.Applied
        assertEquals(3, result.fetched, "the two unchanged puzzles are copied, not downloaded")
        assertEquals(5, result.puzzles)
        assertTrue(result.departed.isEmpty())
    }

    @Test
    fun `a reseeded set names the progress it will cost, before downloading it`(@TempDir root: File) {
        val fetcher = FakeFetcher(reseeded)
        val sync = ContentSync(root, shipped.source(), fetcher)
        val (_, plan) = sync.check(
            startedPids = setOf("aaa111", "ccc333", "eee555"),
            solvedPids = setOf("ccc333"),
        )!!

        assertTrue(plan.warns)
        assertEquals(listOf("aaa111", "ccc333", "eee555"), plan.losesStarted)
        assertEquals(listOf("ccc333"), plan.losesSolved, "the solved one is called out separately")
        // The point of planning from the manifest: the warning is ready before
        // a single puzzle has been downloaded.
        assertEquals(listOf("manifest.json"), fetcher.asked)
    }

    @Test
    fun `progress survives a puzzle that is regenerated identically`(@TempDir root: File) {
        // ddd444 is byte for byte the same in both sets.
        val (_, plan) = sync(root, reworded).check(setOf("ddd444"), setOf("ddd444"))!!
        assertTrue(plan.losesStarted.isEmpty())
        assertTrue(plan.fetch.none { it.path == "puzzles/04-theme.json" })
    }

    // -- never leave the player with less than they had -----------------------

    @Test
    fun `content that is the right length but the wrong bytes is refused`(@TempDir root: File) {
        shipped.writeTo(File(root, "current"))
        val fetcher = FakeFetcher(reseeded, corrupt = setOf("puzzles/02-theme.json"))
        val sync = ContentSync(root, shipped.source(), fetcher)
        val (remote, plan) = sync.check(emptySet(), emptySet())!!

        val result = runBlocking { sync.apply(remote, plan) }
        assertTrue(result is SyncResult.Failed, "a hash mismatch must not be applied: $result")
        assertTrue((result as SyncResult.Failed).reason.contains("02-theme"))

        // The old set is exactly as it was, and the staging directory is gone.
        assertEquals(shipped.manifest, sync.localManifest()!!.text)
        assertEquals("clues B", sync.source().bytes("puzzles/02-theme.json")!!.decodeToString())
        assertFalse(File(root, "next").exists(), "a failed sync leaves no rubble")
    }

    @Test
    fun `a truncated download is refused`(@TempDir root: File) {
        shipped.writeTo(File(root, "current"))
        val sync = ContentSync(
            root,
            shipped.source(),
            FakeFetcher(reseeded, truncate = setOf("puzzles/03-theme.json")),
        )
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        val result = runBlocking { sync.apply(remote, plan) }
        assertTrue(result is SyncResult.Failed, "half a puzzle is not a puzzle: $result")
        assertEquals(shipped.pids, sync.localManifest()!!.pids)
    }

    @Test
    fun `a connection lost mid-set leaves the old set in place`(@TempDir root: File) {
        shipped.writeTo(File(root, "current"))
        val sync = ContentSync(
            root,
            shipped.source(),
            FakeFetcher(reseeded, broken = setOf("puzzles/04-theme.json")),
        )
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        assertTrue(runBlocking { sync.apply(remote, plan) } is SyncResult.Failed)
        assertEquals(shipped.pids, sync.localManifest()!!.pids)
    }

    @Test
    fun `the manifest is never left describing content that is not there`(@TempDir root: File) {
        // The failure this guards against is silent: write new files into the
        // live directory, fail before the manifest, and every entry still
        // checksums -- against the file that used to be at that path.
        shipped.writeTo(File(root, "current"))
        val sync = ContentSync(
            root,
            shipped.source(),
            FakeFetcher(reseeded, broken = setOf("puzzles/05-theme.json")),
        )
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        runBlocking { sync.apply(remote, plan) }

        val local = sync.localManifest()!!
        for (file in local.files) {
            val bytes = sync.source().bytes(file.path)
            assertTrue(bytes != null, "${file.path} is described but missing")
            assertEquals(file.sha256, sha256(bytes!!), "${file.path} is not what the manifest says")
        }
    }

    @Test
    fun `an unreadable manifest is ignored rather than acted on`(@TempDir root: File) {
        val sync = ContentSync(root, shipped.source(), object : ContentFetcher {
            override fun fetch(path: String) = "<!doctype html>not json".encodeToByteArray()
        })
        assertNull(sync.check(emptySet(), emptySet()), "a captive portal is not a content update")
    }

    @Test
    fun `a manifest from a newer schema is refused`(@TempDir root: File) {
        val sync = ContentSync(root, shipped.source(), object : ContentFetcher {
            override fun fetch(path: String) =
                shipped.manifest.replace("\"schema\":1", "\"schema\":2").encodeToByteArray()
        })
        assertNull(sync.check(emptySet(), emptySet()))
    }

    // -- which set is served -------------------------------------------------

    @Test
    fun `with no cache the bundled set is served`(@TempDir root: File) {
        val sync = sync(root, shipped)
        assertEquals(shipped.pids, sync.localManifest()!!.pids)
    }

    @Test
    fun `an applied set is served afterwards`(@TempDir root: File) {
        val sync = sync(root, reseeded)
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        runBlocking { sync.apply(remote, plan) }
        assertEquals(reseeded.pids, sync.localManifest()!!.pids)
        assertEquals("clues Z", sync.source().bytes("puzzles/01-theme.json")!!.decodeToString())
    }

    @Test
    fun `a cache older than the installed APK loses to it`(@TempDir root: File) {
        // An app update can ship newer content than the cache holds. Without
        // this the stale cache would shadow it for the life of the install.
        val old = content("2026-01-01T00:00:00+00:00", listOf("old111" to "clues old"))
        old.writeTo(File(root, "current"))
        val sync = sync(root, shipped)
        assertEquals(shipped.pids, sync.localManifest()!!.pids, "assets are newer, so assets win")
    }

    @Test
    fun `nothing to do is reported as nothing done`(@TempDir root: File) {
        shipped.writeTo(File(root, "current"))
        val fetcher = FakeFetcher(shipped)
        val sync = ContentSync(root, shipped.source(), fetcher)
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        assertTrue(plan.idle)
        assertEquals(SyncResult.UpToDate, runBlocking { sync.apply(remote, plan) })
        assertEquals(listOf("manifest.json"), fetcher.asked, "an unchanged set costs one request")
    }

    @Test
    fun `narratives are fetched and verified like puzzles`(@TempDir root: File) {
        val withNarrative = Content(
            "2026-11-21T00:00:00+00:00",
            listOf(Fake("aaa111", "01-theme", "clues A", narrative = "Once upon a time")),
        )
        val sync = ContentSync(root, shipped.source(), FakeFetcher(withNarrative))
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        assertTrue(plan.fetch.any { it.path == "narratives/01-theme.json" })
        runBlocking { sync.apply(remote, plan) }
        assertEquals(
            "Once upon a time",
            sync.source().bytes("narratives/01-theme.json")!!.decodeToString(),
        )
    }

    private val Content.pids: Set<String> get() = puzzles.map { it.pid }.toSet()

    // -- how the downloading runs --------------------------------------------

    /** Thirty files, enough that running them one at a time is visible. */
    private val wholeNewSet = content(
        "2026-12-01T00:00:00+00:00",
        (1..30).map { "pid%02d".format(it) to "clues $it" },
    )

    /** Serves [wholeNewSet] slowly, recording how many calls overlap. */
    private class SlowFetcher(
        private val set: Content,
        private val breakOn: String? = null,
        private val delayMs: Long = 20,
    ) : ContentFetcher {
        val asked = AtomicInteger()
        val peakInFlight = AtomicInteger()
        private val inFlight = AtomicInteger()

        override fun fetch(path: String): ByteArray {
            if (path == "manifest.json") return set.manifest.encodeToByteArray()
            asked.incrementAndGet()
            if (path == breakOn) error("connection reset")
            val now = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { max(it, now) }
            try {
                // Stands in for the round trip, which is nearly all of the
                // real cost: 50 files took 6.2s sequentially against GitHub.
                Thread.sleep(delayMs)
                return set.files[path] ?: error("404 $path")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `files are fetched a few at a time, and never more than the limit`(
        @TempDir root: File,
    ) = runBlocking(Dispatchers.IO) {
        val fetcher = SlowFetcher(wholeNewSet)
        val sync = ContentSync(root, shipped.source(), fetcher)
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        assertTrue(sync.apply(remote, plan) is SyncResult.Applied)

        assertTrue(
            fetcher.peakInFlight.get() > 1,
            "nothing overlapped, so this is still sequential",
        )
        assertTrue(
            fetcher.peakInFlight.get() <= ContentSync.PARALLEL,
            "${fetcher.peakInFlight.get()} at once, limit is ${ContentSync.PARALLEL}",
        )
    }

    @Test
    fun `progress reports every file exactly once, and ends at the total`(
        @TempDir root: File,
    ) = runBlocking(Dispatchers.IO) {
        val sync = ContentSync(root, shipped.source(), SlowFetcher(wholeNewSet, delayMs = 0))
        val (remote, plan) = sync.check(emptySet(), emptySet())!!
        val counts = Collections.synchronizedList(mutableListOf<Int>())
        val totals = Collections.synchronizedSet(mutableSetOf<Int>())
        sync.apply(remote, plan) { done, total ->
            counts += done
            totals += total
        }

        val expected = remote.files.size
        assertEquals(setOf(expected), totals, "the total must not move while it runs")
        assertEquals(
            (1..expected).toList(),
            counts.sorted(),
            "each file counted once, and the last one reaches the total",
        )
    }

    @Test
    fun `a failure stops the downloads that have not started`(@TempDir root: File) =
        runBlocking(Dispatchers.IO) {
            // The one that breaks is in the first batch, so nothing after it
            // should ever be asked for. Downloading all thirty and only then
            // reporting the failure would waste a phone's data on a set that
            // is going to be thrown away.
            val fetcher = SlowFetcher(wholeNewSet, breakOn = "puzzles/03-theme.json")
            val sync = ContentSync(root, shipped.source(), fetcher)
            val (remote, plan) = sync.check(emptySet(), emptySet())!!
            assertTrue(sync.apply(remote, plan) is SyncResult.Failed)
            // A batch that is already in flight finishes -- a blocking read
            // cannot be interrupted -- so the bound is the batch size and a
            // little slack, not the exact count.
            assertTrue(
                fetcher.asked.get() <= ContentSync.PARALLEL + 2,
                "asked for ${fetcher.asked.get()} of ${remote.files.size} before stopping",
            )
        }

    @Test
    fun `stale progress from an older set does not warn about nothing`(@TempDir root: File) {
        // This one came from running the sync against the live repo: progress
        // can be stored under a pid that no manifest mentions any more, and an
        // unchanged set must not keep announcing that it will cost something.
        shipped.writeTo(File(root, "current"))
        val sync = ContentSync(root, shipped.source(), FakeFetcher(shipped))
        val (_, plan) = sync.check(startedPids = setOf("longgone"), solvedPids = emptySet())!!
        assertTrue(plan.idle)
        assertFalse(plan.warns, "nothing is happening, so there is nothing to warn about")
    }

    @Test
    fun `an undated manifest never discards a cache`() {
        assertTrue(ContentSync.olderThan("2026-01-01T00:00:00+00:00", "2026-11-19T23:08:21+00:00"))
        assertFalse(ContentSync.olderThan("2026-11-19T23:08:21+00:00", "2026-01-01T00:00:00+00:00"))
        assertFalse(ContentSync.olderThan("", "2026-11-19T23:08:21+00:00"))
        assertFalse(ContentSync.olderThan("2026-11-19T23:08:21Z", "2026-11-20T00:00:00+00:00"))
    }
}
