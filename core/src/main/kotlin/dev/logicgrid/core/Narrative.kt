package dev.logicgrid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A narrated version of one puzzle: the same facts, written as prose.
 *
 * The generator produces these separately from the puzzle and only publishes
 * one that survived a round trip -- a narrator wrote it from the puzzle's
 * facts alone, then three independent readers read it back, and all three had
 * to recover the one true solution with no drift shared between them. So a
 * narrative that reaches the app is not merely nicer prose; it is prose proved
 * to still mean the puzzle.
 *
 * Which is why nothing here is optional at play time. A narrated puzzle can be
 * read instead of the canonical text with no loss of information, and a player
 * who finds the prose harder going can switch back without losing a fact.
 */
data class Narrative(
    /** Whose puzzle this is. The only safe way to pair the two files. */
    val pid: String,
    val title: String,
    /** The scene: two to four sentences, carrying no logical weight. */
    val introduction: String,
    /**
     * The cast, laid out exactly as the canonical introduction lays it out.
     *
     * Printed verbatim rather than narrated, and deliberately so: restating
     * five names, five months and five faults in prose makes a longer, less
     * scannable copy of a list the player is looking at on the chart anyway.
     * The narrator is told to leave it alone.
     */
    val preamble: String,
    val clues: List<String>,
    /** "pass" where the readers matched the clue set atom for atom. */
    val verdict: String,
) {
    /** A narrative with no clues cannot be played from; treat it as absent. */
    val usable: Boolean get() = clues.isNotEmpty()
}

object NarrativeJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Narrative {
        val root = json.parseToJsonElement(text).jsonObject
        fun str(field: String) = root[field]?.jsonPrimitive?.contentOrNull ?: ""
        return Narrative(
            pid = str("pid"),
            title = str("title"),
            introduction = str("introduction"),
            preamble = str("preamble"),
            clues = root["clues"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            verdict = str("verdict"),
        )
    }

    /**
     * The narrative for [puzzle], or null when the file is not about it.
     *
     * The pid check is the whole point of this function. Pairing the two files
     * by name is what a content set makes easy and what regenerating one makes
     * wrong: `45-mushroom.json` is a different puzzle after a reseed, while a
     * pid is a hash of the puzzle's own content and means one puzzle forever.
     * Without the check, a stale narrative would be shown against whatever
     * puzzle inherited its number -- clues for a mushroom hunt over a grid
     * about jam -- and the player would be told, in good prose, a set of facts
     * about nothing.
     *
     * This already happened once on the generator side, where narratives were
     * paired by file stem; it was invisible until a narrative existed for a
     * stem that had been reused. Here it is cheap to be certain, so be
     * certain.
     */
    fun forPuzzle(text: String, puzzle: Puzzle): Narrative? =
        runCatching { parse(text) }.getOrNull()
            ?.takeIf { it.pid == puzzle.pid && it.usable }
}
