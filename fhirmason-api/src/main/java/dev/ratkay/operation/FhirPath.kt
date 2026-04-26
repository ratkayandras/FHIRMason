package dev.ratkay.operation

/**
 * Fluent, immutable builder for FHIRPath expressions.
 *
 * Each step returns a new [FhirPath] instance; the accumulated expression string is retrieved
 * via [build] (or [toString]).
 *
 * ### Factory methods
 * - [FhirPath.from] — start from a named root segment (resource type or first field)
 * - [FhirPath.relative] — start with an empty base for condition/predicate expressions
 *
 * ### Kotlin usage
 * ```kotlin
 * // Absolute path
 * val path = FhirPath.from("Patient")
 *     .navigate("name")
 *     .where(FhirPath.relative().navigate("use").eq("official"))
 *     .first()
 *     .build()
 * // → "Patient.name.where(use = 'official').first()"
 *
 * // Relative condition for use inside where()
 * val isActive = FhirPath.relative().navigate("active").eq(true)
 * result.whenPath(isActive.build()) { ... }
 * ```
 *
 * ### Java usage
 * ```java
 * String path = FhirPath.from("Patient")
 *     .navigate("name")
 *     .where(FhirPath.relative().navigate("use").eq("official"))
 *     .first()
 *     .build();
 * ```
 */
class FhirPath private constructor(private val expr: String) {

