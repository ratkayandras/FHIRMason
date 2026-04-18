package dev.ratkay.operation.r4

import dev.ratkay.operation.ErrorStrategy
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
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { filtered ->
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
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem named overload stores result under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromHavingMetaTagWithSystem("tagged", Patient::class, "http://example.org/tags") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("tagged"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem reified overload works without KClass argument`() {
        val withTag = patientWithTag("http://example.org/tags", "x")
        val result = OperationResult.of(listOf(withTag, patientNoMeta()), "patients")
            .addFromHavingMetaTagWithSystem<Patient, OperationOutcome>("http://example.org/tags") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem respects FAIL_FAST and skips when already errored`() {
        var builderCalled = false
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients", ErrorStrategy.FAIL_FAST)
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { _ ->
                throw RuntimeException("first failure")
            }
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { _ ->
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
            .addFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { filtered ->
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
            .addAllFromHavingMetaTagWithSystem(Patient::class, "http://example.org/tags") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaTagWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addAllFromHavingMetaTagWithSystem("tagged-patients", Patient::class, "http://example.org/tags") { it }

        assertTrue(result.containsKey("tagged-patients"))
    }

    @Test
    fun `addAllFromHavingMetaTagWithSystem reified overload works`() {
        val a = patientWithTag("http://example.org/tags", "x")
        val result = OperationResult.of(listOf(a, patientNoMeta()), "patients")
            .addAllFromHavingMetaTagWithSystem<Patient, Patient>("http://example.org/tags") { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── addFromHavingMetaTagWithCode ──────────────────────────────────────────

    @Test
    fun `addFromHavingMetaTagWithCode returns only resources whose tag code matches`() {
        val target = patientWithTag("http://example.org/tags", "reviewed")
        val other = patientWithTag("http://example.org/tags", "draft")
        val result = OperationResult.of(listOf(target, other), "patients")
            .addFromHavingMetaTagWithCode(Patient::class, "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithCode with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromHavingMetaTagWithCode(Patient::class, "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromHavingMetaTagWithCode("reviewed-result", Patient::class, "reviewed") { _ -> OperationOutcome() }

        assertTrue(result.containsKey("reviewed-result"))
    }

    @Test
    fun `addFromHavingMetaTagWithCode reified overload works`() {
        val target = patientWithTag("http://example.org/tags", "reviewed")
        val result = OperationResult.of(listOf(target, patientNoMeta()), "patients")
            .addFromHavingMetaTagWithCode<Patient, OperationOutcome>("reviewed") { filtered ->
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
            .addAllFromHavingMetaTagWithCode(Patient::class, "reviewed") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaTag (system + code) ──────────────────────────────────

    @Test
    fun `addFromHavingMetaTag returns only resources matching both system and code`() {
        val exact = patientWithTag("http://example.org/tags", "reviewed")
        val wrongCode = patientWithTag("http://example.org/tags", "draft")
        val wrongSystem = patientWithTag("http://other.org/tags", "reviewed")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag does not match when system matches but code does not`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "draft"), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag does not match when code matches but system does not`() {
        val result = OperationResult.of(patientWithTag("http://other.org/tags", "reviewed"), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { filtered ->
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
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { _ -> OperationOutcome() }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaTag named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromHavingMetaTag("matched", Patient::class, "http://example.org/tags", "reviewed") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("matched"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTag reified overload works without KClass argument`() {
        val exact = patientWithTag("http://example.org/tags", "reviewed")
        val result = OperationResult.of(listOf(exact, patientNoMeta()), "patients")
            .addFromHavingMetaTag<Patient, OperationOutcome>("http://example.org/tags", "reviewed") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { _ ->
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
            .addAllFromHavingMetaTag(Patient::class, "http://example.org/tags", "reviewed") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaTag named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addAllFromHavingMetaTag("exact-matches", Patient::class, "http://example.org/tags", "reviewed") { it }

        assertTrue(result.containsKey("exact-matches"))
    }

    // ── addFromHavingMetaSecurityWithSystem ───────────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithSystem returns only resources whose security system matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val other = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(restricted, other), "patients")
            .addFromHavingMetaSecurityWithSystem(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromHavingMetaSecurityWithSystem(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromHavingMetaSecurityWithSystem("restricted", Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("restricted"))
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem reified overload works`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(restricted, patientNoMeta()), "patients")
            .addFromHavingMetaSecurityWithSystem<Patient, OperationOutcome>("http://terminology.hl7.org/CodeSystem/v3-ActCode") { filtered ->
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
            .addAllFromHavingMetaSecurityWithSystem(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaSecurityWithCode ─────────────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithCode returns only resources whose security code matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val normal = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val result = OperationResult.of(listOf(restricted, normal), "patients")
            .addFromHavingMetaSecurityWithCode(Patient::class, "R") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromHavingMetaSecurityWithCode("restricted-result", Patient::class, "R") { _ -> OperationOutcome() }

        assertTrue(result.containsKey("restricted-result"))
    }

    @Test
    fun `addFromHavingMetaSecurityWithCode reified overload works`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(restricted, patientNoMeta()), "patients")
            .addFromHavingMetaSecurityWithCode<Patient, OperationOutcome>("R") { filtered ->
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
            .addAllFromHavingMetaSecurityWithCode(Patient::class, "R") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromHavingMetaSecurity (system + code) ─────────────────────────────

    @Test
    fun `addFromHavingMetaSecurity returns only resources matching both system and code`() {
        val exact = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val wrongCode = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val wrongSystem = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromHavingMetaSecurity(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity does not match when system matches but code does not`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N"), "patients")
            .addFromHavingMetaSecurity(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { filtered ->
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
            .addFromHavingMetaSecurity(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromHavingMetaSecurity("restricted", Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("restricted"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaSecurity reified overload works`() {
        val exact = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val result = OperationResult.of(listOf(exact, patientNoMeta()), "patients")
            .addFromHavingMetaSecurity<Patient, OperationOutcome>("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromHavingMetaSecurity(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { _ ->
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
            .addAllFromHavingMetaSecurity(Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaSecurity named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addAllFromHavingMetaSecurity("restricted-patients", Patient::class, "http://terminology.hl7.org/CodeSystem/v3-ActCode", "R") { it }

        assertTrue(result.containsKey("restricted-patients"))
    }

    // ── addFromHavingMetaProfile ──────────────────────────────────────────────

    @Test
    fun `addFromHavingMetaProfile returns only resources declaring the given profile URL`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val other = patientWithProfile("http://example.org/fhir/StructureDefinition/custom-patient")
        val result = OperationResult.of(listOf(conforming, other), "patients")
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile with multiple matching resources passes all to builder`() {
        val a = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val b = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val result = OperationResult.of(listOf(a, b), "patients")
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { filtered ->
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
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { _ ->
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
            .addFromHavingMetaProfile("us-core-result", Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { _ ->
                OperationOutcome()
            }

        assertTrue(result.containsKey("us-core-result"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaProfile reified overload works without KClass argument`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
        val result = OperationResult.of(listOf(conforming, patientNoMeta()), "patients")
            .addFromHavingMetaProfile<Patient, OperationOutcome>("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { filtered ->
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
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile records ERROR outcome when builder throws`() {
        val result = OperationResult.of(
            patientWithProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"), "patients"
        )
            .addFromHavingMetaProfile(Patient::class, "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient") { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaProfile respects FAIL_FAST and skips when already errored`() {
        var builderCalled = false
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients", ErrorStrategy.FAIL_FAST)
            .addFromHavingMetaProfile(Patient::class, url) { _ ->
                throw RuntimeException("first failure")
            }
            .addFromHavingMetaProfile(Patient::class, url) { _ ->
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
            .addFromHavingMetaProfile(Patient::class, url) { filtered ->
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
            .addAllFromHavingMetaProfile(Patient::class, url) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaProfile named overload stores under explicit key`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients")
            .addAllFromHavingMetaProfile("us-core-patients", Patient::class, url) { it }

        assertTrue(result.containsKey("us-core-patients"))
    }

    @Test
    fun `addAllFromHavingMetaProfile reified overload works`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(listOf(patientWithProfile(url), patientNoMeta()), "patients")
            .addAllFromHavingMetaProfile<Patient, Patient>(url) { it }

        assertThat(result.getResult(), hasSize(1))
    }

    // ── Cross-cutting ─────────────────────────────────────────────────────────

    @Test
    fun `meta filter methods compose with subsequent pipeline steps`() {
        val url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
        val result = OperationResult.of(listOf(patientWithProfile(url), patientNoMeta()), "patients")
            .addFromHavingMetaProfile(Patient::class, url) { filtered ->
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
