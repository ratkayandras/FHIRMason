package dev.ratkay.extension

import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource

@Throws(IllegalArgumentException::class, NoSuchElementException::class)
inline fun <reified T : Resource> Parameters.getResource(paramName: String): T {
    return this.parameter
        .single { it.name == paramName }
        .let { it.resource as? T }
        ?: throw IllegalArgumentException("Parameter $paramName is not an instance of ${T::class}")
}

inline fun <reified T : Resource> Parameters.getResources(paramName: String): List<T> {
    return this.parameter
        .filter { it.name == paramName && it.hasResource() && it.resource is T }
        .map { it.resource as T }
}

fun Parameters.filter(vararg paramNames: String): Parameters {
    return this.parameter
        .filter { paramNames.contains(it.name) }
        .let { Parameters().setParameter(it) }
}