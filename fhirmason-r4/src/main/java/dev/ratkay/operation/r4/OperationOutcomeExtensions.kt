package dev.ratkay.operation.r4

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

/**
 * Dispatches to the correct [toOperationOutcome] overload — preserving the embedded
 * [OperationOutcome] from [BaseServerResponseException] when present, or creating a
 * generic ERROR outcome otherwise.
 *
 * Both branches look visually identical but call **different** extension function overloads
 * via Kotlin's static dispatch: the smart-cast in the first branch resolves to
 * [BaseServerResponseException.toOperationOutcome], while the `else` branch resolves to
 * [Exception.toOperationOutcome]. This consolidates the dispatch in one place so callers
 * never need to repeat the `when` pattern inline.
 */
internal fun errorOutcome(e: Exception): OperationOutcome = when (e) {
    is BaseServerResponseException -> e.toOperationOutcome()
    else -> e.toOperationOutcome()
}

/**
 * Builds a WARNING-severity [OperationOutcome] from [e], preserving any rich embedded
 * outcome when [e] is a [BaseServerResponseException].
 *
 * Shared by [OperationResult.addOrSkip], [OperationResult.addOrDefault], and the
 * conditional-chaining methods — do not duplicate this logic inline.
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
