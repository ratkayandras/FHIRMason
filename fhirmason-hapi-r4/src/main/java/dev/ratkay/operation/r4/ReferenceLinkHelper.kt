package dev.ratkay.operation.r4

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource

/**
 * Internal helpers for [OperationResult.linkReferences].
 *
 * All functions operate on a **copy** of the parameter map produced by [deepCopyParameters];
 * they never mutate the original [OperationResult] state.
 */

/**
 * Returns a deep copy of [parameters] so that reference-linking can mutate the copies
 * without affecting the original accumulated resources.
 *
 * HAPI FHIR's [Base.copy] performs a recursive clone of each element.
 */
internal fun deepCopyParameters(parameters: Map<String, MutableList<Base>>): MutableMap<String, MutableList<Base>> =
    parameters.mapValues { (_, values) ->
        values.map { base -> if (base is Resource) base.copy() else base }.toMutableList()
    }.toMutableMap()

/**
 * Applies explicit [rules] to all resources in [copiedParams].
 *
 * For each rule there must be **at most one** target resource; if multiple are found an
 * [IllegalArgumentException] is thrown because the wiring would be ambiguous. Every source
 * resource has its reference set to the single target. Self-pairs (source === target) are
 * skipped.
 */
internal fun applyReferenceLinkRules(
    copiedParams: MutableMap<String, MutableList<Base>>,
    rules: Array<out ReferenceLinkRule<*, *>>
) {
    val allResources = copiedParams.values.flatten().filterIsInstance<Resource>()
    rules.forEach { rule ->
        val sources = allResources.filter { rule.sourceType.java.isInstance(it) }
        val targets = allResources.filter { rule.targetType.java.isInstance(it) }
        require(targets.size <= 1) {
            "Ambiguous reference target: ${targets.size} resources of type " +
                "'${rule.targetType.simpleName}' found; exactly one is required per rule"
        }
        val target = targets.singleOrNull() ?: return@forEach
        sources.forEach { source ->
            if (source !== target) rule.applyTo(source, target)
        }
    }
}

/**
 * Automatically wires FHIR references in [copiedParams] by introspecting each resource's
 * HAPI child properties.
 *
 * Algorithm:
 * 1. Build an index of ID-bearing resources keyed by `fhirType().lowercase()`.
 * 2. For every accumulated resource, iterate its [Base.children] properties.
 * 3. For each unset property whose typeCode starts with `"Reference("`, parse the allowed
 *    target types from the typeCode (e.g. `"Reference(Patient|Group)"`).
 * 4. If exactly one matching resource exists in the index for one of those types, assign a
 *    `Reference("ResourceType/id")` via [Base.setProperty].
 *
 * Only unset reference properties are touched; already-populated references are left unchanged.
 * Ambiguous cases (multiple candidates for the same property) are silently skipped.
 */
internal fun autoLinkReferences(copiedParams: MutableMap<String, MutableList<Base>>) {
    val allResources = copiedParams.values.flatten().filterIsInstance<Resource>()

    val resourceIndex: Map<String, List<Resource>> = allResources
        .filter { it.hasId() }
        .groupBy { it.fhirType().lowercase() }

    allResources.forEach { source ->
        source.children().forEach { property ->
            val typeCode = property.typeCode ?: return@forEach
            if (!typeCode.startsWith("Reference(")) return@forEach
            if (property.hasValues()) return@forEach

            val allowedTypes = typeCode
                .removePrefix("Reference(")
                .removeSuffix(")")
                .split("|")
                .map { it.trim().lowercase() }

            val candidates: List<Resource> = allowedTypes.flatMap { type ->
                resourceIndex[type]?.filter { it !== source } ?: emptyList()
            }

            if (candidates.size == 1) {
                val target = candidates.single()
                val ref = Reference("${target.fhirType()}/${target.idPart}")
                runCatching { source.setProperty(property.name, ref) }
            }
        }
    }
}
