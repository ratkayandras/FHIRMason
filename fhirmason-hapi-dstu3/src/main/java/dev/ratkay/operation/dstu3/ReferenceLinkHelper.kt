package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.Reference
import org.hl7.fhir.dstu3.model.Resource

/**
 * Internal helpers for [OperationResult.linkReferences] in DSTU3.
 */

internal fun deepCopyParameters(parameters: Map<String, MutableList<Base>>): MutableMap<String, MutableList<Base>> =
    parameters.mapValues { (_, values) ->
        values.map { base -> if (base is Resource) base.copy() else base }.toMutableList()
    }.toMutableMap()

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
                val ref = Reference("${target.fhirType()}/${target.idElement.idPart}")
                runCatching { source.setProperty(property.name, ref) }
            }
        }
    }
}
