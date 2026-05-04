package dev.ratkay.operation.r4

import dev.ratkay.operation.ErrorStrategy
import dev.ratkay.operation.r4.FhirFilter
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultMetaTest {

    // ── addFromHavingMetaTagWithSystem ────────────────────────────────────────

    @Test
    fun `addFromHavingMetaTagWithSystem returns only resources whose tag system matches`() {
        val withTag = patientWithTag("http://example.org/tags", "reviewed")
        val otherSystem = patientWithTag("http://other.org/tags", "reviewed")
        val result = OperationResult.of(listOf(withTag, otherSystem), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem with multiple matching resources passes all to builder`() {
        val a = patientWithTag("http://example.org/tags", "a")
        val b = patientWithTag("http://example.org/tags", "b")
        val result = OperationResult.of(listOf(a, b), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem named overload stores result under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered("tagged", Patient::class.java, FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("tagged"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem reified overload works without KClass argument`() {
        val withTag = patientWithTag("http://example.org/tags", "x")
        val result = OperationResult.of(listOf(withTag, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem respects FAIL_FAST and skips when already errored`() {
        var builderCalled = false
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                throw RuntimeException("first failure")
            }
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                builderCalled = true
                OperationOutcome()
            }

        assertFalse(builderCalled)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addFromHavingMetaTagWithSystem searches across all parameter keys`() {
        val a = patientWithTag("http://example.org/tags", "x")
        val b = patientWithTag("http://example.org/tags", "y")
        val result = OperationResult.of(a, "group-a")
            .add("group-b") { b }
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    // ── addAllFromHavingMetaTagWithSystem ─────────────────────────────────────

    @Test
    fun `addAllFromHavingMetaTagWithSystem returns matching resources as list result`() {
        val a = patientWithTag("http://example.org/tags", "x")
        val b = patientWithTag("http://example.org/tags", "y")
        val other = patientWithTag("http://other.org/tags", "x")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaTagWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addAllFromFiltered("tagged-patients", Patient::class.java, FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { it }

        assertTrue(result.containsKey("tagged-patients"))
    }

    @Test
    fun `addAllFromHavingMetaTagWithSystem reified overload works`() {
        val a = patientWithTag("http://example.org/tags", "x")
        val result = OperationResult.of(listOf(a, patientNoMeta()), "patients")
            .addAllFromFiltered<Patient, Patient>(predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── addFromHavingMetaTagWithCode ──────────────────────────────────────────

    @Test
    fun `addFromHavingMetaTagWithCode returns only resources whose tag code matches`() {
        val target = patientWithTag("http://example.org/tags", "reviewed")
        val other = patientWithTag("http://example.org/tags", "draft")
        val result = OperationResult.of(listOf(target, other), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithCode with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered("reviewed-result", Patient::class.java, FhirFilter.hasMetaTagWithCode("reviewed")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("reviewed-result"))
    }

    @Test
    fun `addFromHavingMetaTagWithCode reified overload works`() {
        val target = patientWithTag("http://example.org/tags", "reviewed")
        val result = OperationResult.of(listOf(target, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingMetaTagWithCode returns matching resources as list result`() {
        val a = patientWithTag("http://sys1.org", "reviewed")
        val b = patientWithTag("http://sys2.org", "reviewed")
        val other = patientWithTag("http://sys1.org", "draft")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaTag (system + code) ──────────────────────────────────

    @Test
    fun `addFromHavingMetaTag returns only resources matching both system and code`() {
        val exact = patientWithTag("http://example.org/tags", "reviewed")
        val wrongCode = patientWithTag("http://example.org/tags", "draft")
        val wrongSystem = patientWithTag("http://other.org/tags", "reviewed")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag does not match when system matches but code does not`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "draft"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag does not match when code matches but system does not`() {
        val result = OperationResult.of(patientWithTag("http://other.org/tags", "reviewed"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag matches if any tag on resource satisfies both fields`() {
        val multiTag = Patient().apply {
            meta.addTag().apply { system = "http://other.org/tags"; code = "other" }
            meta.addTag().apply { system = "http://example.org/tags"; code = "reviewed" }
        }
        val result = OperationResult.of(multiTag, "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaTag named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered("matched", Patient::class.java, FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("matched"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTag reified overload works without KClass argument`() {
        val exact = patientWithTag("http://example.org/tags", "reviewed")
        val result = OperationResult.of(listOf(exact, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addAllFromHavingMetaTag returns matching resources as list result`() {
        val a = patientWithTag("http://example.org/tags", "reviewed")
        val b = patientWithTag("http://example.org/tags", "reviewed")
        val other = patientWithTag("http://example.org/tags", "draft")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaTag named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addAllFromFiltered("exact-matches", Patient::class.java, FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { it }

        assertTrue(result.containsKey("exact-matches"))
    }

    // ── addFromHavingMetaSecurityWithSystem ───────────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithSystem returns only resources whose security system matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val other = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(restricted, other), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted", Patient::class.java, FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("restricted"))
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem reified overload works`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(restricted, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingMetaSecurityWithSystem returns matching resources as list result`() {
        val a = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val b = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val other = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaSecurityWithCode ─────────────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithCode returns only resources whose security code matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val normal = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val result = OperationResult.of(listOf(restricted, normal), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurityWithCode("R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted-result", Patient::class.java, FhirFilter.hasMetaSecurityWithCode("R")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("restricted-result"))
    }

    @Test
    fun `addFromHavingMetaSecurityWithCode reified overload works`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(restricted, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaSecurityWithCode("R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addAllFromHavingMetaSecurityWithCode returns matching resources as list result`() {
        val a = patientWithSecurity("http://sys1.org", "R")
        val b = patientWithSecurity("http://sys2.org", "R")
        val other = patientWithSecurity("http://sys1.org", "N")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurityWithCode("R")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaSecurity (system + code) ─────────────────────────────

    @Test
    fun `addFromHavingMetaSecurity returns only resources matching both system and code`() {
        val exact = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val wrongCode = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val wrongSystem = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity does not match when system matches but code does not`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity matches if any security label on resource satisfies both fields`() {
        val multiSec = Patient().apply {
            meta.addSecurity().apply { system = "http://other.org/security"; code = "other" }
            meta.addSecurity().apply { system = "http://terminology.hl7.org/CodeSystem/v3-ActCode"; code = "R" }
        }
        val result = OperationResult.of(multiSec, "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted", Patient::class.java, FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("restricted"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaSecurity reified overload works`() {
        val exact = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(exact, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addAllFromHavingMetaSecurity returns matching resources as list result`() {
        val a = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val b = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val other = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaSecurity named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addAllFromFiltered("restricted-patients", Patient::class.java, FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { it }

        assertTrue(result.containsKey("restricted-patients"))
    }

    // ── addFromHavingMetaProfile ──────────────────────────────────────────────

    @Test
    fun `addFromHavingMetaProfile returns only resources declaring the given profile URL`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val other = patientWithProfile("http://example.org/fhir/StructureDefinition/custom-patient")
        val result = OperationResult.of(listOf(conforming, other), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile with multiple matching resources passes all to builder`() {
        val a = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val b = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val result = OperationResult.of(listOf(a, b), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile stores result under fhirType when name is null`() {
        val result = OperationResult.of(
            patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"), "patients"
        )
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaProfile named overload stores result under explicit key`() {
        val result = OperationResult.of(
            patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"), "patients"
        )
            .addFromFiltered("us-core-result", Patient::class.java, FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("us-core-result"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaProfile reified overload works without KClass argument`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val result = OperationResult.of(listOf(conforming, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile matches if any profile in list equals url`() {
        val multiProfile = Patient().apply {
            meta.addProfile("http://example.org/fhir/StructureDefinition/custom-patient")
            meta.addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        }
        val result = OperationResult.of(multiProfile, "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile records ERROR outcome when builder throws`() {
        val result = OperationResult.of(
            patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"), "patients"
        )
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaProfile respects FAIL_FAST and skips when already errored`() {
        var builderCalled = false
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile(url)) { _ ->
                throw RuntimeException("first failure")
            }
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile(url)) { _ ->
                builderCalled = true
                OperationOutcome()
            }

        assertFalse(builderCalled)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addFromHavingMetaProfile searches across all parameter keys`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val a = patientWithProfile(url)
        val b = patientWithProfile(url)
        val result = OperationResult.of(a, "group-a")
            .add("group-b") { b }
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile(url)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    // ── addAllFromHavingMetaProfile ───────────────────────────────────────────

    @Test
    fun `addAllFromHavingMetaProfile returns matching resources as list result`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val a = patientWithProfile(url)
        val b = patientWithProfile(url)
        val other = patientWithProfile("http://example.org/fhir/StructureDefinition/other")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile(url)) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaProfile named overload stores under explicit key`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients")
            .addAllFromFiltered("us-core-patients", Patient::class.java, FhirFilter.hasMetaProfile(url)) { it }

        assertTrue(result.containsKey("us-core-patients"))
    }

    @Test
    fun `addAllFromHavingMetaProfile reified overload works`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(listOf(patientWithProfile(url), patientNoMeta()), "patients")
            .addAllFromFiltered<Patient, Patient>(predicate = FhirFilter.hasMetaProfile(url)) { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── Cross-cutting ─────────────────────────────────────────────────────────

    @Test
    fun `meta filter methods compose with subsequent pipeline steps`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(listOf(patientWithProfile(url), patientNoMeta()), "patients")
            .addFromFiltered(type = Patient::class.java, predicate = FhirFilter.hasMetaProfile(url)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }
            .add("flag") { org.hl7.fhir.r4.model.StringType("done") }

        assertTrue(result.isSuccessful())
        assertTrue(result.containsKey("flag"))
        assertTrue(result.containsKey("operationoutcome"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patientWithTag(system: String, code: String) = Patient().apply {
        meta.addTag().apply { this.system = system; this.code = code }
    }

    private fun patientWithSecurity(system: String, code: String) = Patient().apply {
        meta.addSecurity().apply { this.system = system; this.code = code }
    }

    private fun patientWithProfile(url: String) = Patient().apply {
        meta.addProfile(url)
    }

    private fun patientNoMeta() = Patient().apply {
        addName().family = "NoMeta"
    }
}
