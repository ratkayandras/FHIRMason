package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.Extension
import org.hl7.fhir.dstu3.model.Type
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional

/**
 * Utility object for retrieving FHIR extensions from any object that can carry them.
 *
 * In HAPI FHIR DSTU3, objects that implement [IBaseHasExtensions] include all primitive
 * types, complex datatypes, and all FHIR resources.
 *
 * Every method searches at every level of the FHIR object graph via [Base.children].
 */
object FhirExtensionHelper {

    // ── Presence check ────────────────────────────────────────────────────

    fun hasExtension(source: IBaseHasExtensions, url: String): Boolean =
        getAllByUrl(source, url).isNotEmpty()

    // ── Single retrieval ──────────────────────────────────────────────────

    fun getByUrl(source: IBaseHasExtensions, url: String): Extension? =
        getAllByUrl(source, url).firstOrNull()

    fun getByUrlOptional(source: IBaseHasExtensions, url: String): Optional<Extension> =
        Optional.ofNullable(getByUrl(source, url))

    // ── All retrieval ─────────────────────────────────────────────────────

    fun getAllByUrl(source: IBaseHasExtensions, url: String): List<Extension> {
        val results = mutableListOf<Extension>()
        val visited: MutableSet<Base> = Collections.newSetFromMap(IdentityHashMap())
        collectDeepByUrl(source as Base, url, results, visited)
        return results
    }

    // ── Typed value extraction ────────────────────────────────────────────

    inline fun <reified T : Type> getValueAs(source: IBaseHasExtensions, url: String): T? =
        getByUrl(source, url)?.value as? T

    fun <T : Type> getValueAsOptional(
        source: IBaseHasExtensions,
        url: String,
        type: Class<T>
    ): Optional<T> {
        val value = getByUrl(source, url)?.value ?: return Optional.empty()
        return Optional.ofNullable(if (type.isInstance(value)) type.cast(value) else null)
    }

    inline fun <reified T : Type> getAllValuesAs(source: IBaseHasExtensions, url: String): List<T> =
        getAllByUrl(source, url).mapNotNull { it.value as? T }

    fun <T : Type> getAllValuesAs(
        source: IBaseHasExtensions,
        url: String,
        type: Class<T>
    ): List<T> = getAllByUrl(source, url).mapNotNull { ext ->
        val v = ext.value ?: return@mapNotNull null
        if (type.isInstance(v)) type.cast(v) else null
    }

    // ── Nested extension retrieval ────────────────────────────────────────

    fun getNested(source: IBaseHasExtensions, url: String, nestedUrl: String): Extension? =
        getByUrl(source, url)?.let { parent -> getByUrl(parent, nestedUrl) }

    fun getNestedOptional(
        source: IBaseHasExtensions,
        url: String,
        nestedUrl: String
    ): Optional<Extension> = Optional.ofNullable(getNested(source, url, nestedUrl))

    fun getAllNested(source: IBaseHasExtensions, url: String, nestedUrl: String): List<Extension> {
        val parent = getByUrl(source, url) ?: return emptyList()
        return getAllByUrl(parent, nestedUrl)
    }

    // ── Private helpers ───────────────────────────────────────────────────

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
