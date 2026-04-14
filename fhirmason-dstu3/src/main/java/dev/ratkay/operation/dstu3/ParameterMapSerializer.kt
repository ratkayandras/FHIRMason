package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Base
import org.hl7.fhir.dstu3.model.Extension
import org.hl7.fhir.dstu3.model.Parameters
import org.hl7.fhir.dstu3.model.Resource
import org.hl7.fhir.dstu3.model.Type
import java.util.IdentityHashMap

/**
 * Converts between a flat dot-delimited parameter map and a nested FHIR [Parameters] resource
 * for FHIR DSTU3.
 */
internal object ParameterMapSerializer {

    fun flatten(
        parameters: Parameters
    ): Pair<MutableMap<String, MutableList<Base>>, MutableMap<String, MutableMap<Base, List<Extension>>>> {
        val params = mutableMapOf<String, MutableList<Base>>()
        val exts = mutableMapOf<String, MutableMap<Base, List<Extension>>>()
        parameters.parameter.forEach { param -> populateFromParam(params, exts, param) }
        return params to exts
    }

    fun unflatten(
        map: Map<String, List<Base>>,
        extensions: Map<String, Map<Base, List<Extension>>> = emptyMap()
    ): Parameters = Parameters().apply {
        val root = this
        buildParameterComponents(map, extensions) { name ->
            root.addParameter().also { it.name = name }
        }
    }

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
