package dev.ratkay.operation.dstu3

import dev.ratkay.operation.ErrorStrategy
import dev.ratkay.operation.FhirPath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.hl7.fhir.dstu3.model.HumanName
import org.hl7.fhir.dstu3.model.Observation
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultFhirPathTest {

    // ── addFromMatching ────────────────────────────────────────────────────

    @Test
    fun `addFromMatching filters accumulated resources to those where expression is true`() {
        val activePatient = Patient().apply { active = true; setId("p1") }
        val inactivePatient = Patient().apply { active = false; setId("p2") }

        val result = OperationResult.of(activePatient)
            .add { inactivePatient }
            .addFromMatching<Patient, StringType>("result", Patient::class.java, "active = true") { matches ->
                assertThat(matches, hasSize(1))
                assertThat(matches[0].idPart, `is`("p1"))
                StringType("found")
            }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("result"), hasSize(1))
    }

    @Test
    fun `addFromMatching with no matches passes empty list to builder`() {
        val patient = Patient().apply { active = false }

        val result = OperationResult.of(patient)
            .addFromMatching<Patient, StringType>("result", Patient::class.java, "active = true") { matches ->
                assertThat(matches, empty())
                StringType("none")
            }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("result"), hasSize(1))
    }

    @Test
    fun `addFromMatching without explicit name uses output fhirType as key`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .addFromMatching<Patient, StringType>(expression = "active = true") { StringType("found") }

        // Key is the output fhirType ("string"), NOT the input type name ("patient")
        assertThat(result.getAll("string"), hasSize(1))
        assertThat(result.getAll("patient"), hasSize(1)) // only the original patient, not the StringType
    }

    @Test
    fun `addFromMatching with explicit name stores result under that key`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .addFromMatching("active-patients", Patient::class.java, "active = true") { StringType("ok") }

        assertThat(result.getAll("active-patients"), hasSize(1))
    }

    @Test
    fun `addFromMatching with identifier expression filters correctly`() {
        val patientWithId = Patient().apply {
            active = true
            addIdentifier().apply { system = "http://example.org/mpi"; value = "123" }
        }
        val patientWithoutId = Patient().apply { active = true }

        val result = OperationResult.of(patientWithId)
            .add { patientWithoutId }
            .addFromMatching<Patient, StringType>(
                "has-id",
                Patient::class.java,
                "identifier.where(system = 'http://example.org/mpi').exists()"
            ) { matches ->
                assertThat(matches, hasSize(1))
                StringType("found")
            }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("has-id"), hasSize(1))
    }

    @Test
    fun `addFromMatching with malformed expression records ERROR outcome`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .addFromMatching<Patient, StringType>("out", Patient::class.java, "(((invalid") { StringType("x") }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes(), hasSize(1))
        assertEquals(
            OperationOutcome.IssueSeverity.ERROR,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `addFromMatching filters only resources of the specified type`() {
        val patient = Patient().apply { active = true }
        val observation = Observation().apply { setId("obs1") }

        val result = OperationResult.of(patient)
            .add { observation }
            .addFromMatching<Patient, StringType>("result", Patient::class.java, "active = true") { matches ->
                assertThat(matches, hasSize(1))
                StringType("patients-only")
            }

        assertTrue(result.isSuccessful())
    }

    // ── addAllFromMatching ─────────────────────────────────────────────────

    @Test
    fun `addAllFromMatching returns all matching resources as list`() {
        val p1 = Patient().apply { active = true; setId("p1") }
        val p2 = Patient().apply { active = true; setId("p2") }
        val p3 = Patient().apply { active = false; setId("p3") }

        val result = OperationResult.of(p1)
            .add { p2 }
            .add { p3 }
            .addAllFromMatching<Patient, Patient>("active", Patient::class.java, "active = true") { it }

        assertTrue(result.isSuccessful())
        val patients = result.getResult()
        assertThat(patients, hasSize(2))
    }

    @Test
    fun `addAllFromMatching with explicit name stores under that key`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .addAllFromMatching("active", Patient::class.java, "active = true") { it }

        assertThat(result.getAll("active"), hasSize(1))
    }

    // ── whenPath ──────────────────────────────────────────────────────────

    @Test
    fun `whenPath runs block when expression evaluates to true`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .whenPath("active = true") { add("flag") { StringType("ran") } }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("flag"), hasSize(1))
    }

    @Test
    fun `whenPath skips block when expression evaluates to false`() {
        val patient = Patient().apply { active = false }

        val result = OperationResult.of(patient)
            .whenPath("active = true") { add("flag") { StringType("should-not-run") } }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("flag"), empty())
    }

    @Test
    fun `whenPath skips block silently when head is null`() {
        val result = OperationResult.empty()
            .whenPath("active = true") { add("flag") { StringType("should-not-run") } }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("flag"), empty())
    }

    @Test
    fun `whenPath records WARNING when expression is malformed`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .whenPath("(((invalid") { add("flag") { StringType("x") } }

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertEquals(
            OperationOutcome.IssueSeverity.WARNING,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `whenPath merges parameters from block into result`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .whenPath("active = true") {
                add("flag") { StringType("active") }
                    .add("note") { StringType("ok") }
            }

        assertThat(result.getAll("flag"), hasSize(1))
        assertThat(result.getAll("note"), hasSize(1))
    }

    // ── guardPath ─────────────────────────────────────────────────────────

    @Test
    fun `guardPath passes through when expression evaluates to true`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .guardPath("active = true", "Patient must be active")

        assertTrue(result.isSuccessful())
        assertThat(result.getOutcomes(), empty())
    }

    @Test
    fun `guardPath adds WARNING when expression evaluates to false`() {
        val patient = Patient().apply { active = false }

        val result = OperationResult.of(patient)
            .guardPath("active = true", "Patient must be active")

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        val issue = result.getOutcomes().first().issueFirstRep
        assertEquals(OperationOutcome.IssueSeverity.WARNING, issue.severity)
        assertEquals("Patient must be active", issue.diagnostics)
    }

    @Test
    fun `guardPath adds WARNING when head is null`() {
        val result = OperationResult.empty()
            .guardPath("active = true", "Patient must be active")

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertEquals(
            OperationOutcome.IssueSeverity.WARNING,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `guardPath adds WARNING when expression is malformed`() {
        val patient = Patient().apply { active = true }

        val result = OperationResult.of(patient)
            .guardPath("(((invalid", "guard message")

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertEquals(
            OperationOutcome.IssueSeverity.WARNING,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `guardPath preserves head and pipeline continues in ACCUMULATE mode`() {
        val patient = Patient().apply { active = false }

        val result = OperationResult.of(patient).useErrorStrategy(ErrorStrategy.ACCUMULATE)
            .guardPath("active = true", "not active")
            .add("note") { StringType("after-guard") }

        // WARNING is recorded but pipeline continues because ACCUMULATE does not skip steps
        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertThat(result.getAll("note"), hasSize(1))
    }

    // ── selectByPath ──────────────────────────────────────────────────────

    @Test
    fun `selectByPath promotes first matching sub-element to head`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith"; addGiven("John") }
        }

        val result = OperationResult.of(patient)
            .selectByPath<HumanName>("name.first()")

        assertTrue(result.isSuccessful())
        val name = result.getResult()
        assertThat(name, notNullValue())
        assertEquals("Smith", name.family)
    }

    @Test
    fun `selectByPath stores result under explicit name`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
        }

        val result = OperationResult.of(patient)
            .selectByPath(HumanName::class.java, "name.first()", "official-name")

        assertThat(result.getAll("official-name"), hasSize(1))
    }

    @Test
    fun `selectByPath records ERROR when expression matches nothing`() {
        val patient = Patient()

        val result = OperationResult.of(patient)
            .selectByPath<HumanName>("name.first()")

        assertTrue(result.hasErrors())
        assertEquals(
            OperationOutcome.IssueSeverity.ERROR,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `selectByPath records ERROR when head is null`() {
        val result = OperationResult.empty()
            .selectByPath<HumanName>("name.first()")

        assertTrue(result.hasErrors())
        assertEquals(
            OperationOutcome.IssueSeverity.ERROR,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `selectByPath extracts StringType family name`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith" }
        }

        val result = OperationResult.of(patient)
            .selectByPath<StringType>("name.family")

        assertTrue(result.isSuccessful())
        assertEquals("Smith", result.getResult().value)
    }

    // ── FhirPath builder overloads ────────────────────────────────────────

    @Test
    fun `addFromMatching accepts FhirPath expression`() {
        val activePatient = Patient().apply { active = true; setId("p1") }
        val inactivePatient = Patient().apply { active = false; setId("p2") }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(activePatient)
            .add { inactivePatient }
            .addFromMatching<Patient, StringType>("result", Patient::class.java, expression) { matches ->
                assertThat(matches, hasSize(1))
                assertThat(matches[0].idPart, `is`("p1"))
                StringType("found")
            }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("result"), hasSize(1))
    }

    @Test
    fun `addFromMatching reified accepts FhirPath expression`() {
        val patient = Patient().apply { active = true }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .addFromMatching<Patient, StringType>(expression = expression) { StringType("ok") }

        assertThat(result.getAll("string"), hasSize(1))
    }

    @Test
    fun `addAllFromMatching accepts FhirPath expression`() {
        val p1 = Patient().apply { active = true; setId("p1") }
        val p2 = Patient().apply { active = true; setId("p2") }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(p1)
            .add { p2 }
            .addAllFromMatching("active", Patient::class.java, expression) { it }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("active"), hasSize(2))
    }

    @Test
    fun `addAllFromMatching reified accepts FhirPath expression`() {
        val patient = Patient().apply { active = true }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .addAllFromMatching<Patient, Patient>(expression = expression) { it }

        assertTrue(result.isSuccessful())
        assertThat(result.getResult(), hasSize(1))
    }

    @Test
    fun `whenPath accepts FhirPath expression`() {
        val patient = Patient().apply { active = true }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .whenPath(expression) { add("flag") { StringType("ran") } }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("flag"), hasSize(1))
    }

    @Test
    fun `whenPath with FhirPath skips block when expression is false`() {
        val patient = Patient().apply { active = false }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .whenPath(expression) { add("flag") { StringType("should-not-run") } }

        assertTrue(result.isSuccessful())
        assertThat(result.getAll("flag"), empty())
    }

    @Test
    fun `guardPath accepts FhirPath expression`() {
        val patient = Patient().apply { active = true }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .guardPath(expression, "Patient must be active")

        assertTrue(result.isSuccessful())
        assertThat(result.getOutcomes(), empty())
    }

    @Test
    fun `guardPath with FhirPath records WARNING when expression is false`() {
        val patient = Patient().apply { active = false }
        val expression = FhirPath.relative().navigate("active").eq(true)

        val result = OperationResult.of(patient)
            .guardPath(expression, "Patient must be active")

        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
        assertEquals(OperationOutcome.IssueSeverity.WARNING, result.getOutcomes().first().issueFirstRep.severity)
    }

    @Test
    fun `selectByPath KClass accepts FhirPath expression`() {
        val patient = Patient().apply {
            addName().apply { family = "Smith"; addGiven("John") }
        }
        val expression = FhirPath.relative().navigate("name").first()

        val result = OperationResult.of(patient)
            .selectByPath(HumanName::class.java, expression)

        assertTrue(result.isSuccessful())
        assertEquals("Smith", result.getResult().family)
    }

    @Test
    fun `selectByPath reified accepts FhirPath expression`() {
        val patient = Patient().apply {
            addName().apply { family = "Jones" }
        }
        val expression = FhirPath.relative().navigate("name").first()

        val result = OperationResult.of(patient)
            .selectByPath<HumanName>(expression)

        assertTrue(result.isSuccessful())
        assertEquals("Jones", result.getResult().family)
    }
}
