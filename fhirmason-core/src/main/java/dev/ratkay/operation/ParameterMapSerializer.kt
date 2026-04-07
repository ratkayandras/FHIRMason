package dev.ratkay.operation

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.Type
import java.util.IdentityHashMap

/**
 * Converts between a flat dot-delimited parameter map and a nested FHIR [Parameters] resource.
 *
 * **flatten** — `Parameters` → flat map (the *fromParameters* direction)
 *
 * Each top-level [Parameters.ParametersParameterComponent] is mapped to an entry in the returned
 * map using its name as the key.  Nested `part` entries are flattened recursively: a part named
 * `"city"` inside a parameter named `"address"` produces the key `"address.city"`.  Extensions
 * attached to value-type components are preserved in the returned extension map so that a
 * subsequent [unflatten] call can re-emit them.
 *
 * **unflatten** — flat map → `Parameters` (the *toParameters* direction)
 *
 * Keys that share a common dot-prefix are grouped under a single parent component; their
 * suffixes become `part` entries (recursively).  Leaf values are serialised as either a FHIR
 * primitive/complex [Type] or a [Resource].  Per-value extensions from the extension map are
 * re-attached to the matching leaf component.
 */
internal object ParameterMapSerializer {

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Flattens a nested FHIR [Parameters] resource into a dot-delimited parameter map and a
     * companion extension map.
     *
     * Returns a [Pair] of:
     * - `first`  — `MutableMap<String, MutableList<Base>>` — flat parameter entries
     * - `second` — `MutableMap<String, MutableMap<Base, List<Extension>>>` — per-value extensions,
     *   keyed by the same dot-delimited parameter key; the inner map uses object identity.
     */
    fun flatten(
        parameters: Parameters
    ): Pair<MutableMap<String, MutableList<Base>>, MutableMap<String, MutableMap<Base, List<Extension>>>> {
        val params = mutableMapOf<String, MutableList<Base>>()
        val exts = mutableMapOf<String, MutableMap<Base, List<Extension>>>()
        parameters.parameter.forEach { param -> populateFromParam(params, exts, param) }
        return params to exts
    }

    /**
     * Unflattens a dot-delimited parameter map into a nested FHIR [Parameters] resource.
     *
     * Keys that share a common prefix segment are grouped under a single parent component whose
     * children are the remaining suffix segments (recursively).  Leaf values are emitted as
     * [Type] (via `setValue`) or [Resource] (via `setResource`).
     *
     * @param map        the flat parameter map produced by the pipeline
     * @param extensions optional per-value extensions to re-attach to leaf components;
     *                   defaults to an empty map when absent
     */
    fun unflatten(
        map: Map<String, List<Base>>,
        extensions: Map<String, Map<Base, List<Extension>>> = emptyMap()
    ): Parameters = Parameters().apply {
        val root = this
        buildParameterComponents(map, extensions) { name ->
            root.addParameter().also { it.name = name }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Recursively builds [Parameters.ParametersParameterComponent] entries from [entries].
     *
     * [addComponent] abstracts the difference between adding to a [Parameters] root
     * (`parameters.addParameter()`) and adding to a parent part (`component.addPart()`),
     * so the same logic handles both levels.
     *
     * Algorithm:
     * - Collect all unique top-level segments (the portion of each key before the first dot),
     *   preserving map insertion order.
     * - For a segment whose values live directly under that key (no dot sub-keys):
     *   emit one component per value (leaf node).
     * - For a segment whose values all live under dot-qualified sub-keys:
     *   emit a single parent component with no value/resource, then recurse for its children.
     *
     * This means `"address.city"` and `"address.country"` together produce:
     * ```
     * name="address"
     *   part: name="city",    value=…
     *   part: name="country", value=…
     * ```
     */
    private fun buildParameterComponents(
        entries: Map<String, List<Base>>,
        extensions: Map<String, Map<Base, List<Extension>>>,
        keyPrefix: String = "",
        addComponent: (String) -> Parameters.ParametersParameterComponent
    ) {
        val topSegments = entries.keys.mapTo(linkedSetOf()) { it.substringBefore('.') }

        topSegments.forEach { segment ->
            val directValues = entries[segment]
            val childEntries: Map<String, List<Base>> = entries
                .filterKeys { it.startsWith("$segment.") }
                .mapKeys { (key, _) -> key.removePrefix("$segment.") }

            val fullKey = if (keyPrefix.isEmpty()) segment else "$keyPrefix.$segment"

            if (childEntries.isEmpty()) {
                directValues?.forEach { value ->
                    addComponent(segment).apply {
                        when (value) {
                            is Type     -> setValue(value)
                            is Resource -> setResource(value)
                        }
                        extensions[fullKey]?.get(value)?.forEach { ext -> addExtension(ext) }
                    }
                }
            } else {
                val parent = addComponent(segment)
                buildParameterComponents(childEntries, extensions, fullKey) { childName ->
                    parent.addPart().also { it.name = childName }
                }
            }
        }
    }

    /**
     * Recursively populates [params] and [exts] from a single [Parameters.ParametersParameterComponent].
     *
     * - Resource parameter  → stored under the composite key; component-level extensions are not
     *                         captured because the resource already carries its own extension list.
     * - Value parameter     → stored under the composite key; any component-level extensions are
     *                         preserved in [exts] so a subsequent [unflatten] call re-emits them.
     * - Parts-only entry    → each part is processed recursively with the current key as prefix.
     */
    private fun populateFromParam(
        params: MutableMap<String, MutableList<Base>>,
        exts: MutableMap<String, MutableMap<Base, List<Extension>>>,
        param: Parameters.ParametersParameterComponent,
        keyPrefix: String = ""
    ) {
        val key = if (keyPrefix.isEmpty()) param.name else "$keyPrefix.${param.name}"
        when {
            param.hasResource() -> params.getOrPut(key) { mutableListOf() }.add(param.resource)
            param.hasValue() -> {
                val value = param.value
                params.getOrPut(key) { mutableListOf() }.add(value)
                if (param.hasExtension()) {
                    val innerMap = exts.getOrPut(key) { IdentityHashMap() }
                    innerMap[value] = param.extension.toList()
                }
            }
            param.hasPart() -> param.part.forEach { part ->
                populateFromParam(params, exts, part, key)
            }
        }
    }
}
