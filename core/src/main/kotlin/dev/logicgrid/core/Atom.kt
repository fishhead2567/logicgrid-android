package dev.logicgrid.core

/**
 * A clue atom.
 *
 * The app does not *render* atoms -- the content repo ships pre-rendered prose
 * in each puzzle's `text` block, precisely so this module never has to
 * reimplement English. Atoms are modelled because they carry the puzzle's
 * logic, which a hint engine or an in-app validator would need, and because
 * round-tripping them keeps the data contract honest.
 *
 * [holds] is the one behaviour worth having now: it answers "is this atom true
 * of this assignment", which is all a validator needs.
 */
sealed interface Atom {
    val kind: String

    /** The refs this atom constrains, deduplicated, in declaration order. */
    fun refs(): List<Ref>

    /** Is this atom satisfied when each of [refs] sits at the given position? */
    fun check(position: (Ref) -> Int, schema: Schema): Boolean

    /** Is this atom true of a complete solution? */
    fun holds(solution: Solution): Boolean =
        check({ solution.position(it) }, solution.schema)
}

/** Atoms whose two symmetric arguments are normalised at construction. */
private fun order(a: Ref, b: Ref): Pair<Ref, Ref> = if (b < a) b to a else a to b

// --- tier A: pairing ------------------------------------------------------

class Link private constructor(val a: Ref, val b: Ref) : Atom {
    override val kind: String get() = "link"
    override fun refs(): List<Ref> = if (a == b) listOf(a) else listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        position(a) == position(b)

    override fun equals(other: Any?): Boolean = other is Link && other.a == a && other.b == b
    override fun hashCode(): Int = 31 * a.hashCode() + b.hashCode()
    override fun toString(): String = "link(${a.key}, ${b.key})"

    companion object {
        operator fun invoke(a: Ref, b: Ref): Link = order(a, b).let { Link(it.first, it.second) }
    }
}

class NotLink private constructor(val a: Ref, val b: Ref) : Atom {
    override val kind: String get() = "not_link"
    override fun refs(): List<Ref> = if (a == b) listOf(a) else listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        position(a) != position(b)

    override fun equals(other: Any?): Boolean = other is NotLink && other.a == a && other.b == b
    override fun hashCode(): Int = 31 * a.hashCode() + b.hashCode() + 1
    override fun toString(): String = "not_link(${a.key}, ${b.key})"

    companion object {
        operator fun invoke(a: Ref, b: Ref): NotLink =
            order(a, b).let { NotLink(it.first, it.second) }
    }
}

data class Distinct(val members: List<Ref>) : Atom {
    init { require(members.size >= 2) { "distinct needs at least two refs" } }
    private val sorted = members.distinct().sorted()
    override val kind: String get() = "distinct"
    override fun refs(): List<Ref> = sorted
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        sorted.map(position).toSet().size == sorted.size
}

// --- tier B: position -----------------------------------------------------

data class Before(val a: Ref, val b: Ref) : Atom {
    override val kind: String get() = "before"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        position(a) < position(b)
}

data class ImmediatelyBefore(val a: Ref, val b: Ref) : Atom {
    override val kind: String get() = "immediately_before"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        position(b) - position(a) == 1
}

class Adjacent private constructor(val a: Ref, val b: Ref) : Atom {
    override val kind: String get() = "adjacent"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        kotlin.math.abs(position(a) - position(b)) == 1

    override fun equals(other: Any?): Boolean = other is Adjacent && other.a == a && other.b == b
    override fun hashCode(): Int = 31 * a.hashCode() + b.hashCode() + 2
    override fun toString(): String = "adjacent(${a.key}, ${b.key})"

    companion object {
        operator fun invoke(a: Ref, b: Ref): Adjacent =
            order(a, b).let { Adjacent(it.first, it.second) }
    }
}

/** [a] sits strictly between [b] and [c]; the outer pair is unordered. */
class Between private constructor(val a: Ref, val b: Ref, val c: Ref) : Atom {
    override val kind: String get() = "between"
    override fun refs(): List<Ref> = listOf(a, b, c)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean {
        val pa = position(a); val pb = position(b); val pc = position(c)
        return (pb < pa && pa < pc) || (pc < pa && pa < pb)
    }

    override fun equals(other: Any?): Boolean =
        other is Between && other.a == a && other.b == b && other.c == c
    override fun hashCode(): Int = (31 * a.hashCode() + b.hashCode()) * 31 + c.hashCode()
    override fun toString(): String = "between(${a.key}; ${b.key}, ${c.key})"

