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