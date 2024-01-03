package eu.ratkay.extension

import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.StringType

fun Resource.toParameters(name: String): Parameters {
    if (this is Parameters) return this
    return Parameters()
        .setParameter(
            listOf(
                Parameters.ParametersParameterComponent(StringType(name)).setResource(this)
            )
        )
}

fun OperationOutcome.hasError(): Boolean {
    return this.issue.any { operationOutcomeIssueComponent ->
        operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.FATAL || operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.ERROR
    }
}

fun Resource.getParameterName(): String {
    return this.resourceType.toString().lowercase()
}