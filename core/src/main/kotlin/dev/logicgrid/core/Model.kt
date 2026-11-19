package dev.logicgrid.core

/**
 * A reference to one item of one category: the atom every clue is built from.
 *
 * Ordering is load-bearing, not incidental: a [Cell] key is the canonical
 * ordering of its two refs, so the Kotlin and Python sides must agree on which
 * way round a symmetric pair is stored. The Python `Ref` is
 * `@dataclass(order=True)`, comparing `(cat, item)` as a tuple; this mirrors
 * that. Python compares by Unicode code point and Kotlin by UTF-16 code unit,
 * which coincide for all data here -- so do not "simplify" this to something
 * locale-aware.
 */
data class Ref(val cat: String, val item: String) : Comparable<Ref> {
    val key: String get() = "$cat:$item"

    override fun compareTo(other: Ref): Int =
        compareValuesBy(this, other, Ref::cat, Ref::item)

    override fun toString(): String = key

    companion object {
        fun parse(text: String): Ref {
            val i = text.indexOf(':')
            require(i > 0) { "malformed ref '$text', expected 'category:item'" }
            return Ref(text.substring(0, i).trim(), text.substring(i + 1).trim())
        }
    }
}

/**
 * One axis of the grid.
 *
 * Only what the app needs. The generator emits several more fields -- verbs,
 * comparatives, step nouns -- that exist purely to render English on the Python
 * side. The content repo now ships pre-rendered prose in each puzzle's `text`
 * block, so nothing here re-derives it and deliberately cannot.
 */
data class Category(
    val name: String,
    val label: String,
    val items: List<String>,
    val ordered: Boolean = false,
    val noun: String = "",
    /**
     * Numeric value per item, aligned with [items]; only ever set on an
     * ordered spine. The JSON holds integers (`200`), so this must decode as
     * Double -- the Python types it `tuple[float, ...]`.
     */
    val values: List<Double>? = null,
) {
    val size: Int get() = items.size

    fun refs(): List<Ref> = items.map { Ref(name, it) }

    fun index(item: String): Int {
        val i = items.indexOf(item)
        require(i >= 0) { "'$item' is not an item of category '$name'" }
        return i
    }
}

/**
 * The categories of a puzzle plus which one is the spine.
 *
 * The spine supplies the puzzle's index: entity *p* holds `spine.items[p]`.
 * That removes the M! relabelling symmetry a bare "entity id" model suffers
 * from, and makes an entity's position *be* its index.
 */
class Schema(
    val categories: List<Category>,
    val spineName: String,
    val entityNoun: String = "entry",
    entityNounPlural: String = "",
) {
    init {
        require(categories.size >= 2) { "a puzzle needs at least two categories" }
        val sizes = categories.map { it.size }.distinct()
        require(sizes.size == 1) { "all categories must be the same size, got $sizes" }
        require(categories.any { it.name == spineName }) {
            "spine '$spineName' is not one of ${categories.map { it.name }}"
        }
        val all = categories.flatMap { it.items }
        require(all.size == all.toSet().size) { "item names must be globally unique" }
    }

    val entityNounPlural: String = entityNounPlural.ifEmpty { entityNoun + "s" }

    private val byName: Map<String, Category> = categories.associateBy { it.name }
    private val byItem: Map<String, Ref>

    val spine: Category = byName.getValue(spineName)
    val size: Int = spine.size

    /**
     * Every ref, in the order the conformance fixtures index them: categories
     * in order, items in order. **Must not be reordered** -- the fixtures
     * encode a cell as `min*R + max` over indices into this list, and the
     * Python side derives the same order from the same puzzle JSON.
     */
    val allRefs: List<Ref> = categories.flatMap { it.refs() }

    /** Index of each ref in [allRefs], for cell encoding. */
    val refIndex: Map<Ref, Int> = allRefs.withIndex().associate { (i, r) -> r to i }

    init {
        byItem = allRefs.associateBy { it.item }
    }

    /** Unordered pairs of distinct categories -- the grid's blocks. */
    val pairs: List<Pair<String, String>> = buildList {
        for (i in categories.indices) {
            for (j in i + 1 until categories.size) {
                add(categories[i].name to categories[j].name)
            }
        }
    }

    fun category(name: String): Category = byName.getValue(name)
    fun categoryOf(ref: Ref): Category = byName.getValue(ref.cat)
    fun isSpine(ref: Ref): Boolean = ref.cat == spineName
    fun otherCategories(): List<Category> = categories.filter { it.name != spineName }

    /** Item names are globally unique, so a bare name identifies a ref. */
    fun resolveOrNull(item: String): Ref? = byItem[item]

    fun resolve(item: String): Ref =
        byItem[item] ?: error("no item named '$item' in this puzzle")
}

/** A complete assignment: `table[category][position] -> item`. */
class Solution(val schema: Schema, table: Map<String, List<String>>) {
    val table: Map<String, List<String>>
    private val positionOf: Map<Ref, Int>

    init {
        val normalised = LinkedHashMap<String, List<String>>()
        for (cat in schema.categories) {
            val row = requireNotNull(table[cat.name]) {
                "solution is missing category '${cat.name}'"
            }
            require(row.sorted() == cat.items.sorted()) {
                "solution row for '${cat.name}' is not a permutation of its items"
            }
            normalised[cat.name] = row
        }
        require(normalised.getValue(schema.spineName) == schema.spine.items) {
            "the spine row must be in canonical item order"
        }
        this.table = normalised
        positionOf = buildMap {
            normalised.forEach { (cat, row) ->
                row.forEachIndexed { p, item -> put(Ref(cat, item), p) }
            }
        }
    }

    fun position(ref: Ref): Int = positionOf[ref] ?: error("unknown ref '${ref.key}'")

    fun sameEntity(a: Ref, b: Ref): Boolean = position(a) == position(b)

    fun entity(position: Int): Map<String, String> = table.mapValues { it.value[position] }

    override fun equals(other: Any?): Boolean = other is Solution && other.table == table
    override fun hashCode(): Int = table.hashCode()
}

/**
 * One numbered clue. Bundling is presentational: a puzzle's logical content is
 * the flat union of every clue's atoms.
 */
data class Clue(val atoms: List<Atom>, val style: String = "sentences")

/** The canonical prose, pre-rendered by the generator. */
data class PuzzleText(val intro: String, val clues: List<String>)

/** A finished puzzle. */
data class Puzzle(
    val pid: String,
    val title: String,
    val theme: String,
    val blurb: String,
    val profile: String,
    val rating: Int,
    val schema: Schema,
    val clues: List<Clue>,
    val solution: Solution,
    val text: PuzzleText?,
) {
    val atoms: List<Atom> get() = clues.flatMap { it.atoms }
    val size: Int get() = schema.size
    val categoryCount: Int get() = schema.categories.size
}
