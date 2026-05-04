package dev.ratkay.operation.r4

import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Type
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional

/**
 * Utility object for retrieving FHIR extensions from any object that can carry them.
 *
 * In HAPI FHIR R4, objects that implement [IBaseHasExtensions] include:
 * - All primitive types (`StringType`, `BooleanType`, `DateType`, …) — extend `Element`
 * - All complex datatypes (`Coding`, `Reference`, `Identifier`, `Period`, …) — extend `Element`
 * - All FHIR resources (`Patient`, `Observation`, …) — `DomainResource` implements `IBaseHasExtensions`
 * - `Extension` itself — carries nested sub-extensions via its own `.extension` list
 *
 * ### Deep traversal
 * Every method searches **at every level** of the FHIR object graph, not just the top-level
 * `.extension` list of the source. The internal [collectDeepByUrl] helper recurses through all
 * FHIR children via [Base.children], mirroring the same traversal used by
 * [OperationResult.linkReferences].
 *
 * ### Java interop
 * Methods that return a nullable Kotlin type (`T?`) have a parallel `*Optional` variant that
 * returns [Optional] instead. Reified generic methods (`getValueAs`, `getAllValuesAs`) have
 * `Class<T>`-parameter overloads that are callable from Java without the inline/reified machinery.
 *
 * ### Usage
 * ```kotlin
 * val patient = Patient().apply {
 *     addName().apply { addExtension("http://example.com/nid", StringType("12345")) }
 * }
 * // Deep: finds the extension on the nested HumanName element
 * val nid: StringType? = FhirExtensionHelper.getValueAs<StringType>(patient, "http://example.com/nid")
 *
 * // Java-friendly Optional variant
 * val nidOpt: Optional<StringType> =
 *     FhirExtensionHelper.getValueAsOptional(patient, "http://example.com/nid", StringType::class.java)
 * ```
 */
object FhirExtensionHelper {

    // ── Presence check ────────────────────────────────────────────────────

    /**
     * Returns `true` if [source] or any of its FHIR descendants has at least one extension
     * whose URL equals [url].
     */
    fun hasExtension(source: IBaseHasExtensions, url: String): Boolean =
        getAllByUrl(source, url).isNotEmpty()

    // ── Single retrieval ──────────────────────────────────────────────────

    /**
     * Returns the first [Extension] matching [url] found at any depth in [source],
     * or `null` if none is present.
     */
    fun getByUrl(source: IBaseHasExtensions, url: String): Extension? =
        getAllByUrl(source, url).firstOrNull()

    /**
     * Returns the first [Extension] matching [url] found at any depth in [source],
     * wrapped in an [Optional]. Returns [Optional.empty] if none is present.
     *
     * Java callers may prefer this over [getByUrl].
     */
    fun getByUrlOptional(source: IBaseHasExtensions, url: String): Optional<Extension> =
        Optional.ofNullable(getByUrl(source, url))

    // ── All retrieval ─────────────────────────────────────────────────────

    /**
     * Returns all [Extension]s matching [url] found at any depth in [source].
     * Returns an empty list if none are present.
     */
    fun getAllByUrl(source: IBaseHasExtensions, url: String): List<Extension> {
        val results = mutableListOf<Extension>()
        val visited: MutableSet<Base> = Collections.newSetFromMap(IdentityHashMap())
        collectDeepByUrl(source as Base, url, results, visited)
        return results
    }

    // ── Typed value extraction ────────────────────────────────────────────

    /**
     * Returns the value of the first [Extension] matching [url] at any depth, cast to [T],
     * or `null` if:
     * - no extension with that URL exists, or
     * - the extension has no value, or
     * - the value is not an instance of [T].
     *
     * [T] must be a concrete FHIR R4 [Type] (`StringType`, `BooleanType`, `Coding`, …).
     */
    inline fun <reified T : Type> getValueAs(source: IBaseHasExtensions, url: String): T? =
        getByUrl(source, url)?.value as? T

    /**
     * Returns the value of the first [Extension] matching [url] at any depth, cast to [type],
     * wrapped in an [Optional]. Returns [Optional.empty] if absent or the wrong type.
     *
     * Java-friendly alternative to [getValueAs] — pass `StringType::class.java` instead of a
     * reified type parameter.
     */
    fun <T : Type> getValueAsOptional(
        source: IBaseHasExtensions,
        url: String,
        type: Class<T>
    ): Optional<T> {
        val value = getByUrl(source, url)?.value ?: return Optional.empty()
        return Optional.ofNullable(if (type.isInstance(value)) type.cast(value) else null)
    }

    /**
     * Returns the values of all [Extension]s matching [url] at any depth that are instances of [T].
     * Extensions that exist but carry a different value type (or no value) are silently skipped.
     */
    inline fun <reified T : Type> getAllValuesAs(source: IBaseHasExtensions, url: String): List<T> =
        getAllByUrl(source, url).mapNotNull { it.value as? T }

