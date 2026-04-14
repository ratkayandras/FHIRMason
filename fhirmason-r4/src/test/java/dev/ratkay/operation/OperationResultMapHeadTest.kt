package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.sameInstance
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OperationResultMapHeadTest {

    // ── mapHead — basic type change ──────────────────────────────────────────

    @Test
    fun `mapHead changes head type and returns new typed result`() {
        val appt = appointment()
        val result: OperationResult<Appointment> = OperationResult.of(patient(), "patient")
            .mapHead { appt }

        assertSame(appt, result.getResult())
    }

    @Test
    fun `mapHead receives the current head value as argument`() {
        val patient = patient()
        var received: Patient? = null

        OperationResult.of(patient, "patient")
            .mapHead { p ->
                received = p
                appointment()
            }

        assertSame(patient, received)
    }

    @Test
    fun `mapHead does not add a new entry to the parameter map`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHead { appointment() }

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
        assertEquals(1, result.getAllParameters().size)
    }

    @Test
    fun `mapHead preserves all existing parameter map entries`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .mapHead { Coverage() }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
        assertEquals(2, result.getAllParameters().size)
    }

    // ── mapHead — pipeline continuity ────────────────────────────────────────

    @Test
    fun `mapHead result can be used as head in subsequent pipeline steps`() {
        val appt = appointment()
        val result = OperationResult.of(patient(), "patient")
            .mapHead { appt }
            .add("finalAppt") { appt }

        assertTrue(result.containsKey("finalAppt"))
        assertThat(result.getAll("finalAppt"), hasSize(1))
    }

    @Test
    fun `mapHead chained twice transforms through both types`() {
        val coverage = Coverage()
        val result: OperationResult<Coverage> = OperationResult.of(patient(), "patient")
            .mapHead { appointment() }
            .mapHead { coverage }

        assertSame(coverage, result.getResult())
    }

    @Test
    fun `mapHead followed by add stores new entry and retains existing ones`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHead { appointment() }
            .add("enc") { Encounter() }

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("enc"))
    }

    // ── mapHead — contrast with flatMap ──────────────────────────────────────

    @Test
    fun `mapHead does not merge inner result parameters unlike flatMap`() {
        // flatMap would add "appt-inner" to the map; mapHead must not
        val result = OperationResult.of(patient(), "patient")
            .mapHead { _ ->
                // simulate: produce an Appointment (no merging of any OperationResult)
                appointment()
            }

        assertFalse(result.containsKey("appt-inner"))
        assertEquals(1, result.getAllParameters().size)
    }

    // ── mapHead — error handling (Pattern A) ─────────────────────────────────

    @Test
    fun `mapHead records ERROR outcome and skips head when transform throws`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHead<Appointment> { error("transform failed") }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes(), hasSize(1))
        assertThat(
            result.getOutcomes().first().issueFirstRep.severity,
            `is`(OperationOutcome.IssueSeverity.ERROR)
        )
    }

    @Test
    fun `mapHead head is inaccessible (getResult throws) when transform throws`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHead<Appointment> { error("boom") }

        assertTrue(result.hasErrors())
        assertThrows<IllegalStateException> { result.getResult() }
    }

    @Test
    fun `mapHead preserves existing parameter map entries when transform throws`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHead<Appointment> { error("boom") }

        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `mapHead skips transform in FAIL_FAST mode when pipeline already has errors`() {
        var transformCalled = false

        val result = OperationResult.of(patient(), "patient")
            .add<Appointment> { error("earlier failure") }
            .mapHead {
                transformCalled = true
                appointment()
            }

        assertFalse(transformCalled)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `mapHead runs transform in ACCUMULATE mode even when pipeline has errors`() {
        var transformCalled = false

        // Use addOrSkip (Pattern B) so the head is preserved after the failure,
        // allowing mapHead to receive it in ACCUMULATE mode.
        OperationResult.of(patient(), "patient", errorStrategy = ErrorStrategy.ACCUMULATE)
            .addOrSkip("extra") { error("earlier warning") }
            .mapHead { p ->
                transformCalled = true
                appointment()
            }

        assertTrue(transformCalled)
    }

    // ── mapHeadUsing — receiver lambda variant ────────────────────────────────

    @Test
    fun `mapHeadUsing changes head type using receiver lambda`() {
        val appt = appointment()
        val result: OperationResult<Appointment> = OperationResult.of(patient(), "patient")
            .mapHeadUsing { appt }

        assertSame(appt, result.getResult())
    }

    @Test
    fun `mapHeadUsing exposes current head as this`() {
        val patient = patient()
        var capturedThis: Patient? = null

        OperationResult.of(patient, "patient")
            .mapHeadUsing {
                capturedThis = this
                appointment()
            }

        assertSame(patient, capturedThis)
    }

    @Test
    fun `mapHeadUsing does not add a new parameter entry`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHeadUsing { appointment() }

        assertEquals(1, result.getAllParameters().size)
        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `mapHeadUsing records ERROR and head is inaccessible when lambda throws`() {
        val result = OperationResult.of(patient(), "patient")
            .mapHeadUsing<Appointment> { error("receiver lambda failed") }

        assertTrue(result.hasErrors())
        assertThrows<IllegalStateException> { result.getResult() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply {
        addName().apply {
            family = "Doe"
            addGiven("John")
        }
    }

    private fun appointment() = Appointment().apply {
        status = Appointment.AppointmentStatus.BOOKED
    }
}
