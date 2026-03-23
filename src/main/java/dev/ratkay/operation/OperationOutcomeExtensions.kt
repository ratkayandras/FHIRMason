package dev.ratkay.operation

import org.hl7.fhir.r4.model.OperationOutcome

fun Exception.toOperationOutcome(): OperationOutcome = OperationOutcome().apply {
    addIssue().apply {
        severity = OperationOutcome.IssueSeverity.ERROR
        code = OperationOutcome.IssueType.EXCEPTION
        diagnostics = this@toOperationOutcome.message ?: this@toOperationOutcome.javaClass.simpleName
    }
}
