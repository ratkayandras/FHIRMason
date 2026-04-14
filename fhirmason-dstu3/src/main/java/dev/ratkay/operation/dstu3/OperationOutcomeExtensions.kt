package dev.ratkay.operation.dstu3

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import org.hl7.fhir.dstu3.model.OperationOutcome

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
 */
fun BaseServerResponseException.toOperationOutcome(): OperationOutcome =
    (operationOutcome as? OperationOutcome) ?: (this as Exception).toOperationOutcome()

/**
 * Dispatches to the correct [toOperationOutcome] overload — preserving the embedded
 * [OperationOutcome] from [BaseServerResponseException] when present, or creating a
 * generic ERROR outcome otherwise.
 */
internal fun errorOutcome(e: Exception): OperationOutcome = when (e) {
    is BaseServerResponseException -> e.toOperationOutcome()
    else -> e.toOperationOutcome()
}

/**
 * Builds a WARNING-severity [OperationOutcome] from [e], preserving any rich embedded
 * outcome when [e] is a [BaseServerResponseException].
 */
internal fun warningOutcome(e: Exception): OperationOutcome =
    errorOutcome(e).also { outcome ->
        outcome.issue.forEach { it.severity = OperationOutcome.IssueSeverity.WARNING }
    }
