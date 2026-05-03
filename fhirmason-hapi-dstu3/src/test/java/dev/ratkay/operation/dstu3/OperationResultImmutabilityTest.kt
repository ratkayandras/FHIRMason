package dev.ratkay.operation.dstu3

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.dstu3.model.Appointment
import org.hl7.fhir.dstu3.model.Coverage
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationResultImmutabilityTest {

    @Test
    fun `forking pipeline - two branches from same intermediate have independent params`() {
        val base = OperationResult.of(Patient())

        val branchA = base.add { Appointment() }
        val branchB = base.add { Coverage() }

        assertThat(branchA.getAll("appointment"), hasSize(1))
        assertFalse(branchA.containsKey("coverage"))

        assertThat(branchB.getAll("coverage"), hasSize(1))
        assertFalse(branchB.containsKey("appointment"))
    }

    @Test
    fun `forking pipeline - error in one branch does not affect other branch`() {
        val base = OperationResult.of(Patient())

        val branchA = base.add { error("branch A fails") }
        val branchB = base.add { Coverage() }

        assertTrue(branchA.hasErrors())
        assertFalse(branchB.hasErrors())
    }

    @Test
    fun `forking pipeline - outcomes are independent between branches`() {
        val base = OperationResult.of(Patient())

        val branchA = base.add { error("branch A fails") }
        val branchB = base.add { Coverage() }

        assertThat(branchA.getOutcomes(), hasSize(1))
        assertThat(branchB.getOutcomes(), hasSize(0))
    }

    @Test
    fun `forking pipeline - metrics are independent between branches`() {
        val base = OperationResult.of(Patient()).timed()

        val branchA = base.add("appointment") { Appointment() }
        val branchB = base.add("coverage") { Coverage() }

        assertThat(branchA.getMetrics(), hasSize(1))
        assertThat(branchA.getMetrics().first().stepName, `is`("appointment"))

        assertThat(branchB.getMetrics(), hasSize(1))
        assertThat(branchB.getMetrics().first().stepName, `is`("coverage"))
    }

    @Test
    fun `primitive step - does not mutate prior instance params`() {
        val before = OperationResult.of(Patient())
        val after = before.addString("status", "active")

        assertFalse(before.containsKey("status"))
        assertTrue(after.containsKey("status"))
    }

    @Test
    fun `addAll - does not mutate prior instance params`() {
        val before = OperationResult.of(Patient())
        val after = before.addAll { listOf(Appointment(), Appointment()) }

        assertFalse(before.containsKey("appointment"))
        assertThat(after.getAll("appointment"), hasSize(2))
    }

    @Test
    fun `addOrSkip success - does not mutate prior instance params`() {
        val before = OperationResult.of(Patient())
        val after = before.addOrSkip("coverage") { Coverage() }

        assertFalse(before.containsKey("coverage"))
        assertTrue(after.containsKey("coverage"))
    }

    @Test
    fun `addOrDefault success - does not mutate prior instance params`() {
        val before = OperationResult.of(Patient())
        val after = before.addOrDefault("coverage", Coverage()) { Coverage() }

        assertFalse(before.containsKey("coverage"))
        assertTrue(after.containsKey("coverage"))
    }
}