    companion object {
        /** Starts a new expression rooted at [segment] (e.g. `"Patient"` or `"name"`). */
        @JvmStatic
        fun from(segment: String): FhirPath = FhirPath(segment)

        /** Starts an empty expression — use this to build condition strings for [where], [all], [exists], etc. */
        @JvmStatic
        fun relative(): FhirPath = FhirPath("")
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /** Appends a navigation step: `expr.segment`. Handles an empty base from [relative] correctly. */
    fun navigate(segment: String): FhirPath =
        if (expr.isEmpty()) FhirPath(segment) else FhirPath("$expr.$segment")

    /** Produces a union of this expression and [other]: `expr | other`. */
    fun union(other: FhirPath): FhirPath = FhirPath("$expr | ${other.build()}")

    /** Produces a union of this expression and the raw [other] string: `expr | other`. */
    fun union(other: String): FhirPath = FhirPath("$expr | $other")

    // ── Subsetting / filtering ────────────────────────────────────────────────

    /** Appends a `where` filter with a raw condition string: `expr.where(condition)`. */
    fun where(condition: String): FhirPath = FhirPath("$expr.where($condition)")

    /** Appends a `where` filter with a [FhirPath] condition: `expr.where(condition)`. */
    fun where(condition: FhirPath): FhirPath = FhirPath("$expr.where(${condition.build()})")

    /** Appends a `select` projection: `expr.select(projection)`. */
    fun select(projection: String): FhirPath = FhirPath("$expr.select($projection)")

    // ── Collection functions ──────────────────────────────────────────────────

    /** Returns the first item in the collection: `expr.first()`. */
    fun first(): FhirPath = FhirPath("$expr.first()")

    /** Returns the last item in the collection: `expr.last()`. */
    fun last(): FhirPath = FhirPath("$expr.last()")

    /** Returns all items except the first: `expr.tail()`. */
    fun tail(): FhirPath = FhirPath("$expr.tail()")

    /** Returns the first [n] items: `expr.take(n)`. */
    fun take(n: Int): FhirPath = FhirPath("$expr.take($n)")

    /** Skips the first [n] items and returns the rest: `expr.skip(n)`. */
    fun skip(n: Int): FhirPath = FhirPath("$expr.skip($n)")

    /** Returns the number of items in the collection: `expr.count()`. */
    fun count(): FhirPath = FhirPath("$expr.count()")

    /** Returns `true` when the collection contains no items: `expr.empty()`. */
    fun empty(): FhirPath = FhirPath("$expr.empty()")

    /** Returns `true` when the collection contains at least one item: `expr.exists()`. */
    fun exists(): FhirPath = FhirPath("$expr.exists()")

    /** Returns `true` when at least one item satisfies [criteria]: `expr.exists(criteria)`. */
    fun exists(criteria: String): FhirPath = FhirPath("$expr.exists($criteria)")

    /** [FhirPath] overload of [exists] — builds [criteria] and delegates. */
    fun exists(criteria: FhirPath): FhirPath = FhirPath("$expr.exists(${criteria.build()})")

    /** Returns `true` when every item satisfies [criteria]: `expr.all(criteria)`. */
    fun all(criteria: String): FhirPath = FhirPath("$expr.all($criteria)")

    /** [FhirPath] overload of [all] — builds [criteria] and delegates. */
    fun all(criteria: FhirPath): FhirPath = FhirPath("$expr.all(${criteria.build()})")

    /** Returns `true` when all items are `true`: `expr.allTrue()`. */
    fun allTrue(): FhirPath = FhirPath("$expr.allTrue()")

    /** Returns `true` when at least one item is `true`: `expr.anyTrue()`. */
    fun anyTrue(): FhirPath = FhirPath("$expr.anyTrue()")

    /** Returns `true` when all items are `false`: `expr.allFalse()`. */
    fun allFalse(): FhirPath = FhirPath("$expr.allFalse()")

    /** Returns `true` when at least one item is `false`: `expr.anyFalse()`. */
    fun anyFalse(): FhirPath = FhirPath("$expr.anyFalse()")

    /** Removes duplicate items from the collection: `expr.distinct()`. */
    fun distinct(): FhirPath = FhirPath("$expr.distinct()")

    /** Returns `true` when the collection has no duplicates: `expr.isDistinct()`. */
    fun isDistinct(): FhirPath = FhirPath("$expr.isDistinct()")

    /** Returns `true` when this collection is a superset of [other]: `expr.supersetOf(other)`. */
    fun supersetOf(other: String): FhirPath = FhirPath("$expr.supersetOf($other)")

    /** Returns the direct child nodes of each item: `expr.children()`. */
    fun children(): FhirPath = FhirPath("$expr.children()")

    /** Returns all descendant nodes recursively: `expr.descendants()`. */
    fun descendants(): FhirPath = FhirPath("$expr.descendants()")

    // ── Boolean operators ─────────────────────────────────────────────────────

    /** Logical negation: `expr.not()`. */
    fun not(): FhirPath = FhirPath("$expr.not()")

    /** Logical AND with a [FhirPath] operand: `(expr) and (other)`. */
    fun and(other: FhirPath): FhirPath = FhirPath("($expr) and (${other.build()})")

    /** Logical AND with a raw string operand: `(expr) and (other)`. */
    fun and(other: String): FhirPath = FhirPath("($expr) and ($other)")

    /** Logical OR with a [FhirPath] operand: `(expr) or (other)`. */
    fun or(other: FhirPath): FhirPath = FhirPath("($expr) or (${other.build()})")

    /** Logical OR with a raw string operand: `(expr) or (other)`. */
    fun or(other: String): FhirPath = FhirPath("($expr) or ($other)")

    /** Logical XOR with a [FhirPath] operand: `(expr) xor (other)`. */
    fun xor(other: FhirPath): FhirPath = FhirPath("($expr) xor (${other.build()})")

    /** Logical XOR with a raw string operand: `(expr) xor (other)`. */
    fun xor(other: String): FhirPath = FhirPath("($expr) xor ($other)")

    /** Logical implication with a [FhirPath] operand: `(expr) implies (other)`. */
    fun implies(other: FhirPath): FhirPath = FhirPath("($expr) implies (${other.build()})")

    /** Logical implication with a raw string operand: `(expr) implies (other)`. */
    fun implies(other: String): FhirPath = FhirPath("($expr) implies ($other)")

    // ── Equality / comparison operators ──────────────────────────────────────
    //
    // String overloads auto-quote: eq("official") → = 'official'
    // Typed overloads emit the literal directly: eq(true) → = true, eq(18) → = 18

    /**
     * Equality comparison (`=`). String values are auto-quoted: `eq("official")` → `= 'official'`.
     * Numeric and boolean values are emitted as literals: `eq(18)` → `= 18`.
     */
    fun eq(value: String): FhirPath = FhirPath("$expr = '$value'")

    /** Equality comparison with an integer literal: `expr = value`. */
    fun eq(value: Int): FhirPath = FhirPath("$expr = $value")

    /** Equality comparison with a decimal literal: `expr = value`. */
    fun eq(value: Double): FhirPath = FhirPath("$expr = $value")

    /** Equality comparison with a boolean literal: `expr = value`. */
    fun eq(value: Boolean): FhirPath = FhirPath("$expr = $value")

    /** Inequality comparison (`!=`). String values are auto-quoted. */
    fun ne(value: String): FhirPath = FhirPath("$expr != '$value'")

    /** Inequality comparison with an integer literal: `expr != value`. */
    fun ne(value: Int): FhirPath = FhirPath("$expr != $value")

    /** Inequality comparison with a decimal literal: `expr != value`. */
    fun ne(value: Double): FhirPath = FhirPath("$expr != $value")

    /** Inequality comparison with a boolean literal: `expr != value`. */
    fun ne(value: Boolean): FhirPath = FhirPath("$expr != $value")

    /** Less-than comparison (`<`). String values are auto-quoted. */
    fun lt(value: String): FhirPath = FhirPath("$expr < '$value'")

    /** Less-than comparison with an integer literal: `expr < value`. */
    fun lt(value: Int): FhirPath = FhirPath("$expr < $value")

    /** Less-than comparison with a decimal literal: `expr < value`. */
    fun lt(value: Double): FhirPath = FhirPath("$expr < $value")

    /** Greater-than comparison (`>`). String values are auto-quoted. */
    fun gt(value: String): FhirPath = FhirPath("$expr > '$value'")

    /** Greater-than comparison with an integer literal: `expr > value`. */
    fun gt(value: Int): FhirPath = FhirPath("$expr > $value")

    /** Greater-than comparison with a decimal literal: `expr > value`. */
    fun gt(value: Double): FhirPath = FhirPath("$expr > $value")

    /** Less-than-or-equal comparison (`<=`). String values are auto-quoted. */
    fun le(value: String): FhirPath = FhirPath("$expr <= '$value'")

    /** Less-than-or-equal comparison with an integer literal: `expr <= value`. */
    fun le(value: Int): FhirPath = FhirPath("$expr <= $value")

    /** Less-than-or-equal comparison with a decimal literal: `expr <= value`. */
    fun le(value: Double): FhirPath = FhirPath("$expr <= $value")

    /** Greater-than-or-equal comparison (`>=`). String values are auto-quoted. */
    fun ge(value: String): FhirPath = FhirPath("$expr >= '$value'")

    /** Greater-than-or-equal comparison with an integer literal: `expr >= value`. */
    fun ge(value: Int): FhirPath = FhirPath("$expr >= $value")

    /** Greater-than-or-equal comparison with a decimal literal: `expr >= value`. */
    fun ge(value: Double): FhirPath = FhirPath("$expr >= $value")

    /** FHIRPath equivalence (`~`): matches regardless of insignificant whitespace, case, etc. */
    fun equiv(value: String): FhirPath = FhirPath("$expr ~ '$value'")
    fun equiv(value: Int): FhirPath = FhirPath("$expr ~ $value")
    fun equiv(value: Double): FhirPath = FhirPath("$expr ~ $value")

    /** FHIRPath non-equivalence (`!~`). */
    fun notEquiv(value: String): FhirPath = FhirPath("$expr !~ '$value'")
    fun notEquiv(value: Int): FhirPath = FhirPath("$expr !~ $value")
    fun notEquiv(value: Double): FhirPath = FhirPath("$expr !~ $value")

    /** FHIRPath `contains` membership: `expr contains value`. [value] is auto-quoted as a string literal. */
    fun containsValue(value: String): FhirPath = FhirPath("$expr contains '$value'")

    // ── String functions ──────────────────────────────────────────────────────
    //
    // All string-parameter functions auto-quote: startsWith("Sm") → .startsWith('Sm')

    /** Returns the number of characters in the string: `expr.length()`. */
    fun length(): FhirPath = FhirPath("$expr.length()")

    /** Converts the string to upper case: `expr.upper()`. */
    fun upper(): FhirPath = FhirPath("$expr.upper()")

    /** Converts the string to lower case: `expr.lower()`. */
    fun lower(): FhirPath = FhirPath("$expr.lower()")

    /** Removes leading and trailing whitespace: `expr.trim()`. */
    fun trim(): FhirPath = FhirPath("$expr.trim()")

    /** Returns `true` when the string starts with [prefix]: `expr.startsWith('prefix')`. */
    fun startsWith(prefix: String): FhirPath = FhirPath("$expr.startsWith('$prefix')")

    /** Returns `true` when the string ends with [suffix]: `expr.endsWith('suffix')`. */
    fun endsWith(suffix: String): FhirPath = FhirPath("$expr.endsWith('$suffix')")

    /** Returns `true` when the string contains [substring]: `expr.contains('substring')`. */
    fun contains(substring: String): FhirPath = FhirPath("$expr.contains('$substring')")

    /** Returns `true` when the string matches [regex]: `expr.matches('regex')`. */
    fun matches(regex: String): FhirPath = FhirPath("$expr.matches('$regex')")

    /** Returns the zero-based index of [substring] in the string: `expr.indexOf('substring')`. */
    fun indexOf(substring: String): FhirPath = FhirPath("$expr.indexOf('$substring')")

    /** Returns the substring starting at index [start]: `expr.substring(start)`. */
    fun substring(start: Int): FhirPath = FhirPath("$expr.substring($start)")

    /** Returns [length] characters of the substring starting at index [start]: `expr.substring(start, length)`. */
    fun substring(start: Int, length: Int): FhirPath = FhirPath("$expr.substring($start, $length)")

    /** Replaces all occurrences of [pattern] with [substitution]: `expr.replace('pattern', 'substitution')`. */
    fun replace(pattern: String, substitution: String): FhirPath = FhirPath("$expr.replace('$pattern', '$substitution')")

    /** Replaces substrings matching [regex] with [substitution]: `expr.replaceMatches('regex', 'substitution')`. */
    fun replaceMatches(regex: String, substitution: String): FhirPath = FhirPath("$expr.replaceMatches('$regex', '$substitution')")

    /** Splits the string on [separator] and returns a collection: `expr.split('separator')`. */
    fun split(separator: String): FhirPath = FhirPath("$expr.split('$separator')")

    /** Joins the items of a string collection using [separator]: `expr.join('separator')`. */
    fun join(separator: String): FhirPath = FhirPath("$expr.join('$separator')")

    // ── Math functions ────────────────────────────────────────────────────────

    /** Returns the absolute value: `expr.abs()`. */
    fun abs(): FhirPath = FhirPath("$expr.abs()")

    /** Returns the smallest integer greater than or equal to the value: `expr.ceiling()`. */
    fun ceiling(): FhirPath = FhirPath("$expr.ceiling()")

    /** Returns the largest integer less than or equal to the value: `expr.floor()`. */
    fun floor(): FhirPath = FhirPath("$expr.floor()")

    /** Rounds the value to the nearest integer: `expr.round()`. */
    fun round(): FhirPath = FhirPath("$expr.round()")

    /** Rounds the value to [precision] decimal places: `expr.round(precision)`. */
    fun round(precision: Int): FhirPath = FhirPath("$expr.round($precision)")

    /** Returns the square root of the value: `expr.sqrt()`. */
    fun sqrt(): FhirPath = FhirPath("$expr.sqrt()")

    /** Raises the value to the power of [exp]: `expr.power(exp)`. */
    fun power(exp: String): FhirPath = FhirPath("$expr.power($exp)")

    /** Truncates the decimal part, returning the integer portion: `expr.truncate()`. */
    fun truncate(): FhirPath = FhirPath("$expr.truncate()")

    // ── Arithmetic operators ──────────────────────────────────────────────────

    /** Addition: `expr + value`. */
    fun plus(value: String): FhirPath = FhirPath("$expr + $value")

    /** Subtraction: `expr - value`. */
    fun minus(value: String): FhirPath = FhirPath("$expr - $value")

    /** Multiplication: `expr * value`. */
    fun times(value: String): FhirPath = FhirPath("$expr * $value")

    /** Decimal division: `expr / value`. */
    fun dividedBy(value: String): FhirPath = FhirPath("$expr / $value")

    /** Integer division: `expr div value`. */
    fun div(value: String): FhirPath = FhirPath("$expr div $value")

    /** Modulo (remainder): `expr mod value`. */
    fun mod(value: String): FhirPath = FhirPath("$expr mod $value")

    /** FHIRPath string concatenation (`&`): `expr & value`. */
    fun concat(value: String): FhirPath = FhirPath("$expr & $value")

    // ── Type conversion ───────────────────────────────────────────────────────

    /** Converts the value to a Boolean: `expr.toBoolean()`. */
    fun toBoolean(): FhirPath = FhirPath("$expr.toBoolean()")

    /** Converts the value to an Integer: `expr.toInteger()`. */
    fun toInteger(): FhirPath = FhirPath("$expr.toInteger()")

    /** Converts the value to a Decimal: `expr.toDecimal()`. */
    fun toDecimal(): FhirPath = FhirPath("$expr.toDecimal()")

    /** Converts the value to a Quantity: `expr.toQuantity()`. */
    fun toQuantity(): FhirPath = FhirPath("$expr.toQuantity()")

    // ── FHIR-specific ─────────────────────────────────────────────────────────

    /** Navigates to the extension with [url]: `expr.extension('url')`. */
    fun extension(url: String): FhirPath = FhirPath("$expr.extension('$url')")

    /** Resolves a reference to its target resource: `expr.resolve()`. */
    fun resolve(): FhirPath = FhirPath("$expr.resolve()")

    // ── Terminal ──────────────────────────────────────────────────────────────

    /** Returns the accumulated FHIRPath expression string. */
    fun build(): String = expr

    override fun toString(): String = expr
}
