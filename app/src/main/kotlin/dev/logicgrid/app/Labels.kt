package dev.logicgrid.app

/**
 * Shortest prefixes that still tell a category's items apart.
 *
 * Header cells are a few dp wide, so full item names ("a gelatine cube of
 * unclear provenance") cannot be shown there. Ported from the terminal
 * player's `short_labels`: strip a leading article, strip any prefix every
 * item shares, then take the shortest distinguishing prefix. That turns
 * "a fluted rim" / "a fork-crimped edge" into `flu` / `for`, and
 * "slot one" / "slot two" into `one` / `two`.
 *
 * The full name always remains available in the status bar and in the
 * selection readout, so nothing depends on these being readable in isolation.
 */
private val ARTICLES = listOf("the ", "a ", "an ")

private fun stripArticle(item: String): String {
    val lower = item.lowercase()
    for (a in ARTICLES) if (lower.startsWith(a)) return item.substring(a.length)
    return item
}

fun shortLabels(items: List<String>, cap: Int = 10, floor: Int = 3): List<String> {
    var bases = items.map(::stripArticle)
    if (bases.size > 1) {
        // A prefix every item shares carries no information.
        var shared = 0
        val shortest = bases.minOf { it.length }
        while (shared < shortest &&
            bases.map { it[shared].lowercaseChar() }.distinct().size == 1
        ) shared++
        val word = bases[0].lastIndexOf(' ', minOf(shared, bases[0].length - 1))
        if (word > 0 && bases.all { it.length - word - 1 >= 2 }) {
            bases = bases.map { it.substring(word + 1) }
        }
    }
    return bases.mapIndexed { i, base ->
        val others = bases.filterIndexed { j, _ -> j != i }
        var length = floor
        while (length < minOf(cap, base.length)) {
            val prefix = base.take(length).lowercase()
            if (others.none { it.lowercase().startsWith(prefix) }) break
            length++
        }
        base.take(length).trim()
    }
}

/** Convenience for a single label where the sibling set is not to hand. */
fun shortLabel(item: String, cap: Int = 10): String =
    stripArticle(item).take(cap).trim()
