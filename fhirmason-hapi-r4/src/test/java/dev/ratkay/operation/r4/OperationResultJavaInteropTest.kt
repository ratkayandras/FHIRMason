package dev.ratkay.operation.r4

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that the Class<T> overloads added for Java interop produce the same
 * results as the corresponding KClass / reified overloads.
 */
class OperationResultJavaInteropTest {

    // ── getByType ─────────────────────────────────────────────────────────────

    @Test
    fun `getByType - Class overload returns same resources as KClass overload`() {
        val patient = patient()
        val result = OperationResult.of(patient, "patient")
            .add("appt") { appointment() }

        val byClass = result.getByType(Patient::class.java)
        val byKClass = result.getByType(Patient::class)

        assertThat(byClass, hasSize(1))
        assertSame(byClass[0], byKClass[0])
    }

    @Test
    fun `getByType - Class overload returns empty list when type absent`() {
        val result = OperationResult.of(patient(), "patient")

        val found = result.getByType(Coverage::class.java)

        assertThat(found, hasSize(0))
    }

    // ── filterByType ──────────────────────────────────────────────────────────

    @Test
    fun `filterByType - Class overload keeps only matching type`() {
        val patient = patient()
        val result = OperationResult.of(patient, "patient")
            .add("appt") { appointment() }

        val filtered = result.filterByType(Patient::class.java)

        assertTrue(filtered.containsKey("patient"))
        assertFalse(filtered.containsKey("appt"))
    }

    // ── addFrom ───────────────────────────────────────────────────────────────

    @Test
    fun `addFrom - Class overload processes values same as KClass overload`() {
        val appt = appointment()
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appt }
            .addFrom("appt", Appointment::class.java) { list -> list.first() }

        assertSame(appt, result.getResult())
    }

    // ── addAllFrom ────────────────────────────────────────────────────────────

    @Test
    fun `addAllFrom - Class overload collects values same as KClass overload`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .add("appt") { appointment() }
            .addAllFrom("appt", Appointment::class.java) { list -> list }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── extractParam ──────────────────────────────────────────────────────────

    @Test
    fun `extractParam - Class overload sets head to first matching value`() {
        val patient = patient()
        val result = OperationResult.of(patient, "patient")
            .extractParam("patient", Patient::class.java)

        assertSame(patient, result.getResult())
    }

    // ── extractParamList ──────────────────────────────────────────────────────

    @Test
    fun `extractParamList - Class overload sets head to list of matching values`() {
        val result = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }
            .add("appt") { appointment() }
            .extractParamList("appt", Appointment::class.java)

        assertThat(result.getResult(), hasSize(2))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply { addName().family = "Doe" }
    private fun appointment() = Appointment().apply { status = Appointment.AppointmentStatus.BOOKED }
}
