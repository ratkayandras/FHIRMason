package eu.ratkay.extension

import org.hl7.fhir.r4.model.OperationOutcome

fun OperationOutcome.hasError(): Boolean {
    return this.issue.any { operationOutcomeIssueComponent ->
        operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.FATAL || operationOutcomeIssueComponent.severity == OperationOutcome.IssueSeverity.ERROR
    }
}