package dev.logicgrid.app

import android.content.Context
import android.content.SharedPreferences
import dev.logicgrid.core.ProgressJson
import dev.logicgrid.core.PuzzleProgress

/**
 * Where a player's marks live between sessions, keyed by puzzle pid.
 *
 * An interface, so the menu and the solve screen can be tested against an
 * in-memory implementation with no Android framework in sight.
 *
 * Deliberately NOT synced to any server: in-progress state stays on the
 * device that made it.
 */
interface ProgressStore {
    fun read(pid: String): PuzzleProgress
    fun write(pid: String, progress: PuzzleProgress)
    /** The pids that have been solved; cheap enough to call per menu frame. */
    fun solvedPids(): Set<String>
    fun startedPids(): Set<String>
    fun clear(pid: String)
}

/** Test double. Same contract, no device. */
class InMemoryProgressStore(
    initial: Map<String, PuzzleProgress> = emptyMap(),
) : ProgressStore {
    private val map = HashMap(initial)
    override fun read(pid: String) = map[pid] ?: PuzzleProgress()
    override fun write(pid: String, progress: PuzzleProgress) { map[pid] = progress }
    override fun solvedPids() = map.filterValues { it.solved }.keys.toSet()
    override fun startedPids() = map.filterValues { it.started }.keys.toSet()
    override fun clear(pid: String) { map.remove(pid) }
}

/**
 * SharedPreferences, not Room.
 *
 * The data is a few dozen small blobs read by primary key and never queried
 * any other way, so a database would buy nothing but a schema to migrate. If
 * the library ever grows a "find every puzzle I half-finished in March" view,
 * that is the point to reach for Room -- not before.
 *
 * Writes use apply(), so a mark never blocks the UI thread on disk.
 */
class PrefsProgressStore(context: Context) : ProgressStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    private fun key(pid: String) = "p:$pid"

    override fun read(pid: String): PuzzleProgress =
        ProgressJson.decode(prefs.getString(key(pid), null))

    override fun write(pid: String, progress: PuzzleProgress) {
        prefs.edit().putString(key(pid), ProgressJson.encode(progress)).apply()
    }

    override fun solvedPids(): Set<String> = scan { it.solved }

    override fun startedPids(): Set<String> = scan { it.started }

    private fun scan(predicate: (PuzzleProgress) -> Boolean): Set<String> =
        prefs.all.keys
            .filter { it.startsWith("p:") }
            .filter { predicate(ProgressJson.decode(prefs.getString(it, null))) }
            .map { it.removePrefix("p:") }
            .toSet()

    override fun clear(pid: String) {
        prefs.edit().remove(key(pid)).apply()
    }
}
