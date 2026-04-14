package dev.ratkay.operation.dstu3

import dev.ratkay.operation.ErrorStrategy

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hl7.fhir.dstu3.model.Coverage
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.Resource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for HAPI FHIR-specific error handling:
 * - [BaseServerResponseException] outcomes are preserved rather than replaced
 * - [OperationResult.throwIfErrors] surfaces pipeline errors as [InternalErrorException]
 */
class OperationResultFhirErrorHandlingTest {

    // ── BaseServerResponseException in add() ─────────────────────────────────

    @Test
    fun `add - BaseServerResponseException without embedded outcome creates generic error outcome`() {
        val result = OperationResult.of(patient())
            .add("coverage") { throw InvalidRequestException("bad request") }

        assertTrue(result.hasErrors())
        val outcome = result.toOperationOutcome()
        assertThat(outcome.issue, hasSize(1))
        assertThat(outcome.issue[0].severity, equalTo(OperationOutcome.IssueSeverity.ERROR))
        assertThat(outcome.issue[0].diagnostics, containsString("bad request"))
    }

    @Test
    fun `add - BaseServerResponseException with embedded OperationOutcome preserves it`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.NOTFOUND
                diagnostics = "Patient/123 not found on remote server"
            }
        }
        val fhirException = InvalidRequestException("not found").apply {
            operationOutcome = embeddedOutcome
        }

        val result = OperationResult.of(patient())
            .add("coverage") { throw fhirException }

        assertTrue(result.hasErrors())
        val merged = result.toOperationOutcome()
        assertThat(merged.issue, hasSize(1))
        assertThat(merged.issue[0].code, equalTo(OperationOutcome.IssueType.NOTFOUND))
        assertThat(merged.issue[0].diagnostics, equalTo("Patient/123 not found on remote server"))
    }

    @Test
    fun `add - BaseServerResponseException with multi-issue OperationOutcome preserves all issues`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.INVALID
                diagnostics = "Field 'birthDate' is invalid"
            }
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.REQUIRED
                diagnostics = "Field 'name' is required"
            }
        }
        val fhirException = InvalidRequestException("validation failed").apply {
            operationOutcome = embeddedOutcome
        }

        val result = OperationResult.of(patient())
            .add("enc") { throw fhirException }

        val merged = result.toOperationOutcome()
        assertThat(merged.issue, hasSize(2))
        assertThat(merged.issue[0].code, equalTo(OperationOutcome.IssueType.INVALID))
        assertThat(merged.issue[1].code, equalTo(OperationOutcome.IssueType.REQUIRED))
    }

    @Test
    fun `add - BaseServerResponseException stops pipeline in FAIL_FAST mode`() {
        var secondStepExecuted = false

        OperationResult.of(patient(), errorStrategy = ErrorStrategy.FAIL_FAST)
            .add("enc") { throw InvalidRequestException("server error") }
            .add("cov") { secondStepExecuted = true; Coverage() }

        assertFalse(secondStepExecuted, "Second step should be skipped in FAIL_FAST mode")
    }

    @Test
    fun `add - BaseServerResponseException continues pipeline in ACCUMULATE mode`() {
        var secondStepExecuted = false

        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.ACCUMULATE)
            .add("enc") { throw InvalidRequestException("server error") }
            .add("cov") { secondStepExecuted = true; Coverage() }

        assertTrue(secondStepExecuted, "Second step should run in ACCUMULATE mode")
        assertTrue(result.hasErrors())
        assertTrue(result.containsKey("cov"))
    }

    // ── BaseServerResponseException in addUsing() ────────────────────────────

    @Test
    fun `addUsing - BaseServerResponseException with embedded outcome preserves it`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.FORBIDDEN
                diagnostics = "Access denied to Encounter resource"
            }
        }

        val result = OperationResult.of(patient())
            .addUsing("enc") { _: Patient ->
                throw InvalidRequestException("forbidden").apply { operationOutcome = embeddedOutcome }
            }

        val merged = result.toOperationOutcome()
        assertThat(merged.issue[0].code, equalTo(OperationOutcome.IssueType.FORBIDDEN))
    }

    // ── BaseServerResponseException in addOrSkip() ───────────────────────────

    @Test
    fun `addOrSkip - BaseServerResponseException with embedded outcome is downgraded to WARNING`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.NOTFOUND
                diagnostics = "Optional resource not available"
            }
        }

        val result = OperationResult.of(patient())
            .addOrSkip<Coverage>("cov") {
                throw InvalidRequestException("not found").apply { operationOutcome = embeddedOutcome }
            }

        assertTrue(result.hasErrors())
        val merged = result.toOperationOutcome()
        assertThat(merged.issue[0].severity, equalTo(OperationOutcome.IssueSeverity.WARNING))
        assertThat(merged.issue[0].code, equalTo(OperationOutcome.IssueType.NOTFOUND))
        assertFalse(result.containsKey("cov"), "Failed step should not add any value")
    }

    // ── throwIfErrors() ──────────────────────────────────────────────────────

    @Test
    fun `throwIfErrors - returns same instance when pipeline is successful`() {
        val result = OperationResult.of(patient())
            .add { Coverage() }

        assertSame(result.throwIfErrors(), result)
    }

    @Test
    fun `throwIfErrors - throws InternalErrorException when pipeline has errors`() {
        val result = OperationResult.of(patient())
            .add("enc") { throw RuntimeException("fetch failed") }

        assertThrows<InternalErrorException> {
            result.throwIfErrors()
        }
    }

    @Test
    fun `throwIfErrors - embedded OperationOutcome is attached to the thrown exception`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.NOTFOUND
                diagnostics = "Resource not found on upstream"
            }
        }

        val result = OperationResult.of(patient())
            .add("enc") {
                throw InvalidRequestException("not found").apply { operationOutcome = embeddedOutcome }
            }

        val thrown = assertThrows<InternalErrorException> { result.throwIfErrors() }
        val attachedOutcome = thrown.operationOutcome as? OperationOutcome
        assertNotNull(attachedOutcome)
        assertThat(attachedOutcome!!.issue[0].code, equalTo(OperationOutcome.IssueType.NOTFOUND))
        assertThat(attachedOutcome.issue[0].diagnostics, equalTo("Resource not found on upstream"))
    }

    @Test
    fun `throwIfErrors - can be used fluently before toParameters`() {
        val result = OperationResult.of(patient())
            .add { Coverage() }

        // Should not throw — can chain directly into toParameters()
        val params = result.throwIfErrors().toParameters()
        assertNotNull(params)
    }

    @Test
    fun `throwIfErrors - exception message is taken from first issue diagnostics`() {
        val result = OperationResult.of(patient())
            .add("enc") { throw RuntimeException("specific error message") }

        val thrown = assertThrows<InternalErrorException> { result.throwIfErrors() }
        assertThat(thrown.message, containsString("specific error message"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply { setId("p1") }
}
