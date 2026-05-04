package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.Type
import kotlin.reflect.KClass

/**
 * Predicate factories for use with [OperationResult.addFromFiltered] and
 * [OperationResult.addAllFromFiltered].
 *
 * Each factory returns a `(Base) -> Boolean` that can be passed directly as the
 * `predicate` argument.  Because function types are contravariant in their parameter,
 * a `(Base) -> Boolean` is assignable to `(I) -> Boolean` for any `I : Base`.
 *
 * Predicates are composable with standard Kotlin lambdas:
 * ```kotlin
 * val p = { r: Base -> FhirFilter.hasAllExtensions("url1")(r) && FhirFilter.hasMetaProfile("url2")(r) }
 * result.addFromFiltered(Patient::class, p) { patients -> ... }
 * ```
 */
object FhirFilter {

    // ── Extension predicates ──────────────────────────────────────────────────

    /** Returns `true` when the resource has **all** of the given extension URLs. */
    @JvmStatic
    fun hasAllExtensions(vararg urls: String): (Base) -> Boolean =
        { filterByExtension(listOf(it), Base::class.java, urls, matchAll = true).isNotEmpty() }

    /** Returns `true` when the resource has **at least one** of the given extension URLs. */
    @JvmStatic
    fun hasAnyExtension(vararg urls: String): (Base) -> Boolean =
        { filterByExtension(listOf(it), Base::class.java, urls, matchAll = false).isNotEmpty() }

    /**
     * Returns `true` when the resource has an extension at [url] whose value is an
     * instance of [valueType].
     *
     * @param url the extension URL to match
     * @param valueType the expected Java class of the extension value
     */
    @JvmStatic
    fun <V : Type> hasExtensionWithValueType(url: String, valueType: Class<V>): (Base) -> Boolean =
        { filterByExtensionAndValueType(listOf(it), Base::class.java, url, valueType) { true }.isNotEmpty() }

    /** Reified overload — no [Class] argument needed at call sites. */
    inline fun <reified V : Type> hasExtensionWithValueType(url: String): (Base) -> Boolean =
        hasExtensionWithValueType(url, V::class.java)

    /** [KClass] overload — Kotlin callers may pass [KClass] directly. */
    fun <V : Type> hasExtensionWithValueType(url: String, valueType: KClass<V>): (Base) -> Boolean =
        hasExtensionWithValueType(url, valueType.java)

    /**
     * Returns `true` when the resource has an extension at [url] whose value is an instance
     * of [valueType] and satisfies [predicate].
     *
     * @param url the extension URL to match
     * @param valueType the expected Java class of the extension value
     * @param predicate additional condition the value must satisfy
     */
    @JvmStatic
    fun <V : Type> hasExtensionValueMatching(
        url: String,
        valueType: Class<V>,
        predicate: (V) -> Boolean
    ): (Base) -> Boolean =
        hasExtensionValueMatchingInternal(url, valueType, predicate)

    /**
     * Reified, fully type-safe overload of [hasExtensionValueMatching].
     * Delegates via [hasExtensionValueMatchingInternal] to keep the `internal`
     * helper out of the inlined bytecode.
     */
    inline fun <reified V : Type> hasExtensionValueMatching(
        url: String,
        noinline predicate: (V) -> Boolean
    ): (Base) -> Boolean =
        hasExtensionValueMatchingInternal(url, V::class.java, predicate)

    @PublishedApi
    @JvmSynthetic
    internal fun <V : Type> hasExtensionValueMatchingInternal(
        url: String,
        valueType: Class<V>,
        predicate: (V) -> Boolean
    ): (Base) -> Boolean =
        { filterByExtensionAndValueType(listOf(it), Base::class.java, url, valueType, predicate).isNotEmpty() }

    // ── Identifier predicates ─────────────────────────────────────────────────

    /** Returns `true` when the resource has an identifier with [system]. */
    @JvmStatic
    fun hasIdentifierWithSystem(system: String): (Base) -> Boolean =
        { filterByIdentifierSystem(listOf(it), Base::class.java, system).isNotEmpty() }

    /** Returns `true` when the resource has an identifier with [identifierValue]. */
    @JvmStatic
    fun hasIdentifierWithValue(identifierValue: String): (Base) -> Boolean =
        { filterByIdentifierValue(listOf(it), Base::class.java, identifierValue).isNotEmpty() }

    /** Returns `true` when the resource has an identifier matching both [system] and [identifierValue]. */
    @JvmStatic
    fun hasIdentifier(system: String, identifierValue: String): (Base) -> Boolean =
        { filterByIdentifier(listOf(it), Base::class.java, system, identifierValue).isNotEmpty() }

    // ── Meta tag predicates ───────────────────────────────────────────────────

    /** Returns `true` when the resource has a meta.tag with [system]. */
    @JvmStatic
    fun hasMetaTagWithSystem(system: String): (Base) -> Boolean =
        { filterByMetaTagSystem(listOf(it), Base::class.java, system).isNotEmpty() }

    /** Returns `true` when the resource has a meta.tag with [code]. */
    @JvmStatic
    fun hasMetaTagWithCode(code: String): (Base) -> Boolean =
        { filterByMetaTagCode(listOf(it), Base::class.java, code).isNotEmpty() }

    /** Returns `true` when the resource has a meta.tag matching both [system] and [code]. */
    @JvmStatic
    fun hasMetaTag(system: String, code: String): (Base) -> Boolean =
        { filterByMetaTag(listOf(it), Base::class.java, system, code).isNotEmpty() }

    // ── Meta security predicates ──────────────────────────────────────────────

    /** Returns `true` when the resource has a meta.security with [system]. */
    @JvmStatic
    fun hasMetaSecurityWithSystem(system: String): (Base) -> Boolean =
        { filterByMetaSecuritySystem(listOf(it), Base::class.java, system).isNotEmpty() }

    /** Returns `true` when the resource has a meta.security with [code]. */
    @JvmStatic
    fun hasMetaSecurityWithCode(code: String): (Base) -> Boolean =
        { filterByMetaSecurityCode(listOf(it), Base::class.java, code).isNotEmpty() }

    /** Returns `true` when the resource has a meta.security matching both [system] and [code]. */
    @JvmStatic
    fun hasMetaSecurity(system: String, code: String): (Base) -> Boolean =
        { filterByMetaSecurity(listOf(it), Base::class.java, system, code).isNotEmpty() }

    // ── Combinators ───────────────────────────────────────────────────────────

    /** Returns a predicate that is `true` when **all** of the given predicates are satisfied. */
    @JvmStatic
    fun and(vararg predicates: (Base) -> Boolean): (Base) -> Boolean =
        { r -> predicates.all { it(r) } }

    /** Returns a predicate that is `true` when **at least one** of the given predicates is satisfied. */
    @JvmStatic
    fun or(vararg predicates: (Base) -> Boolean): (Base) -> Boolean =
        { r -> predicates.any { it(r) } }

    /** Returns a predicate that is `true` when the given predicate is **not** satisfied. */
    @JvmStatic
    fun not(predicate: (Base) -> Boolean): (Base) -> Boolean =
        { r -> !predicate(r) }

    // ── Meta profile predicate ────────────────────────────────────────────────

    /** Returns `true` when the resource has the given [url] in meta.profile. */
    @JvmStatic
    fun hasMetaProfile(url: String): (Base) -> Boolean =
        { filterByMetaProfile(listOf(it), Base::class.java, url).isNotEmpty() }
}
