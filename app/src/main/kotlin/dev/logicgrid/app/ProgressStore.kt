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
/**
 * Choices about how the chart is drawn, which belong to the player rather than
 * to any one puzzle. Kept apart from [ProgressStore] because they have a
 * different lifetime: progress is per puzzle and arrives by pid, these are
 * global and outlive every puzzle.
 *
 * Assist is deliberately NOT here. It changes which marks the board holds, so
 * it is board state and travels with the board.
 */
interface ViewPrefs {
    var rotatedHeaders: Boolean

    /**
     * Whether a puzzle opened for the first time starts with assist on.
     *
     * A *default*, not a global setting: assist changes which marks the board
     * holds, so it is board state and travels with the board. This only
     * decides what a board that does not exist yet starts out as.
     */
    var assistByDefault: Boolean

    /**
     * Whether a puzzle that has narrated text opens showing it.
     *
     * On by default. The narrated version is the one written to be read; the
     * canonical text is written to be impossible to misread, which is not the
     * same thing and reads like a specification. A player who prefers the
     * specification can say so here or per puzzle, and the nine narrated
     * puzzles are the only ones this changes anything for.
     */
    var narratedText: Boolean
}

interface ProgressStore {
    fun read(pid: String): PuzzleProgress
    fun write(pid: String, progress: PuzzleProgress)
    /** The pids that have been solved; cheap enough to call per menu frame. */
    fun solvedPids(): Set<String>
    fun startedPids(): Set<String>
    fun clear(pid: String)

    /**
     * Every pid ever solved on this device, whether or not the library still
     * contains it.
     *
     * A content update replaces puzzles, and a grid half-finished on a puzzle
     * that no longer exists cannot be kept. The *fact* of having solved it can
     * be, and should be: losing the record of fifty solved puzzles because the
     * set was regenerated is a worse loss than losing one grid in progress.
     * So this set is only ever added to, including by [forget].
     */
    fun solvedEver(): Set<String>

    /**
     * Drop progress for puzzles the content set no longer contains, keeping
     * their place in [solvedEver].
     */
    fun forget(pids: Collection<String>)
}

/** Test double. Same contract, no device. */
class InMemoryProgressStore(
    initial: Map<String, PuzzleProgress> = emptyMap(),
) : ProgressStore, ViewPrefs {
    override var rotatedHeaders: Boolean = false
    override var assistByDefault: Boolean = false
    override var narratedText: Boolean = true
    private val map = HashMap(initial)
    private val ever = initial.filterValues { it.solved }.keys.toMutableSet()
    override fun read(pid: String) = map[pid] ?: PuzzleProgress()
    override fun write(pid: String, progress: PuzzleProgress) {
        map[pid] = progress
        if (progress.solved) ever += pid
    }
    override fun solvedPids() = map.filterValues { it.solved }.keys.toSet()
    override fun startedPids() = map.filterValues { it.started }.keys.toSet()
    override fun clear(pid: String) { map.remove(pid) }
    override fun solvedEver() = ever + solvedPids()
    override fun forget(pids: Collection<String>) {
        ever += pids.filter { map[it]?.solved == true }
        pids.forEach { map.remove(it) }
    }
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
class PrefsProgressStore(context: Context) : ProgressStore, ViewPrefs {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    override var rotatedHeaders: Boolean
        get() = prefs.getBoolean("view:rotatedHeaders", false)
        set(value) { prefs.edit().putBoolean("view:rotatedHeaders", value).apply() }

    override var assistByDefault: Boolean
        get() = prefs.getBoolean("view:assistByDefault", false)
        set(value) { prefs.edit().putBoolean("view:assistByDefault", value).apply() }

    override var narratedText: Boolean
        get() = prefs.getBoolean("view:narratedText", true)
        set(value) { prefs.edit().putBoolean("view:narratedText", value).apply() }

    private fun key(pid: String) = "p:$pid"

    private companion object {
        /** Deliberately outside the `p:` namespace that [scan] walks. */
        const val EVER = "solved:ever"
    }

    override fun read(pid: String): PuzzleProgress =
        ProgressJson.decode(prefs.getString(key(pid), null))

    override fun write(pid: String, progress: PuzzleProgress) {
        val edit = prefs.edit().putString(key(pid), ProgressJson.encode(progress))
        if (progress.solved) edit.putStringSet(EVER, stored() + pid)
        edit.apply()
    }

    /**
     * The stored set, unioned with whatever is still solved in the library.
     *
     * The union is not redundancy -- it is the migration. Installs that
     * predate this key have solved puzzles recorded only in their progress
     * blobs, and reading both means the tally is right on the first launch
     * after the upgrade without a migration step to write and get wrong.
     */
    override fun solvedEver(): Set<String> = stored() + solvedPids()

    override fun forget(pids: Collection<String>) {
        val solved = pids.filter { read(it).solved }
        val edit = prefs.edit()
        pids.forEach { edit.remove(key(it)) }
        if (solved.isNotEmpty()) edit.putStringSet(EVER, stored() + solved)
        edit.apply()
    }

    private fun stored(): Set<String> = prefs.getStringSet(EVER, emptySet()) ?: emptySet()

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
