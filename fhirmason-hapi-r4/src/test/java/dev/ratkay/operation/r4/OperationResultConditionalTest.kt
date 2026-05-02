package dev.ratkay.operation.r4

import dev.ratkay.operation.ErrorStrategy

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.sameInstance
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultConditionalTest {

    // ── whenTrue ─────────────────────────────────────────────────────────────

    @Test
    fun `whenTrue - runs block and merges parameters when condition is true`() {
        val appt = appointment()
        val result = OperationResult.of(patient(), "patient")
            .whenTrue(true) { add("appt") { appt } }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
        assertThat(result.getAll("appt"), hasSize(1))
    }

    @Test
    fun `whenTrue - returns unchanged result when condition is false`() {
        val result = OperationResult.of(patient(), "patient")
            .whenTrue(false) { add("appt") { appointment() } }

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appt"))
    }

    @Test
    fun `whenTrue - preserves head type T when condition is true`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .whenTrue(true) { addString("flag", "active") }

        assertSame(patient, result.getResult())
    }

    @Test
    fun `whenTrue - preserves head type T when condition is false`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .whenTrue(false) { addString("flag", "active") }

        assertSame(patient, result.getResult())
    }

    @Test
    fun `whenTrue - records WARNING outcome and preserves head when block throws`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .whenTrue(true) { error("block failed") }

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(
            result.getOutcomes().first().issueFirstRep.severity,
            `is`(OperationOutcome.IssueSeverity.WARNING)
        )
        assertSame(patient, result.getResult())
    }

    @Test
    fun `whenTrue - merges inner outcomes into outer result`() {
        val result = OperationResult.of(patient(), "patient").useErrorStrategy(ErrorStrategy.ACCUMULATE)
            .whenTrue(true) {
                add("appt") { appointment() }
                    .add { error("inner error") }
            }

        assertTrue(result.hasErrors())
        assertTrue(result.containsKey("appt"))
    }

    @Test
    fun `whenTrue - respects shouldSkip in FAIL_FAST mode`() {
        val result = OperationResult.of(patient(), "patient")
            .add { error("pipeline already failed") }
            .whenTrue(true) { add("appt") { appointment() } }

        assertFalse(result.containsKey("appt"))
    }

    @Test
    fun `whenTrue - works with head-changing operations inside block`() {
        val patient = patient()
        val appt = appointment()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .whenTrue(true) {
                // add() inside changes head to Appointment, but whenTrue still returns Patient
                add("appt") { appt }
            }

        assertTrue(result.containsKey("appt"))
        assertSame(patient, result.getResult())
    }

    // ── ifPresent ─────────────────────────────────────────────────────────────

    @Test
    fun `ifPresent - runs block when head is non-null`() {
        val result = OperationResult.of(patient(), "patient")
            .ifPresent { p -> OperationResult.of(p, "resolved-patient") }

        assertTrue(result.containsKey("resolved-patient"))
    }

    @Test
    fun `ifPresent - returns unchanged result when head is null`() {
        val result = OperationResult.empty()
            .ifPresent { OperationResult.of(appointment(), "appt") }

        assertFalse(result.containsKey("appt"))
    }

    @Test
    fun `ifPresent - passes the typed head value to block`() {
        val patient = patient()
        var received: Patient? = null

        OperationResult.of(patient, "patient")
            .ifPresent { p ->
                received = p
                OperationResult.of(p, "received")
            }

        assertSame(patient, received)
    }

    @Test
    fun `ifPresent - records WARNING outcome and preserves head when block throws`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .ifPresent { error("block exploded") }

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(
            result.getOutcomes().first().issueFirstRep.severity,
            `is`(OperationOutcome.IssueSeverity.WARNING)
        )
        assertSame(patient, result.getResult())
    }

    @Test
    fun `ifPresent - respects shouldSkip in FAIL_FAST mode`() {
        val result = OperationResult.of(patient(), "patient")
            .add { error("pipeline already failed") }
            .ifPresent { OperationResult.of(appointment(), "appt") }

        assertFalse(result.containsKey("appt"))
    }

    @Test
    fun `ifPresent - merges parameters from inner result into outer map`() {
        val coverage = coverage()
        val result = OperationResult.of(patient(), "patient")
            .ifPresent { _ ->
                OperationResult.of(coverage, "coverage")
                    .addString("status", "active")
            }

        assertTrue(result.containsKey("coverage"))
        assertTrue(result.containsKey("status"))
        assertTrue(result.containsKey("patient"))
    }

    // ── guardFalse ────────────────────────────────────────────────────────────

    @Test
    fun `guardFalse - returns unchanged result when condition is true`() {
        val patient = patient()
        val result = OperationResult.of(patient, "patient")
            .guardFalse(true, "should not fire")

        assertFalse(result.hasErrors())
        assertSame(patient, result.getResult())
    }

    @Test
    fun `guardFalse - records WARNING outcome when condition is false`() {
        val result = OperationResult.of(patient(), "patient")
            .guardFalse(false, "patient is inactive")

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(
            result.getOutcomes().first().issueFirstRep.severity,
            `is`(OperationOutcome.IssueSeverity.WARNING)
        )
        assertEquals("patient is inactive", result.getOutcomes().first().issueFirstRep.diagnostics)
    }

    @Test
    fun `guardFalse - does not change the head when condition is false`() {
        val patient = patient()
        val result: OperationResult<Patient> = OperationResult.of(patient, "patient")
            .guardFalse(false, "inactive")

        assertSame(patient, result.getResult())
    }

    @Test
    fun `guardFalse - WARNING outcome has BUSINESSRULE issue code`() {
        val result = OperationResult.of(patient(), "patient")
            .guardFalse(false, "some business rule violated")

        assertThat(
            result.getOutcomes().first().issueFirstRep.code,
            `is`(OperationOutcome.IssueType.BUSINESSRULE)
        )
    }

    @Test
    fun `guardFalse - respects shouldSkip in FAIL_FAST mode`() {
        val result = OperationResult.of(patient(), "patient")
            .add { error("pipeline already failed") }
            .guardFalse(false, "should not add another outcome")

        // Only the original ERROR outcome should be present (shouldSkip returns early)
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(
            result.getOutcomes().first().issueFirstRep.severity,
            `is`(OperationOutcome.IssueSeverity.ERROR)
        )
    }

    @Test
    fun `guardFalse - pipeline continues and accumulates resources after guard`() {
        val appt = appointment()
        val result = OperationResult.of(patient(), "patient").useErrorStrategy(ErrorStrategy.ACCUMULATE)
            .guardFalse(false, "inactive")
            .add("appt") { appt }

        assertTrue(result.containsKey("appt"))
        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
    }

    // ── addOrSkip / addOrDefault respect FAIL_FAST ────────────────────────────

    @Test
    fun `addOrSkip - respects shouldSkip in FAIL_FAST mode`() {
        var invoked = false
        val result = OperationResult.of(patient())
            .add("enc") { error("trigger error") }
            .addOrSkip("skipped") { invoked = true; appointment() }

        assertFalse(invoked)
        assertFalse(result.containsKey("skipped"))
    }

    @Test
    fun `addOrDefault - respects shouldSkip in FAIL_FAST mode`() {
        var invoked = false
        val default = coverage()
        val result = OperationResult.of(patient())
            .add("enc") { error("trigger error") }
            .addOrDefault("skipped", default) { invoked = true; coverage() }

        assertFalse(invoked)
        assertFalse(result.containsKey("skipped"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply {
        addName().apply {
            family = "Doe"
            addGiven("Jane")
        }
    }

    private fun appointment() = Appointment().apply {
        status = Appointment.AppointmentStatus.BOOKED
    }

    private fun coverage() = Coverage().apply {
        status = Coverage.CoverageStatus.ACTIVE
    }
}
