package dev.ratkay.operation

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import org.hl7.fhir.r4.model.OperationOutcome

fun Exception.toOperationOutcome(): OperationOutcome = OperationOutcome().apply {
    addIssue().apply {
        severity = OperationOutcome.IssueSeverity.ERROR
        code = OperationOutcome.IssueType.EXCEPTION
        diagnostics = this@toOperationOutcome.message ?: this@toOperationOutcome.javaClass.simpleName
    }
}

/**
 * Extracts the embedded [OperationOutcome] from a [BaseServerResponseException] when one is
 * present, or falls back to creating a generic error outcome from the exception message.
 *
 * This preserves the rich diagnostic information that HAPI FHIR server or client exceptions
 * already carry, rather than discarding it in favour of a plain exception-message outcome.
 */
fun BaseServerResponseException.toOperationOutcome(): OperationOutcome =
    (operationOutcome as? OperationOutcome) ?: (this as Exception).toOperationOutcome()