    /**
     * Java-friendly overload of [getAllValuesAs]: pass [type] as `StringType::class.java` instead
     * of a reified type parameter.
     *
     * The extra [type] parameter makes this overload unambiguous from the reified variant after
     * JVM type erasure — no `@JvmName` workaround is needed.
     */
    fun <T : Type> getAllValuesAs(
        source: IBaseHasExtensions,
        url: String,
        type: Class<T>
    ): List<T> = getAllByUrl(source, url).mapNotNull { ext ->
        val v = ext.value ?: return@mapNotNull null
        if (type.isInstance(v)) type.cast(v) else null
    }

    // ── Nested extension retrieval ────────────────────────────────────────

    /**
     * Returns the first *nested* extension found at any depth: locates the first [Extension]
     * matching [url] in [source], then returns the first sub-extension inside it whose URL
     * equals [nestedUrl].
     *
     * Returns `null` if the parent extension or the nested extension is absent.
     *
     * In FHIR, an [Extension] can itself carry child extensions in its own `.extension` list,
     * used when a complex structure is needed instead of a single `value[x]`.
     */
    fun getNested(source: IBaseHasExtensions, url: String, nestedUrl: String): Extension? =
        getByUrl(source, url)?.let { parent -> getByUrl(parent, nestedUrl) }

    /**
     * Returns the first nested extension (matching [url] → [nestedUrl]) at any depth,
     * wrapped in an [Optional]. Returns [Optional.empty] if absent.
     *
     * Java-friendly alternative to [getNested].
     */
    fun getNestedOptional(
        source: IBaseHasExtensions,
        url: String,
        nestedUrl: String
    ): Optional<Extension> = Optional.ofNullable(getNested(source, url, nestedUrl))

    /**
     * Returns all nested extensions inside the first [Extension] matching [url] at any depth
     * whose URL equals [nestedUrl]. Returns an empty list if the parent is absent.
     */
    fun getAllNested(source: IBaseHasExtensions, url: String, nestedUrl: String): List<Extension> {
        val parent = getByUrl(source, url) ?: return emptyList()
        return getAllByUrl(parent, nestedUrl)
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Recursively collects all [Extension]s matching [url] anywhere in the FHIR object graph
     * rooted at [node].
     *
     * Algorithm:
     * 1. Guard against object-graph cycles with an identity-based [visited] set.
     * 2. If [node] implements [IBaseHasExtensions], inspect its direct `.extension` list and
     *    add any whose URL equals [url].
     * 3. Recurse into all FHIR children exposed by [Base.children], which covers element
     *    properties, nested resources, and the extension list itself (allowing sub-extensions
     *    of a matched parent to also be visited).
     */
    private fun collectDeepByUrl(
        node: Base,
        url: String,
        results: MutableList<Extension>,
        visited: MutableSet<Base>
    ) {
        if (!visited.add(node)) return

        if (node is IBaseHasExtensions) {
            results.addAll(node.extension.filterIsInstance<Extension>().filter { it.url == url })
        }

        for (property in node.children()) {
            for (child in property.values) {
                collectDeepByUrl(child, url, results, visited)
            }
        }
    }
}

// ── Internal helpers used by OperationResult extension-filter methods ────────

/**
 * Filters [allValues] to instances of [type], then — when [extUrls] is non-empty — further
 * requires each instance to implement [IBaseHasExtensions] and carry the relevant URLs.
 *
 * [matchAll] = `true` (AND): every URL must be present.
 * [matchAll] = `false` (OR): at least one URL must be present.
 * When [extUrls] is empty all instances of [type] are returned regardless of [matchAll].
 */
internal fun <I : Base> filterByExtension(
    allValues: List<Base>,
    type: Class<I>,
    extUrls: Array<out String>,
    matchAll: Boolean
): List<I> {
    val allOfType = allValues.filterIsInstance(type)
    return if (extUrls.isEmpty()) allOfType
    else allOfType.filter { resource ->
        resource is IBaseHasExtensions && if (matchAll) {
            extUrls.all { url -> FhirExtensionHelper.hasExtension(resource, url) }
        } else {
            extUrls.any { url -> FhirExtensionHelper.hasExtension(resource, url) }
        }
    }
}

/**
 * Filters [allValues] to instances of [type] that have an extension at [url] with a value
 * of [valueType] satisfying [predicate].
 *
 * Resources that do not implement [IBaseHasExtensions], have no extension at [url], or whose
 * extension value is not an instance of [valueType] are silently excluded.
 */
internal fun <I : Base, V : Type> filterByExtensionAndValueType(
    allValues: List<Base>,
    type: Class<I>,
    url: String,
    valueType: Class<V>,
    predicate: (V) -> Boolean
): List<I> =
    allValues
        .filterIsInstance(type)
        .filter { resource ->
            resource is IBaseHasExtensions &&
                FhirExtensionHelper.getAllValuesAs(resource, url, valueType).any(predicate)
        }
