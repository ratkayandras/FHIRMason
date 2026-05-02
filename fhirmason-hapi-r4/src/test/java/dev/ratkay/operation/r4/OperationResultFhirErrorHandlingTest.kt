package dev.ratkay.operation.r4

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
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
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
 * - [AsyncOperationResult] also preserves [BaseServerResponseException] outcomes
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

        OperationResult.of(patient())
            .add("enc") { throw InvalidRequestException("server error") }
            .add("cov") { secondStepExecuted = true; Coverage() }

        assertFalse(secondStepExecuted, "Second step should be skipped in FAIL_FAST mode")
    }

    @Test
    fun `add - BaseServerResponseException continues pipeline in ACCUMULATE mode`() {
        var secondStepExecuted = false

        val result = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.ACCUMULATE)
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

    // ── AsyncOperationResult - BaseServerResponseException ───────────────────

    @Test
    fun `async add - BaseServerResponseException with embedded outcome is preserved in failedTasks`() {
        val embeddedOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.TIMEOUT
                diagnostics = "Upstream FHIR server timed out"
            }
        }

        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { setId("p1") } }
            .add("enc") {
                throw InvalidRequestException("timeout").apply { operationOutcome = embeddedOutcome }
            }
            .runBlocking()

        assertTrue(result.hasErrors())
        val encOutcome = result.getFailedTasks()["enc"]
        assertNotNull(encOutcome)
        assertThat(encOutcome!!.issue[0].code, equalTo(OperationOutcome.IssueType.TIMEOUT))
        assertThat(encOutcome.issue[0].diagnostics, equalTo("Upstream FHIR server timed out"))
    }

    @Test
    fun `async add - plain BaseServerResponseException without embedded outcome uses message`() {
        val result = AsyncOperationResult()
            .add("enc") { throw InvalidRequestException("bad param") }
            .runBlocking()

        assertTrue(result.hasErrors())
        val encOutcome = result.getFailedTasks()["enc"]
        assertNotNull(encOutcome)
        assertThat(encOutcome!!.issue[0].diagnostics, containsString("bad param"))
    }

    // ── toOperationOutcome preserves all issue fields ─────────────────────────

    @Test
    fun `toOperationOutcome preserves location from original issue`() {
        val embedded = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.EXCEPTION
                diagnostics = "error"
                addLocation("Patient.name")
            }
        }
        val result = OperationResult.of(patient())
            .add("enc") { throw InternalErrorException("fail", embedded) }

        val merged = result.toOperationOutcome()
        assertThat(merged.issue, hasSize(1))
        assertThat(merged.issue[0].location.map { it.value }, equalTo(listOf("Patient.name")))
    }

    @Test
    fun `toOperationOutcome preserves expression from original issue`() {
        val embedded = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.EXCEPTION
                diagnostics = "error"
                addExpression("Patient.active")
            }
        }
        val result = OperationResult.of(patient())
            .add("enc") { throw InternalErrorException("fail", embedded) }

        val merged = result.toOperationOutcome()
        assertThat(merged.issue, hasSize(1))
        assertThat(merged.issue[0].expression.map { it.value }, equalTo(listOf("Patient.active")))
    }

    @Test
    fun `toOperationOutcome preserves extension from original issue`() {
        val embedded = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.ERROR
                code = OperationOutcome.IssueType.EXCEPTION
                diagnostics = "error"
                addExtension("http://example.org/ext", org.hl7.fhir.r4.model.StringType("val"))
            }
        }
        val result = OperationResult.of(patient())
            .add("enc") { throw InternalErrorException("fail", embedded) }

        val merged = result.toOperationOutcome()
        assertThat(merged.issue, hasSize(1))
        assertThat(merged.issue[0].extension, hasSize(1))
        assertThat(merged.issue[0].extension[0].url, equalTo("http://example.org/ext"))
    }

    // ── PROPAGATE strategy ───────────────────────────────────────────────────

    @Test
    fun `add with PROPAGATE strategy - exception propagates raw, not wrapped as outcome`() {
        val pipeline = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.PROPAGATE)
        assertThrows<RuntimeException> {
            pipeline.add("coverage") { throw RuntimeException("boom") }
        }
    }

    @Test
    fun `addAll with PROPAGATE strategy - exception propagates raw`() {
        val pipeline = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.PROPAGATE)
        assertThrows<IllegalStateException> {
            pipeline.addAll<Patient>("coverage") { throw IllegalStateException("addAll boom") }
        }
    }

    @Test
    fun `addFrom with PROPAGATE strategy - exception propagates raw`() {
        val pipeline = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.PROPAGATE)
        assertThrows<RuntimeException> {
            pipeline.addFrom<Patient, Patient>("test", Patient::class) { throw RuntimeException("addFrom boom") }
        }
    }

    @Test
    fun `flatMap with PROPAGATE strategy - exception propagates raw`() {
        val pipeline = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.PROPAGATE)
        assertThrows<RuntimeException> {
            pipeline.flatMap<Patient> { throw RuntimeException("flatMap boom") }
        }
    }

    @Test
    fun `PROPAGATE - no outcomes accumulated after propagation (outcomes list stays empty)`() {
        val pipeline = OperationResult.of(patient()).useErrorStrategy(ErrorStrategy.PROPAGATE)
        assertThrows<RuntimeException> {
            pipeline.add("coverage") { throw RuntimeException("boom") }
        }
        assertTrue(pipeline.isSuccessful())
        assertThat(pipeline.getOutcomes(), hasSize(0))
    }

    @Test
    fun `PROPAGATE - successful steps complete normally and accumulate results`() {
        val result = OperationResult.of(patient())
            .useErrorStrategy(ErrorStrategy.PROPAGATE)
            .add("encounter") { Encounter().apply { setId("e1") } }

        assertTrue(result.isSuccessful())
        assertNotNull(result.getResult())
    }

    @Test
    fun `useErrorStrategy - mid-chain switch from FAIL_FAST to PROPAGATE takes effect from next step`() {
        val result = OperationResult.of(patient())
            .add("encounter") { throw RuntimeException("step1 fails") }
            .useErrorStrategy(ErrorStrategy.PROPAGATE)

        assertTrue(result.hasErrors())
        assertThrows<RuntimeException> {
            result.add("coverage") { throw RuntimeException("step2 propagates") }
        }
    }

    @Test
    fun `useErrorStrategy - mid-chain switch from ACCUMULATE to FAIL_FAST skips subsequent steps`() {
        var step2Ran = false
        val result = OperationResult.of(patient())
            .useErrorStrategy(ErrorStrategy.ACCUMULATE)
            .add("enc") { throw RuntimeException("step1 fails") }
            .useErrorStrategy(ErrorStrategy.FAIL_FAST)
            .add("cov") { step2Ran = true; Coverage() }

        assertTrue(result.hasErrors())
        assertFalse(step2Ran)
    }

    // ── fromParametersTyped error outcomes ───────────────────────────────────

    @Test
    fun `fromParametersTyped - missing key returns hasErrors true and null head`() {
        val result = OperationResult.fromParametersTyped(Parameters(), "patient", Patient::class)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `fromParametersTyped - missing key outcome has NOTFOUND code`() {
        val result = OperationResult.fromParametersTyped(Parameters(), "patient", Patient::class)
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(result.getOutcomes()[0].issueFirstRep.severity, equalTo(OperationOutcome.IssueSeverity.ERROR))
        assertThat(result.getOutcomes()[0].issueFirstRep.code, equalTo(OperationOutcome.IssueType.NOTFOUND))
    }

    @Test
    fun `fromParametersTyped - found key returns isSuccessful true with correct typed head`() {
        val p = Patient().apply { setId("p1") }
        val params = Parameters().apply { addParameter().setName("patient").setResource(p) }
        val result = OperationResult.fromParametersTyped(params, "patient", Patient::class)
        assertTrue(result.isSuccessful())
        assertThat(result.getResult().idElement.idPart, equalTo("p1"))
    }

    // ── fromBundleTyped error outcomes ───────────────────────────────────────

    @Test
    fun `fromBundleTyped - missing key returns hasErrors true and null head`() {
        val result = OperationResult.fromBundleTyped(Bundle(), "patient", Patient::class)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `fromBundleTyped - found resource returns isSuccessful true`() {
        val p = Patient().apply { setId("p1") }
        val bundle = Bundle().apply { addEntry().resource = p }
        val result = OperationResult.fromBundleTyped(bundle, "patient", Patient::class)
        assertTrue(result.isSuccessful())
        assertThat(result.getResult().idElement.idPart, equalTo("p1"))
    }

    // ── extractParam Pattern A ───────────────────────────────────────────────

    @Test
    fun `extractParam - missing key adds ERROR outcome and skips (Pattern A)`() {
        val result = OperationResult.of(patient())
            .extractParam("missing", Coverage::class)
        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(result.getOutcomes()[0].issueFirstRep.severity, equalTo(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `extractParam - found key changes head and leaves no error`() {
        val p = patient()
        val result = OperationResult.of(p)
            .extractParam("patient", Patient::class)
        assertTrue(result.isSuccessful())
        assertSame(p, result.getResult())
    }

    @Test
    fun `extractParam - missing key with FAIL_FAST skips subsequent steps`() {
        var step2Ran = false
        val result = OperationResult.of(patient())
            .extractParam("missing", Coverage::class)
            .add("enc") { step2Ran = true; Encounter() }
        assertTrue(result.hasErrors())
        assertFalse(step2Ran)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply { setId("p1") }
}
