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

    fun first(): FhirPath = FhirPath("$expr.first()")
    fun last(): FhirPath = FhirPath("$expr.last()")
    fun tail(): FhirPath = FhirPath("$expr.tail()")
    fun take(n: Int): FhirPath = FhirPath("$expr.take($n)")
    fun skip(n: Int): FhirPath = FhirPath("$expr.skip($n)")
    fun count(): FhirPath = FhirPath("$expr.count()")
    fun empty(): FhirPath = FhirPath("$expr.empty()")
    fun exists(): FhirPath = FhirPath("$expr.exists()")
    fun exists(criteria: String): FhirPath = FhirPath("$expr.exists($criteria)")
    fun exists(criteria: FhirPath): FhirPath = FhirPath("$expr.exists(${criteria.build()})")
    fun all(criteria: String): FhirPath = FhirPath("$expr.all($criteria)")
    fun all(criteria: FhirPath): FhirPath = FhirPath("$expr.all(${criteria.build()})")
    fun allTrue(): FhirPath = FhirPath("$expr.allTrue()")
    fun anyTrue(): FhirPath = FhirPath("$expr.anyTrue()")
    fun allFalse(): FhirPath = FhirPath("$expr.allFalse()")
    fun anyFalse(): FhirPath = FhirPath("$expr.anyFalse()")
    fun distinct(): FhirPath = FhirPath("$expr.distinct()")
    fun isDistinct(): FhirPath = FhirPath("$expr.isDistinct()")
    fun supersetOf(other: String): FhirPath = FhirPath("$expr.supersetOf($other)")
    fun children(): FhirPath = FhirPath("$expr.children()")
    fun descendants(): FhirPath = FhirPath("$expr.descendants()")

    // ── Boolean operators ─────────────────────────────────────────────────────

    fun not(): FhirPath = FhirPath("$expr.not()")
    fun and(other: FhirPath): FhirPath = FhirPath("($expr) and (${other.build()})")
    fun and(other: String): FhirPath = FhirPath("($expr) and ($other)")
    fun or(other: FhirPath): FhirPath = FhirPath("($expr) or (${other.build()})")
    fun or(other: String): FhirPath = FhirPath("($expr) or ($other)")
    fun xor(other: FhirPath): FhirPath = FhirPath("($expr) xor (${other.build()})")
    fun xor(other: String): FhirPath = FhirPath("($expr) xor ($other)")
    fun implies(other: FhirPath): FhirPath = FhirPath("($expr) implies (${other.build()})")
    fun implies(other: String): FhirPath = FhirPath("($expr) implies ($other)")

    // ── Equality / comparison operators ──────────────────────────────────────
    //
    // String overloads auto-quote: eq("official") → = 'official'
    // Typed overloads emit the literal directly: eq(true) → = true, eq(18) → = 18

    fun eq(value: String): FhirPath = FhirPath("$expr = '$value'")
    fun eq(value: Int): FhirPath = FhirPath("$expr = $value")
    fun eq(value: Double): FhirPath = FhirPath("$expr = $value")
    fun eq(value: Boolean): FhirPath = FhirPath("$expr = $value")

    fun ne(value: String): FhirPath = FhirPath("$expr != '$value'")
    fun ne(value: Int): FhirPath = FhirPath("$expr != $value")
    fun ne(value: Double): FhirPath = FhirPath("$expr != $value")
    fun ne(value: Boolean): FhirPath = FhirPath("$expr != $value")

    fun lt(value: String): FhirPath = FhirPath("$expr < '$value'")
    fun lt(value: Int): FhirPath = FhirPath("$expr < $value")
    fun lt(value: Double): FhirPath = FhirPath("$expr < $value")

    fun gt(value: String): FhirPath = FhirPath("$expr > '$value'")
    fun gt(value: Int): FhirPath = FhirPath("$expr > $value")
    fun gt(value: Double): FhirPath = FhirPath("$expr > $value")

    fun le(value: String): FhirPath = FhirPath("$expr <= '$value'")
    fun le(value: Int): FhirPath = FhirPath("$expr <= $value")
    fun le(value: Double): FhirPath = FhirPath("$expr <= $value")

    fun ge(value: String): FhirPath = FhirPath("$expr >= '$value'")
    fun ge(value: Int): FhirPath = FhirPath("$expr >= $value")
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

    fun length(): FhirPath = FhirPath("$expr.length()")
    fun upper(): FhirPath = FhirPath("$expr.upper()")
    fun lower(): FhirPath = FhirPath("$expr.lower()")
    fun trim(): FhirPath = FhirPath("$expr.trim()")
    fun startsWith(prefix: String): FhirPath = FhirPath("$expr.startsWith('$prefix')")
    fun endsWith(suffix: String): FhirPath = FhirPath("$expr.endsWith('$suffix')")
    fun contains(substring: String): FhirPath = FhirPath("$expr.contains('$substring')")
    fun matches(regex: String): FhirPath = FhirPath("$expr.matches('$regex')")
    fun indexOf(substring: String): FhirPath = FhirPath("$expr.indexOf('$substring')")
    fun substring(start: Int): FhirPath = FhirPath("$expr.substring($start)")
    fun substring(start: Int, length: Int): FhirPath = FhirPath("$expr.substring($start, $length)")
    fun replace(pattern: String, substitution: String): FhirPath = FhirPath("$expr.replace('$pattern', '$substitution')")
    fun replaceMatches(regex: String, substitution: String): FhirPath = FhirPath("$expr.replaceMatches('$regex', '$substitution')")
    fun split(separator: String): FhirPath = FhirPath("$expr.split('$separator')")
    fun join(separator: String): FhirPath = FhirPath("$expr.join('$separator')")

    // ── Math functions ────────────────────────────────────────────────────────

    fun abs(): FhirPath = FhirPath("$expr.abs()")
    fun ceiling(): FhirPath = FhirPath("$expr.ceiling()")
    fun floor(): FhirPath = FhirPath("$expr.floor()")
    fun round(): FhirPath = FhirPath("$expr.round()")
    fun round(precision: Int): FhirPath = FhirPath("$expr.round($precision)")
    fun sqrt(): FhirPath = FhirPath("$expr.sqrt()")
    fun power(exp: String): FhirPath = FhirPath("$expr.power($exp)")
    fun truncate(): FhirPath = FhirPath("$expr.truncate()")

    // ── Arithmetic operators ──────────────────────────────────────────────────

    fun plus(value: String): FhirPath = FhirPath("$expr + $value")
    fun minus(value: String): FhirPath = FhirPath("$expr - $value")
    fun times(value: String): FhirPath = FhirPath("$expr * $value")
    fun dividedBy(value: String): FhirPath = FhirPath("$expr / $value")
    fun div(value: String): FhirPath = FhirPath("$expr div $value")
    fun mod(value: String): FhirPath = FhirPath("$expr mod $value")

    /** FHIRPath string concatenation (`&`): `expr & value`. */
    fun concat(value: String): FhirPath = FhirPath("$expr & $value")

    // ── Type conversion ───────────────────────────────────────────────────────

    fun toBoolean(): FhirPath = FhirPath("$expr.toBoolean()")
    fun toInteger(): FhirPath = FhirPath("$expr.toInteger()")
    fun toDecimal(): FhirPath = FhirPath("$expr.toDecimal()")
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
