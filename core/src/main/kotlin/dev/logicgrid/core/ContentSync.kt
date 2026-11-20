package dev.logicgrid.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Keeping the bundled puzzles up to date with the content repo.
 *
 * Three rules shape everything here, and all three come from the fetch
 * contract in the content repo's README:
 *
 *  1. **The network is never on the path to playing.** The app ships a
 *     complete set in `assets/`, so there is always something to read. A sync
 *     runs behind the library, and a failure is not an error the player has to
 *     deal with -- it is a sync that will happen next launch instead.
 *  2. **Verify before replacing.** A truncated download must never overwrite a
 *     good puzzle, so every fetched file is hashed against the manifest and
 *     discarded if it disagrees.
 *  3. **The manifest and the files it describes must never disagree.** This is
 *     the subtle one. Filenames are reused between generations:
 *     `puzzles/04-pigeon.json` is a *different puzzle* after a reseed. Write
 *     the new files into the live directory and crash halfway, and the old
 *     manifest is now describing new content -- an entry whose `pid`, hash and
 *     title belong to a puzzle that is no longer in the file. Every checksum
 *     would still verify. So a new set is built up beside the old one and
 *     swapped in whole, with the manifest written last.
 */

/** One file the sync may have to fetch, with the hash it must match. */
data class ContentFile(val path: String, val sha256: String, val bytes: Int)

/** The manifest's header as well as its entries. */
data class ContentManifest(
    val schema: Int,
    /** ISO 8601 UTC, from the generator. */
    val generated: String,
    val generator: String,
    val entries: List<LibraryEntry>,
    /** The exact text this was parsed from, so a cache can store it verbatim. */
    val text: String,
) {
    /** Every file this set is made of: a puzzle each, a narrative where one exists. */
    val files: List<ContentFile> get() = entries.flatMap { it.files }

    val pids: Set<String> get() = entries.map { it.pid }.toSet()
}

/**
 * Somewhere a complete content set can be read from: the bundled assets, or a
 * cache directory. Keeps [ContentSync] free of any Android type -- the app
 * passes in an assets-backed implementation.
 */
interface ContentSource {
    /** The manifest text, or null if this source has no usable set. */
    fun manifestText(): String?

    /** Raw bytes at a set-relative path such as `puzzles/01-bookshop.json`. */
    fun bytes(path: String): ByteArray?
}

/** A content set in a directory. */
class DirSource(val dir: File) : ContentSource {
    override fun manifestText(): String? =
        File(dir, "manifest.json").takeIf { it.isFile }?.readText()

    override fun bytes(path: String): ByteArray? =
        File(dir, path).takeIf { it.isFile }?.readBytes()
}

/** Fetches set-relative paths over the wire. Throws on any failure. */
interface ContentFetcher {
    fun fetch(path: String): ByteArray
}

/**
 * Plain HTTPS against the raw files, which is all the contract asks for: no
 * git client, no API token, no auth.
 */
