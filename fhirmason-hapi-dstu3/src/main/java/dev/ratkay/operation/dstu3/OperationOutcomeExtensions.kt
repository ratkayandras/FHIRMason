package dev.ratkay.operation.dstu3

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import org.hl7.fhir.dstu3.model.OperationOutcome

/**
 * Creates a generic ERROR-severity [OperationOutcome] from this exception, using the
 * exception message (or the class name when the message is `null`) as the diagnostics.
 *
 * Prefer [BaseServerResponseException.toOperationOutcome] when the exception is a HAPI
 * server/client exception — it extracts any embedded rich outcome rather than discarding it.
 */
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
 *
 * The first branch resolves to [BaseServerResponseException.toOperationOutcome] via the
 * smart-cast. The `else` branch uses an explicit cast to [Exception] to call
 * [Exception.toOperationOutcome] rather than relying on smart-cast resolution, ensuring
 * the correct overload is always selected unambiguously.
 */
internal fun errorOutcome(e: Exception): OperationOutcome = when (e) {
    is BaseServerResponseException -> e.toOperationOutcome()
    else -> (e as Exception).toOperationOutcome()
}

/**
 * Builds a WARNING-severity [OperationOutcome] from [e], preserving any rich embedded
 * outcome when [e] is a [BaseServerResponseException].
 */
internal fun warningOutcome(e: Exception): OperationOutcome =
    errorOutcome(e).also { outcome ->
        outcome.issue.forEach { it.severity = OperationOutcome.IssueSeverity.WARNING }
    }

/**
 * Builds an ERROR-severity [OperationOutcome] with [IssueType.NOTFOUND] code from a plain
 * diagnostic message string.  Used by factory methods that detect a missing primary resource.
 */
internal fun messageOutcome(diagnostics: String): OperationOutcome = OperationOutcome().apply {
    addIssue().apply {
        severity = OperationOutcome.IssueSeverity.ERROR
        code = OperationOutcome.IssueType.NOTFOUND
        this.diagnostics = diagnostics
    }
}
