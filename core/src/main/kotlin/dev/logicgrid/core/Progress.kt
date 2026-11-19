package dev.logicgrid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the player has done to one puzzle, as it is stored between sessions.
 *
 * Only the player's **own** marks are kept, never the closure. The closure is
 * a pure function of the marks and the puzzle, so storing it would be storing
 * a derived value that can disagree with its source -- and when it does, the
 * board resurrects marks the player never made. The terminal player learned
 * this the hard way; recomputing on load is both cheaper and safer.
 *
 * [solved] is held separately rather than recomputed, because it is a fact
 * about history: a player who solves a puzzle and then clears a cell to look
 * at something has still solved it.
 */
data class PuzzleProgress(
    val userMarks: Map<Cell, Boolean> = emptyMap(),
    val assist: Boolean = false,
    val solved: Boolean = false,
) {
    val started: Boolean get() = userMarks.isNotEmpty() || solved

    fun toBoardState(): BoardState = BoardState(userMarks, assist)

    companion object {
        fun of(state: BoardState, solved: Boolean): PuzzleProgress =
            PuzzleProgress(state.userMarks, state.assist, solved)
    }
}

/**
 * Progress as a JSON string, so a store only has to keep strings by key.
 *
 * Refs are written as their `cat:item` key. A malformed or truncated record
 * decodes to an empty progress rather than throwing: a corrupt save should
 * cost the player one puzzle's marks, not crash the app on launch.
 */
object ProgressJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(p: PuzzleProgress): String = buildString {
        append("{\"v\":1,\"solved\":").append(p.solved)
        append(",\"assist\":").append(p.assist)
        append(",\"marks\":[")
        p.userMarks.entries.forEachIndexed { i, (cell, value) ->
            if (i > 0) append(',')
            append("{\"a\":").append(JsonPrimitive(cell.a.key))
            append(",\"b\":").append(JsonPrimitive(cell.b.key))
            append(",\"v\":").append(value).append('}')
        }
        append("]}")
    }

    fun decode(text: String?): PuzzleProgress {
        if (text.isNullOrBlank()) return PuzzleProgress()
        return try {
            val obj: JsonObject = json.parseToJsonElement(text).jsonObject
            val marks = (obj["marks"] as? JsonArray).orEmpty().associate { element ->
                val m = element.jsonObject
                cellOf(
                    Ref.parse(m.getValue("a").jsonPrimitive.content),
                    Ref.parse(m.getValue("b").jsonPrimitive.content),
                ) to m.getValue("v").jsonPrimitive.boolean
            }
            PuzzleProgress(
                userMarks = marks,
                assist = obj["assist"]?.jsonPrimitive?.boolean ?: false,
                solved = obj["solved"]?.jsonPrimitive?.boolean ?: false,
            )
        } catch (e: Exception) {
            PuzzleProgress()
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this ?: emptyList()
}
