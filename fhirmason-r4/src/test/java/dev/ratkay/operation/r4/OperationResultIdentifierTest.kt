package dev.ratkay.operation.r4

import dev.ratkay.operation.ErrorStrategy
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultIdentifierTest {

    // ── addFromHavingIdentifierWithSystem ─────────────────────────────────────

    @Test
    fun `addFromHavingIdentifierWithSystem returns only resources whose identifier system matches`() {
        val withSystem = patient("http://example.org/mrn", "12345")
        val otherSystem = patient("http://other.org/id", "12345")
        val result = OperationResult.of(listOf(withSystem, otherSystem), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithSystem with multiple matching resources passes all to builder`() {
        val a = patient("http://example.org/mrn", "001")
        val b = patient("http://example.org/mrn", "002")
        val c = patient("http://example.org/mrn", "003")
        val result = OperationResult.of(listOf(a, b, c), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=3", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patient("http://other.org/id", "001"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithSystem excludes resources without identifier property`() {
        val withMrn = patient("http://example.org/mrn", "001")
        val noId = patientNoIdentifier()
        val result = OperationResult.of(listOf(withMrn, noId), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithSystem stores result under fhirType when name is null`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "001"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        assertFalse(result.containsKey("patient"))
        assertTrue(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingIdentifierWithSystem named overload stores result under explicit key`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "001"), "patients")
            .addFromFiltered("mrn-summary", Patient::class, FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("mrn-summary"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingIdentifierWithSystem reified overload works without KClass argument`() {
        val withSystem = patient("http://example.org/mrn", "001")
        val result = OperationResult.of(listOf(withSystem, patientNoIdentifier()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithSystem records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "001"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        val issue = result.getOutcomes().first().issueFirstRep
        assertThat(issue.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingIdentifierWithSystem respects FAIL_FAST and skips when already errored`() {
        var builderCalled = false
        val result = OperationResult.of(patient("http://example.org/mrn", "001"), "patients", ErrorStrategy.FAIL_FAST)
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { _ ->
                throw RuntimeException("first failure")
            }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { _ ->
                builderCalled = true
                OperationOutcome()
            }

        assertFalse(builderCalled)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addFromHavingIdentifierWithSystem searches across all parameter keys`() {
        val a = patient("http://example.org/mrn", "001")
        val b = patient("http://example.org/mrn", "002")
        val result = OperationResult.of(a, "group-a")
            .add("group-b") { b }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    // ── addAllFromHavingIdentifierWithSystem ──────────────────────────────────

    @Test
    fun `addAllFromHavingIdentifierWithSystem returns matching resources as list result`() {
        val a = patient("http://example.org/mrn", "001")
        val b = patient("http://example.org/mrn", "002")
        val other = patient("http://other.org/id", "003")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingIdentifierWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "001"), "patients")
            .addAllFromFiltered("mrn-patients", Patient::class, FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { it }

        assertTrue(result.containsKey("mrn-patients"))
    }

    @Test
    fun `addAllFromHavingIdentifierWithSystem reified overload works`() {
        val a = patient("http://example.org/mrn", "001")
        val result = OperationResult.of(listOf(a, patientNoIdentifier()), "patients")
            .addAllFromFiltered<Patient, Patient>(predicate = FhirFilter.hasIdentifierWithSystem("http://example.org/mrn")) { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── addFromHavingIdentifierWithValue ──────────────────────────────────────

    @Test
    fun `addFromHavingIdentifierWithValue returns only resources whose identifier value matches`() {
        val target = patient("http://example.org/mrn", "12345")
        val other = patient("http://example.org/mrn", "99999")
        val result = OperationResult.of(listOf(target, other), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithValue("12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithValue with no matches passes empty list to builder`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "99999"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithValue("12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithValue excludes resources without identifier property`() {
        val withValue = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(withValue, patientNoIdentifier()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithValue("12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifierWithValue stores result under fhirType when name is null`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithValue("12345")) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingIdentifierWithValue named overload stores under explicit key`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addFromFiltered("value-result", Patient::class, FhirFilter.hasIdentifierWithValue("12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("value-result"))
    }

    @Test
    fun `addFromHavingIdentifierWithValue reified overload works without KClass argument`() {
        val target = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(target, patientNoIdentifier()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasIdentifierWithValue("12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingIdentifierWithValue returns matching resources as list result`() {
        val a = patient("http://example.org/mrn", "12345")
        val b = patient("http://other.org/id", "12345")
        val other = patient("http://example.org/mrn", "99999")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifierWithValue("12345")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingIdentifier (system + value) ──────────────────────────────

    @Test
    fun `addFromHavingIdentifier returns only resources matching both system and value`() {
        val exact = patient("http://example.org/mrn", "12345")
        val wrongValue = patient("http://example.org/mrn", "99999")
        val wrongSystem = patient("http://other.org/id", "12345")
        val result = OperationResult.of(listOf(exact, wrongValue, wrongSystem), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier does not match when system matches but value does not`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "99999"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier does not match when value matches but system does not`() {
        val result = OperationResult.of(patient("http://other.org/id", "12345"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier matches if any identifier on resource satisfies both fields`() {
        val multiId = Patient().apply {
            addIdentifier().apply { system = "http://other.org/id"; value = "999" }
            addIdentifier().apply { system = "http://example.org/mrn"; value = "12345" }
        }
        val result = OperationResult.of(multiId, "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoIdentifier(), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier excludes resources without identifier property`() {
        val exact = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(exact, patientNoIdentifier()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier stores result under fhirType when name is null`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { _ ->
                OperationOutcome().apply { addIssue().diagnostics = "found" }
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingIdentifier named overload stores under explicit key`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addFromFiltered("matched-patient", Patient::class, FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.containsKey("matched-patient"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingIdentifier reified overload works without KClass argument`() {
        val exact = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(exact, patientNoIdentifier()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingIdentifier records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        val issue = result.getOutcomes().first().issueFirstRep
        assertThat(issue.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    // ── addAllFromHavingIdentifier ────────────────────────────────────────────

    @Test
    fun `addAllFromHavingIdentifier returns matching resources as list result`() {
        val a = patient("http://example.org/mrn", "12345")
        val b = patient("http://example.org/mrn", "12345")
        val other = patient("http://example.org/mrn", "99999")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingIdentifier named overload stores under explicit key`() {
        val result = OperationResult.of(patient("http://example.org/mrn", "12345"), "patients")
            .addAllFromFiltered("exact-matches", Patient::class, FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { it }

        assertTrue(result.containsKey("exact-matches"))
    }

    @Test
    fun `addAllFromHavingIdentifier reified overload works`() {
        val a = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(a, patientNoIdentifier()), "patients")
            .addAllFromFiltered<Patient, Patient>(predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── Cross-cutting ─────────────────────────────────────────────────────────

    @Test
    fun `identifier filter methods compose with subsequent pipeline steps`() {
        val withMrn = patient("http://example.org/mrn", "12345")
        val result = OperationResult.of(listOf(withMrn, patientNoIdentifier()), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasIdentifier("http://example.org/mrn", "12345")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }
            .add("flag") { org.hl7.fhir.r4.model.StringType("done") }

        assertTrue(result.isSuccessful())
        assertTrue(result.containsKey("flag"))
        assertTrue(result.containsKey("operationoutcome"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient(system: String, value: String) = Patient().apply {
        addIdentifier().apply {
            this.system = system
            this.value = value
        }
    }

    private fun patientNoIdentifier() = Patient().apply {
        addName().apply { family = "NoId" }
    }
}