class HttpFetcher(
    private val base: String,
    private val timeoutMs: Int = 15_000,
) : ContentFetcher {
    override fun fetch(path: String): ByteArray {
        // URI, not the URL(String) constructor, which is deprecated and
        // also parses far more leniently than anything here wants.
        val url = URI(base.trimEnd('/') + "/" + path.trimStart('/')).toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            // No identifying header. The content is public and the request
            // says nothing at all about who is asking for it.
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("GET " + path + " returned " + code)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * What a sync would do, worked out from the two manifests before a single
 * puzzle file is downloaded.
 *
 * That ordering is the whole trick to the warning the player sees. Progress is
 * keyed on `pid`, and the manifest carries every `pid`, so whether an update
 * destroys progress is answerable from a 22 KiB header -- there is no need to
 * fetch 430 KiB of puzzles first and then ask.
 */
data class SyncPlan(
    /** Files to download, because nothing local has that hash. */
    val fetch: List<ContentFile>,
    /** Pids the player has started that the new set does not contain. */
    val losesStarted: List<String>,
    /** The subset of those that were solved. */
    val losesSolved: List<String>,
    /**
     * Every pid leaving the library, started or not.
     *
     * Wider than [losesStarted] because that one is what the player is told
     * about, and this one is what there is to clean up: a puzzle opened and
     * closed without a mark still has a stored record, and nothing will ever
     * ask for it again.
     */
    val departs: List<String>,
    /** True when the new manifest describes exactly the set already held. */
    val same: Boolean,
) {
    /** Nothing to do at all. */
    val idle: Boolean get() = same && fetch.isEmpty()

    /**
     * Whether the player has to be told before this is applied.
     *
     * `&& !idle` is not belt and braces. Progress can be stored under a pid
     * that is in no manifest at all -- a leftover from a set two updates ago --
     * and without this, every launch from then on would plan an update that
     * does nothing and announce that it costs something.
     */
    val warns: Boolean get() = losesStarted.isNotEmpty() && !idle
}

object ContentPlan {
    /**
     * @param have sha256 of what is already held at a path, or null if the
     *             file is absent. Taken as a function so the caller decides
     *             whether to trust a manifest or to hash the files.
     */
    fun of(
        local: ContentManifest?,
        remote: ContentManifest,
        have: (String) -> String?,
        startedPids: Set<String>,
        solvedPids: Set<String>,
    ): SyncPlan {
        val fetch = remote.files.filter { have(it.path) != it.sha256 }
        val keep = remote.pids
        // Only progress on a puzzle that is actually leaving counts as lost.
        // A regeneration that happens to reproduce a puzzle byte for byte
        // keeps its pid, and so keeps its progress -- which is the point of
        // keying on content rather than on a filename.
        val lost = startedPids.filterNot { it in keep }.sorted()
        return SyncPlan(
            fetch = fetch,
            losesStarted = lost,
            losesSolved = lost.filter { it in solvedPids },
            departs = (lost + local?.pids.orEmpty().filterNot { it in keep }).distinct().sorted(),
            same = local != null &&
                local.pids == remote.pids &&
                local.generated == remote.generated,
        )
    }
}

/** How an apply ended. */
sealed interface SyncResult {
    /** The new set is in place. [fetched] is what came over the wire. */
    data class Applied(
        val puzzles: Int,
        val fetched: Int,
        /** Pids whose stored progress is now unreachable and can be dropped. */
        val departed: List<String>,
    ) : SyncResult

    /** Already current; nothing was written. */
    data object UpToDate : SyncResult

    /** Something went wrong. The old set is untouched and still readable. */
    data class Failed(val reason: String) : SyncResult
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * The sync itself.
 *
 * @param root    a private directory the app owns; two subdirectories live here.
 * @param bundled the set shipped in the APK, which is always complete.
 * @param fetcher how to reach the content repo.
 */
class ContentSync(
    private val root: File,
    private val bundled: ContentSource,
    private val fetcher: ContentFetcher,
) {
    private val live = File(root, "current")
    private val staging = File(root, "next")

    /**
     * Where the app should read content from right now.
     *
     * The cache wins only if it parses *and* is not older than the bundled
     * set. That second condition matters: installing an app update ships newer
     * puzzles in assets, and a cache left over from before it would otherwise
     * shadow them for good.
     */
    fun source(): ContentSource {
        val cached = manifestOf(DirSource(live)) ?: return bundled
        val shipped = manifestOf(bundled) ?: return DirSource(live)
        return if (olderThan(cached.generated, shipped.generated)) bundled else DirSource(live)
    }

    /** The manifest of whichever set [source] would serve. */
    fun localManifest(): ContentManifest? = manifestOf(source())

    private fun manifestOf(source: ContentSource): ContentManifest? = try {
        source.manifestText()?.let { ManifestJson.parseManifest(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Fetch the remote manifest and work out what it would mean. Nothing is
     * written and nothing else is downloaded.
     */
    fun check(
        startedPids: Set<String>,
        solvedPids: Set<String>,
    ): Pair<ContentManifest, SyncPlan>? {
        val remote = try {
            ManifestJson.parseManifest(fetcher.fetch("manifest.json").decodeToString())
        } catch (e: Exception) {
            return null
        }
        // A schema bump means fields this build does not understand. Refusing
        // to sync leaves a working app on old content, which is the better of
        // the two failures.
        if (remote.schema != 1) return null
        val from = source()
        val local = manifestOf(from)
        // Trust the local manifest's hash for a file that is actually there,
        // rather than re-hashing 430 KiB on every launch. A file the manifest
        // claims but that has gone missing reads as absent, so it is refetched.
        val known = local?.files?.associate { it.path to it.sha256 }.orEmpty()
        return remote to ContentPlan.of(
            local = local,
            remote = remote,
            have = { path -> known[path]?.takeIf { from.bytes(path) != null } },
            startedPids = startedPids,
            solvedPids = solvedPids,
        )
    }

    /**
     * Build the new set beside the old one and swap it in.
     *
     * Every file is either copied from what is already held -- so an update
     * that changes five puzzles downloads five puzzles -- or fetched and
     * hash-verified. The manifest is written last, and the swap happens only
     * once the whole set is present and correct.
     */
    fun apply(remote: ContentManifest, plan: SyncPlan): SyncResult {
        if (plan.idle) return SyncResult.UpToDate
        val from = source()
        staging.deleteRecursively()
        if (!staging.mkdirs()) return SyncResult.Failed("cannot create " + staging.path)
        var fetched = 0
        try {
            val wanted = plan.fetch.associateBy { it.path }
            for (file in remote.files) {
                val bytes: ByteArray
                if (file.path in wanted) {
                    fetched++
                    val got = fetcher.fetch(file.path)
                    val digest = sha256(got)
                    if (digest != file.sha256) {
                        return fail(
                            file.path + ": expected " + file.sha256.take(12) +
                                ", got " + digest.take(12)
                        )
                    }
                    if (got.size != file.bytes) {
                        return fail(
                            file.path + ": expected " + file.bytes +
                                " bytes, got " + got.size
                        )
                    }
                    bytes = got
                } else {
                    bytes = from.bytes(file.path) ?: return fail(file.path + ": vanished mid-sync")
                }
                val out = File(staging, file.path)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
            // Written last, and only once every file it describes is present.
            File(staging, "manifest.json").writeText(remote.text)
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        }

        // The swap. A failure here costs the cache, never the bundled set, so
        // the app still has a full library to show and tries again next launch.
        if (live.exists() && !live.deleteRecursively()) {
            return fail("cannot clear " + live.path)
        }
        if (!staging.renameTo(live)) return fail("cannot move the new set into place")
        return SyncResult.Applied(remote.entries.size, fetched, plan.departs)
    }

    private fun fail(reason: String): SyncResult.Failed {
        staging.deleteRecursively()
        return SyncResult.Failed(reason)
    }

    companion object {
        /** Where the content lives. Raw HTTPS, no API, no token. */
        const val DEFAULT_BASE =
            "https://raw.githubusercontent.com/fishhead2567/logicgrid-puzzles/main"

        /**
         * Lexicographic, which is correct for ISO 8601 at a fixed offset -- and
         * the generator always writes `+00:00`. Anything that does not look
         * like one of those timestamps is treated as incomparable rather than
         * as older, so an unreadable date never discards a cache.
         */
        fun olderThan(a: String, b: String): Boolean {
            val iso = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+00:00$""")
            if (!iso.matches(a) || !iso.matches(b)) return false
            return a < b
        }
    }
}
