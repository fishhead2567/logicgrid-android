package dev.logicgrid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading the generator's puzzle JSON.
 *
 * Hand-written against [JsonElement] rather than `@Serializable` data classes,
 * for two reasons:
 *
 *  * the clue IR is polymorphic on a `"kind"` string with a different field
 *    set per kind, and several kinds nest others -- a custom reader is plainer
 *    than the serializer gymnastics;
 *  * the files carry blocks the app has no use for (`spec`, `stats`, `solve`,
 *    `score`). With kotlinx's strict default every puzzle would fail to parse,
 *    so [json] sets `ignoreUnknownKeys`; doing the field access by hand makes
 *    that impossible to forget.
 */
object PuzzleJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(text: String): Puzzle = fromObject(json.parseToJsonElement(text).jsonObject)

    fun fromObject(root: JsonObject): Puzzle {
        val schema = parseSchema(root.getValue("schema").jsonObject)
        val solution = Solution(
            schema,
            root.getValue("solution").jsonObject.mapValues { (_, v) ->
                v.jsonArray.map { it.jsonPrimitive.content }
            },
        )
        val clues = root.getValue("clues").jsonArray.map { element ->
            val obj = element.jsonObject
            Clue(
                atoms = obj.getValue("atoms").jsonArray.map { parseAtom(it.jsonObject) },
                style = obj["style"]?.jsonPrimitive?.contentOrNull ?: "sentences",
            )
        }
        val text = (root["text"] as? JsonObject)?.let {
            PuzzleText(
                intro = it.getValue("intro").jsonPrimitive.content,
                clues = it.getValue("clues").jsonArray.map { c -> c.jsonPrimitive.content },
            )
        }
        return Puzzle(
            pid = root.getValue("pid").jsonPrimitive.content,
            title = root.getValue("title").jsonPrimitive.content,
            theme = root["theme"]?.jsonPrimitive?.contentOrNull ?: "",
            blurb = root["blurb"]?.jsonPrimitive?.contentOrNull ?: "",
            profile = root["profile"]?.jsonPrimitive?.contentOrNull ?: "",
            rating = root["rating"]?.jsonPrimitive?.intOrNull ?: 0,
            schema = schema,
            clues = clues,
            solution = solution,
            text = text,
        )
    }

    private fun parseSchema(obj: JsonObject): Schema {
        val categories = obj.getValue("categories").jsonArray.map { element ->
            val c = element.jsonObject
            Category(
                name = c.getValue("name").jsonPrimitive.content,
                label = c.getValue("label").jsonPrimitive.content,
                items = c.getValue("items").jsonArray.map { it.jsonPrimitive.content },
                ordered = c["ordered"]?.jsonPrimitive?.booleanOrNull ?: false,
                noun = c["noun"]?.jsonPrimitive?.contentOrNull ?: "",
                // Integers in the JSON; the generator types them as floats.
                values = (c["values"] as? JsonArray)
                    ?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 },
            )
        }
        return Schema(
            categories = categories,
            spineName = obj.getValue("spine").jsonPrimitive.content,
            entityNoun = obj["entity_noun"]?.jsonPrimitive?.contentOrNull ?: "entry",
            entityNounPlural =
                obj["entity_noun_plural"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    fun parseAtom(obj: JsonObject): Atom {
        fun ref(field: String): Ref = Ref.parse(obj.getValue(field).jsonPrimitive.content)
        fun sub(field: String): Atom = parseAtom(obj.getValue(field).jsonObject)
        return when (val kind = obj.getValue("kind").jsonPrimitive.content) {
            "link" -> Link(ref("a"), ref("b"))
            "not_link" -> NotLink(ref("a"), ref("b"))
            "distinct" -> Distinct(
                obj.getValue("members").jsonArray.map { Ref.parse(it.jsonPrimitive.content) }
            )
            "before" -> Before(ref("a"), ref("b"))
            "immediately_before" -> ImmediatelyBefore(ref("a"), ref("b"))
            "adjacent" -> Adjacent(ref("a"), ref("b"))
            "between" -> Between(ref("a"), ref("b"), ref("c"))
            "gap" -> Gap(
                ref("a"), ref("b"),
                obj["k"]?.jsonPrimitive?.intOrNull ?: 1,
                GapRel.parse(obj["rel"]?.jsonPrimitive?.contentOrNull ?: "eq"),
            )
            "parity" -> Parity(
                ref("a"), ref("b"),
                obj["same"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            "arith" -> Arith(
                ref("a"), ref("b"),
                obj["k"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                ArithOp.parse(obj["op"]?.jsonPrimitive?.contentOrNull ?: "add"),
            )
            "not" -> Not(sub("inner"))
            "or" -> Or(obj.getValue("options").jsonArray.map { parseAtom(it.jsonObject) })
            "not_both" -> NotBoth(sub("x"), sub("y"))
            "if_then" -> IfThen(sub("x"), sub("y"))
            else -> error("unknown clue kind '$kind'")
        }
    }
}
