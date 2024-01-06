package eu.ratkay.extension

import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType

fun Resource.toParameterComponent(name: String): ParametersParameterComponent =
    ParametersParameterComponent(StringType(name)).setResource(this)

fun Resource.toBundleEntryComponent(): BundleEntryComponent = BundleEntryComponent().setResource(this)

fun OperationOutcome.hasError(): Boolean {
    return this.issue.any { operationOutcomeIssueComponent ->
        operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.FATAL || operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.ERROR
    }
}

fun Resource.getParameterName(): String {
    return this.resourceType.toString().lowercase()
}