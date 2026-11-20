package dev.logicgrid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One puzzle as the manifest describes it: enough to list, sort and filter the
 * library without opening a single puzzle file.
 *
 * This matters more than it looks. The menu shows 50 rows, and parsing 50
 * puzzles to draw them would mean reading ~500 KiB and building 50 schemas,
 * clue trees and all, on the way to the first frame. The manifest carries the
 * listing fields precisely so that the catalogue is cheap and the puzzle is
 * only parsed when someone opens it.
 */
data class LibraryEntry(
    /** Stable identity, and the key progress is stored under. */
    val pid: String,
    /** Human-facing file stem, e.g. "01-kite". Also the display order. */
    val id: String,
    val title: String,
    val theme: String,
    val profile: String,
    val rating: Int,
    val categories: Int,
    val items: Int,
    val clues: Int,
    val path: String,
    val sha256: String,
    val bytes: Int,
    /** Null when this puzzle has no narrated version; most have none. */
    val narrativePath: String? = null,
    val narrativeSha256: String? = null,
    val narrativeBytes: Int = 0,
) {
    /** "3x4" -- categories by items, the shape of the chart. */
    val shape: String get() = "${categories}x$items"
    val narrated: Boolean get() = narrativePath != null

    /**
     * The files this entry is made of, for the sync to hash and to fetch. A
     * puzzle always; a narrative only where one exists.
     */
    val files: List<ContentFile> get() = buildList {
        add(ContentFile(path, sha256, bytes))
        if (narrativePath != null && narrativeSha256 != null) {
            add(ContentFile(narrativePath, narrativeSha256, narrativeBytes))
        }
    }
}

object ManifestJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<LibraryEntry> =
        json.parseToJsonElement(text).jsonObject
            .getValue("puzzles").jsonArray
            .map { entry(it.jsonObject) }

    /**
     * The header as well as the entries, and the text they came from.
     *
     * The sync needs all three: `generated` to tell a stale cache from a fresh
     * one, `schema` to refuse a manifest a shipped build cannot read, and the
     * verbatim text so a cached manifest is byte for byte what was served
     * rather than a re-serialisation that silently drops whatever fields this
     * build happens to ignore.
     */
    fun parseManifest(text: String): ContentManifest {
        val root = json.parseToJsonElement(text).jsonObject
        return ContentManifest(
            schema = root["schema"]?.jsonPrimitive?.int ?: 1,
            generated = root["generated"]?.jsonPrimitive?.content ?: "",
            generator = root["generator"]?.jsonPrimitive?.content ?: "",
            entries = root.getValue("puzzles").jsonArray.map { entry(it.jsonObject) },
            text = text,
        )
    }

    private fun entry(obj: JsonObject): LibraryEntry {
        val file = obj.getValue("puzzle").jsonObject
        fun str(field: String) = obj.getValue(field).jsonPrimitive.content
        fun num(field: String) = obj.getValue(field).jsonPrimitive.int
        return LibraryEntry(
            pid = str("pid"),
            id = str("id"),
            title = str("title"),
            theme = str("theme"),
            profile = str("profile"),
            rating = num("rating"),
            categories = num("categories"),
            items = num("items"),
            clues = num("clues"),
            path = file.getValue("path").jsonPrimitive.content,
            sha256 = file.getValue("sha256").jsonPrimitive.content,
            bytes = file.getValue("bytes").jsonPrimitive.int,
            narrativePath = obj["narrative"]
                ?.jsonObject?.get("path")?.jsonPrimitive?.content,
            narrativeSha256 = obj["narrative"]
                ?.jsonObject?.get("sha256")?.jsonPrimitive?.content,
            narrativeBytes = obj["narrative"]
                ?.jsonObject?.get("bytes")?.jsonPrimitive?.int ?: 0,
        )
    }
}
