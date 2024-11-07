package dev.ratkay.extension

import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType

fun Resource.getResourceTypeAsLowercase(): String {
    return this.resourceType.toString().lowercase()
}

fun Resource.toParameterComponent(name: String): ParametersParameterComponent =
    ParametersParameterComponent(StringType(name)).setResource(this)

fun Resource.toBundleEntryComponent(): BundleEntryComponent = BundleEntryComponent().setResource(this)