    companion object {
        operator fun invoke(a: Ref, b: Ref, c: Ref): Between =
            if (c < b) Between(a, c, b) else Between(a, b, c)
    }
}

enum class GapRel { EQ, GE, LE;
    companion object {
        fun parse(s: String): GapRel = when (s.lowercase()) {
            "eq" -> EQ; "ge" -> GE; "le" -> LE
            else -> error("gap rel must be eq/ge/le, got '$s'")
        }
    }
    val wire: String get() = name.lowercase()
}

/**
 * Absolute spine distance between [a] and [b], compared against [k].
 *
 * A gap is a *difference* of positions, never a count of things in between --
 * "three apart" is k=3, "three in between" would be k=4. The generator is
 * emphatic about this because it is the likeliest way to change a clue's
 * meaning, and the same applies to anything reading these atoms.
 */
class Gap private constructor(
    val a: Ref, val b: Ref, val k: Int, val rel: GapRel,
) : Atom {
    override val kind: String get() = "gap"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean {
        val d = kotlin.math.abs(position(a) - position(b))
        return when (rel) { GapRel.EQ -> d == k; GapRel.GE -> d >= k; GapRel.LE -> d <= k }
    }

    override fun equals(other: Any?): Boolean =
        other is Gap && other.a == a && other.b == b && other.k == k && other.rel == rel
    override fun hashCode(): Int = ((31 * a.hashCode() + b.hashCode()) * 31 + k) * 31 + rel.ordinal
    override fun toString(): String = "gap(${a.key}, ${b.key}, $k, ${rel.wire})"

    companion object {
        operator fun invoke(a: Ref, b: Ref, k: Int, rel: GapRel = GapRel.EQ): Gap {
            require(k >= 1) { "gap k must be >= 1, got $k" }
            return order(a, b).let { Gap(it.first, it.second, k, rel) }
        }
    }
}

class Parity private constructor(val a: Ref, val b: Ref, val same: Boolean) : Atom {
    override val kind: String get() = "parity"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        ((position(a) - position(b)) % 2 == 0) == same

    override fun equals(other: Any?): Boolean =
        other is Parity && other.a == a && other.b == b && other.same == same
    override fun hashCode(): Int = (31 * a.hashCode() + b.hashCode()) * 31 + same.hashCode()
    override fun toString(): String = "parity(${a.key}, ${b.key}, same=$same)"

    companion object {
        operator fun invoke(a: Ref, b: Ref, same: Boolean = true): Parity =
            order(a, b).let { Parity(it.first, it.second, same) }
    }
}

enum class ArithOp { ADD, MUL;
    companion object {
        fun parse(s: String): ArithOp = when (s.lowercase()) {
            "add" -> ADD; "mul" -> MUL
            else -> error("arith op must be add/mul, got '$s'")
        }
    }
    val wire: String get() = name.lowercase()
}

/** Numeric relation on spine values: `value(a) = value(b) <op> k`. */
data class Arith(val a: Ref, val b: Ref, val k: Double, val op: ArithOp) : Atom {
    override val kind: String get() = "arith"
    override fun refs(): List<Ref> = listOf(a, b)
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean {
        val values = schema.spine.values ?: return false
        val va = values[position(a)]
        val vb = values[position(b)]
        val target = if (op == ArithOp.ADD) vb + k else vb * k
        return kotlin.math.abs(va - target) < 1e-9
    }
}

// --- tier C: compound -----------------------------------------------------

data class Not(val inner: Atom) : Atom {
    override val kind: String get() = "not"
    override fun refs(): List<Ref> = inner.refs()
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        !inner.check(position, schema)
}

/** At least one option holds -- an inclusive or. */
data class Or(val options: List<Atom>) : Atom {
    init { require(options.size >= 2) { "or needs at least two options" } }
    override val kind: String get() = "or"
    override fun refs(): List<Ref> = options.flatMap { it.refs() }.distinct()
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        options.any { it.check(position, schema) }
}

/** At most one of [x] and [y] holds; neither is permitted. */
data class NotBoth(val x: Atom, val y: Atom) : Atom {
    override val kind: String get() = "not_both"
    override fun refs(): List<Ref> = (x.refs() + y.refs()).distinct()
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        !(x.check(position, schema) && y.check(position, schema))
}

/** Material implication. Asserts nothing when [x] is false, and not the converse. */
data class IfThen(val x: Atom, val y: Atom) : Atom {
    override val kind: String get() = "if_then"
    override fun refs(): List<Ref> = (x.refs() + y.refs()).distinct()
    override fun check(position: (Ref) -> Int, schema: Schema): Boolean =
        !x.check(position, schema) || y.check(position, schema)
}
