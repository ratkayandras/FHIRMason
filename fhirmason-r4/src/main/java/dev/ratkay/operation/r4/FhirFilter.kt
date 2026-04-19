package dev.ratkay.operation.r4

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Type
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
        { filterByExtension(listOf(it), Base::class, urls, matchAll = true).isNotEmpty() }

    /** Returns `true` when the resource has **at least one** of the given extension URLs. */
    @JvmStatic
    fun hasAnyExtension(vararg urls: String): (Base) -> Boolean =
        { filterByExtension(listOf(it), Base::class, urls, matchAll = false).isNotEmpty() }

    /**
     * Returns `true` when the resource has an extension at [url] whose value is an
     * instance of [valueType].
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun hasExtensionWithValueType(url: String, valueType: KClass<out Type>): (Base) -> Boolean {
        val typed = valueType as KClass<Type>
        return { filterByExtensionAndValueType(listOf(it), Base::class, url, typed) { true }.isNotEmpty() }
    }

    /** Reified overload of [hasExtensionWithValueType]. */
    inline fun <reified V : Type> hasExtensionWithValueType(url: String): (Base) -> Boolean =
        hasExtensionWithValueType(url, V::class)

    /**
     * Returns `true` when the resource has an extension at [url] whose value is an instance
     * of [valueType] and satisfies [predicate].
     *
     * Java callers: the [predicate] receives the value cast to [valueType]; use the
     * reified overload from Kotlin for full type safety.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun hasExtensionValueMatching(
        url: String,
        valueType: KClass<out Type>,
        predicate: (Type) -> Boolean
    ): (Base) -> Boolean {
        val typed = valueType as KClass<Type>
        return { filterByExtensionAndValueType(listOf(it), Base::class, url, typed, predicate).isNotEmpty() }
    }

    /**
     * Reified, fully type-safe overload of [hasExtensionValueMatching].
     * Delegates via [hasExtensionValueMatchingInternal] to keep the `internal`
     * helper out of the inlined bytecode.
     */
    inline fun <reified V : Type> hasExtensionValueMatching(
        url: String,
        noinline predicate: (V) -> Boolean
    ): (Base) -> Boolean =
        hasExtensionValueMatchingInternal(url, V::class, predicate)

    @PublishedApi
    @JvmSynthetic
    internal fun <V : Type> hasExtensionValueMatchingInternal(
        url: String,
        valueType: KClass<V>,
        predicate: (V) -> Boolean
    ): (Base) -> Boolean =
        { filterByExtensionAndValueType(listOf(it), Base::class, url, valueType, predicate).isNotEmpty() }

    // ── Identifier predicates ─────────────────────────────────────────────────

    /** Returns `true` when the resource has an identifier with [system]. */
    @JvmStatic
    fun hasIdentifierWithSystem(system: String): (Base) -> Boolean =
        { filterByIdentifierSystem(listOf(it), Base::class, system).isNotEmpty() }

    /** Returns `true` when the resource has an identifier with [identifierValue]. */
    @JvmStatic
    fun hasIdentifierWithValue(identifierValue: String): (Base) -> Boolean =
        { filterByIdentifierValue(listOf(it), Base::class, identifierValue).isNotEmpty() }

    /** Returns `true` when the resource has an identifier matching both [system] and [identifierValue]. */
    @JvmStatic
    fun hasIdentifier(system: String, identifierValue: String): (Base) -> Boolean =
        { filterByIdentifier(listOf(it), Base::class, system, identifierValue).isNotEmpty() }

    // ── Meta tag predicates ───────────────────────────────────────────────────

    /** Returns `true` when the resource has a meta.tag with [system]. */
    @JvmStatic
    fun hasMetaTagWithSystem(system: String): (Base) -> Boolean =
        { filterByMetaTagSystem(listOf(it), Base::class, system).isNotEmpty() }

    /** Returns `true` when the resource has a meta.tag with [code]. */
    @JvmStatic
    fun hasMetaTagWithCode(code: String): (Base) -> Boolean =
        { filterByMetaTagCode(listOf(it), Base::class, code).isNotEmpty() }

    /** Returns `true` when the resource has a meta.tag matching both [system] and [code]. */
    @JvmStatic
    fun hasMetaTag(system: String, code: String): (Base) -> Boolean =
        { filterByMetaTag(listOf(it), Base::class, system, code).isNotEmpty() }

    // ── Meta security predicates ──────────────────────────────────────────────

    /** Returns `true` when the resource has a meta.security with [system]. */
    @JvmStatic
    fun hasMetaSecurityWithSystem(system: String): (Base) -> Boolean =
        { filterByMetaSecuritySystem(listOf(it), Base::class, system).isNotEmpty() }

    /** Returns `true` when the resource has a meta.security with [code]. */
    @JvmStatic
    fun hasMetaSecurityWithCode(code: String): (Base) -> Boolean =
        { filterByMetaSecurityCode(listOf(it), Base::class, code).isNotEmpty() }

    /** Returns `true` when the resource has a meta.security matching both [system] and [code]. */
    @JvmStatic
    fun hasMetaSecurity(system: String, code: String): (Base) -> Boolean =
        { filterByMetaSecurity(listOf(it), Base::class, system, code).isNotEmpty() }

    // ── Meta profile predicate ────────────────────────────────────────────────

    /** Returns `true` when the resource has the given [url] in meta.profile. */
    @JvmStatic
    fun hasMetaProfile(url: String): (Base) -> Boolean =
        { filterByMetaProfile(listOf(it), Base::class, url).isNotEmpty() }
}
