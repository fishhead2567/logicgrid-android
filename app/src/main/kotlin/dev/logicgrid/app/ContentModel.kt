package dev.logicgrid.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logicgrid.core.ContentManifest
import dev.logicgrid.core.ContentSource
import dev.logicgrid.core.ContentSync
import dev.logicgrid.core.HttpFetcher
import dev.logicgrid.core.LibraryEntry
import dev.logicgrid.core.Puzzle
import dev.logicgrid.core.PuzzleJson
import dev.logicgrid.core.SyncPlan
import dev.logicgrid.core.SyncResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The set bundled in the APK. Always complete, always present, never stale. */
class AssetSource(private val context: Context) : ContentSource {
    override fun manifestText(): String? = bytes("manifest.json")?.decodeToString()

    override fun bytes(path: String): ByteArray? = try {
        context.assets.open("content/$path").use { it.readBytes() }
    } catch (e: IOException) {
        null
    }
}

/** What the library screen shows about a sync. */
sealed interface SyncUi {
    /** Nothing to say. Also where a failed sync lands: it is not the player's problem. */
    data object Quiet : SyncUi

    data object Checking : SyncUi

    /**
     * An update is ready and it will cost progress.
     *
     * There is no decline, so this is a notice rather than a question -- but it
     * is shown *before* the content is replaced, so nobody loses a half-solved
     * grid to something they never saw.
     */
    data class Warning(val puzzles: Int, val started: Int, val solved: Int) : SyncUi

    /**
     * Downloading, with [done] of [total] files finished.
     *
     * [blocking] separates the two cases, because they are not the same event
     * to the player. An update nobody was warned about is housekeeping and
     * gets a line of text. An update they just tapped through is something
     * they are waiting on, so it holds the screen until it is done -- both to
     * show that it is happening at all, and because the library is about to be
     * replaced underneath them.
     */
    data class Working(val done: Int, val total: Int, val blocking: Boolean) : SyncUi

    data class Updated(val puzzles: Int, val lost: Int) : SyncUi
}

/**
 * The library, and the sync that keeps it current.
 *
 * Reading the manifest is synchronous and on the caller's thread, because the
 * library has to be on screen in the first frame: it is a 22 KiB parse of a
 * file that is either in the APK or in the app's own directory. Only the
 * network and the file writing go to a background thread, and nothing the
 * network does can keep the player waiting.
 *
 * The scope is the model's own rather than the composition's. An apply writes
 * 50 files and renames a directory; having that cancelled halfway because
 * somebody opened a puzzle is exactly the kind of torn state the staging
 * directory exists to prevent.
 */
class ContentModel(
    private val content: ContentSync,
    private val store: ProgressStore,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Where the blocking work goes. Overridden in tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        /** The real thing: assets as the floor, the app's own directory as the cache. */
        fun forApp(
            context: Context,
            store: ProgressStore,
            base: String = ContentSync.DEFAULT_BASE,
        ) = ContentModel(
            ContentSync(
                root = File(context.filesDir, "content"),
                bundled = AssetSource(context),
                fetcher = HttpFetcher(base),
            ),
            store,
        )
    }

    var library: List<LibraryEntry> by mutableStateOf(content.localManifest()?.entries ?: emptyList())
        private set

    var status: SyncUi by mutableStateOf(SyncUi.Quiet)
        private set

    private var pending: Pair<ContentManifest, SyncPlan>? = null
    private var attempted = false

    /** The puzzle behind an entry, from whichever set is being served. */
    fun puzzle(entry: LibraryEntry): Puzzle? =
        content.source().bytes(entry.path)?.let { PuzzleJson.parse(it.decodeToString()) }

    /**
     * Check once per process, and apply straight away unless the player has to
     * be told first.
     */
    fun refresh() {
        if (attempted) return
        attempted = true
        scope.launch {
            status = SyncUi.Checking
            val found = withContext(io) {
                content.check(store.startedPids(), store.solvedPids())
            }
            // No manifest, no network, a captive portal, a schema this build
            // does not know: all the same thing from here, and none of them is
            // worth a dialog. The app has a full set of puzzles either way.
            if (found == null) {
                status = SyncUi.Quiet
                return@launch
            }
            val (remote, plan) = found
            when {
                plan.idle -> status = SyncUi.Quiet
                plan.warns -> {
                    pending = found
                    status = SyncUi.Warning(
                        puzzles = remote.entries.size,
                        started = plan.losesStarted.size,
                        solved = plan.losesSolved.size,
                    )
                }
                else -> apply(remote, plan, blocking = false)
            }
        }
    }

    /** The player has seen the warning, so it now holds the screen. */
    fun acknowledge() {
        val (remote, plan) = pending ?: return
        pending = null
        scope.launch { apply(remote, plan, blocking = true) }
    }

    fun dismiss() {
        status = SyncUi.Quiet
    }

    private suspend fun apply(remote: ContentManifest, plan: SyncPlan, blocking: Boolean) {
        status = SyncUi.Working(0, remote.files.size, blocking)
        val result = withContext(io) {
            content.apply(remote, plan) { done, total ->
                // Called from whichever download thread finished the file, so
                // it is handed back to the main thread before it reaches any
                // Compose state. The guard is for the last one or two, which
                // can land after the apply has already returned: a stale
                // count must not undo the result.
                scope.launch {
                    if (status is SyncUi.Working) {
                        status = SyncUi.Working(done, total, blocking)
                    }
                }
            }
        }
        when (result) {
            is SyncResult.Applied -> {
                // Only now, and only for puzzles that are actually gone. The
                // solved tally keeps them; the grids cannot be kept, because
                // the marks name items that no longer exist.
                store.forget(result.departed)
                library = remote.entries
                // What the player is told is what they had worked on, not the
                // wider set of records that were dropped.
                status = SyncUi.Updated(result.puzzles, plan.losesStarted.size)
            }
            else -> status = SyncUi.Quiet
        }
    }
}
