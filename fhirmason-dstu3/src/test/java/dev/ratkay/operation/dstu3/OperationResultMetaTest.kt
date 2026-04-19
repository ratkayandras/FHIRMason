package dev.ratkay.operation.dstu3

import dev.ratkay.operation.ErrorStrategy
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultMetaTest {

    // ── addFromFiltered (meta tag by system) ──────────────────────────────────

    @Test
    fun `addFromHavingMetaTagWithSystem returns only resources whose tag system matches`() {
        val withTag = patientWithTag("http://example.org/tags", "reviewed")
        val otherSystem = patientWithTag("http://other.org/tags", "reviewed")
        val result = OperationResult.of(listOf(withTag, otherSystem), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithSystem stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem named overload stores result under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addFromFiltered("tagged", Patient::class, FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("tagged"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem reified overload works`() {
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
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaTagWithSystem respects FAIL_FAST`() {
        var builderCalled = false
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients", ErrorStrategy.FAIL_FAST)
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
                throw RuntimeException("first failure")
            }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { _ ->
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
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    // ── addAllFromFiltered (meta tag by system) ───────────────────────────────

    @Test
    fun `addAllFromHavingMetaTagWithSystem returns matching resources as list result`() {
        val a = patientWithTag("http://example.org/tags", "x")
        val b = patientWithTag("http://example.org/tags", "y")
        val other = patientWithTag("http://other.org/tags", "x")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaTagWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "x"), "patients")
            .addAllFromFiltered("tagged-patients", Patient::class, FhirFilter.hasMetaTagWithSystem("http://example.org/tags")) { it }

        assertTrue(result.containsKey("tagged-patients"))
    }

    // ── addFromFiltered (meta tag by code) ────────────────────────────────────

    @Test
    fun `addFromHavingMetaTagWithCode returns only resources whose tag code matches`() {
        val target = patientWithTag("http://example.org/tags", "reviewed")
        val other = patientWithTag("http://example.org/tags", "draft")
        val result = OperationResult.of(listOf(target, other), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTagWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered("reviewed-result", Patient::class, FhirFilter.hasMetaTagWithCode("reviewed")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("reviewed-result"))
    }

    @Test
    fun `addAllFromHavingMetaTagWithCode returns matching resources as list result`() {
        val a = patientWithTag("http://sys1.org", "reviewed")
        val b = patientWithTag("http://sys2.org", "reviewed")
        val other = patientWithTag("http://sys1.org", "draft")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTagWithCode("reviewed")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromFiltered (meta tag system + code) ──────────────────────────────

    @Test
    fun `addFromHavingMetaTag returns only resources matching both system and code`() {
        val exact = patientWithTag("http://example.org/tags", "reviewed")
        val wrongCode = patientWithTag("http://example.org/tags", "draft")
        val wrongSystem = patientWithTag("http://other.org/tags", "reviewed")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag matches if any tag satisfies both fields`() {
        val multiTag = Patient().apply {
            meta.addTag().apply { system = "http://other.org/tags"; code = "other" }
            meta.addTag().apply { system = "http://example.org/tags"; code = "reviewed" }
        }
        val result = OperationResult.of(multiTag, "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaTag named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithTag("http://example.org/tags", "reviewed"), "patients")
            .addFromFiltered("matched", Patient::class, FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("matched"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaTag reified overload works`() {
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
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { _ ->
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
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaTag("http://example.org/tags", "reviewed")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromFiltered (meta security by system) ─────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithSystem returns only resources whose security system matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val other = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(restricted, other), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithSystem named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted", Patient::class, FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { _ -> OperationOutcome() }

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
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurityWithSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromFiltered (meta security by code) ───────────────────────────────

    @Test
    fun `addFromHavingMetaSecurityWithCode returns only resources whose security code matches`() {
        val restricted = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val normal = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val result = OperationResult.of(listOf(restricted, normal), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurityWithCode("R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurityWithCode named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted-result", Patient::class, FhirFilter.hasMetaSecurityWithCode("R")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("restricted-result"))
    }

    @Test
    fun `addAllFromHavingMetaSecurityWithCode returns matching resources as list result`() {
        val a = patientWithSecurity("http://sys1.org", "R")
        val b = patientWithSecurity("http://sys2.org", "R")
        val other = patientWithSecurity("http://sys1.org", "N")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurityWithCode("R")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromFiltered (meta security system + code) ─────────────────────────

    @Test
    fun `addFromHavingMetaSecurity returns only resources matching both system and code`() {
        val exact = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")
        val wrongCode = patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "N")
        val wrongSystem = patientWithSecurity("http://other.org/security", "R")
        val result = OperationResult.of(listOf(exact, wrongCode, wrongSystem), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity matches if any security label satisfies both fields`() {
        val multiSec = Patient().apply {
            meta.addSecurity().apply { system = "http://other.org/security"; code = "other" }
            meta.addSecurity().apply { system = "http://terminology.hl7.org/CodeSystem/v3-ActCode"; code = "R" }
        }
        val result = OperationResult.of(multiSec, "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaSecurity named overload stores under explicit key`() {
        val result = OperationResult.of(patientWithSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R"), "patients")
            .addFromFiltered("restricted", Patient::class, FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { _ -> OperationOutcome() }

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
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { _ ->
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
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaSecurity("http://terminology.hl7.org/CodeSystem/v3-ActCode", "R")) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    // ── addFromFiltered (meta profile) ────────────────────────────────────────

    @Test
    fun `addFromHavingMetaProfile returns only resources declaring the given profile URL`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")
        val other = patientWithProfile("http://example.org/fhir/StructureDefinition/custom-patient")
        val result = OperationResult.of(listOf(conforming, other), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile with no matches passes empty list to builder`() {
        val result = OperationResult.of(patientNoMeta(), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertTrue(result.isSuccessful())
        assertEquals("count=0", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile stores result under fhirType when name is null`() {
        val result = OperationResult.of(patientWithProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("operationoutcome"))
        assertFalse(result.containsKey("patient"))
    }

    @Test
    fun `addFromHavingMetaProfile named overload stores result under explicit key`() {
        val result = OperationResult.of(patientWithProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient"), "patients")
            .addFromFiltered("us-core-result", Patient::class, FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { _ -> OperationOutcome() }

        assertTrue(result.containsKey("us-core-result"))
        assertFalse(result.containsKey("operationoutcome"))
    }

    @Test
    fun `addFromHavingMetaProfile reified overload works`() {
        val conforming = patientWithProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")
        val result = OperationResult.of(listOf(conforming, patientNoMeta()), "patients")
            .addFromFiltered<Patient, OperationOutcome>(predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile matches if any profile in list equals url`() {
        val multiProfile = Patient().apply {
            meta.addProfile("http://example.org/fhir/StructureDefinition/custom-patient")
            meta.addProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")
        }
        val result = OperationResult.of(multiProfile, "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=1", result.getResult().issueFirstRep.diagnostics)
    }

    @Test
    fun `addFromHavingMetaProfile records ERROR outcome when builder throws`() {
        val result = OperationResult.of(patientWithProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient"), "patients")
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile("http://hl7.org/fhir/StructureDefinition/us-core-patient")) { _ ->
                throw RuntimeException("builder failed")
            }

        assertTrue(result.hasErrors())
        assertThat(result.getOutcomes().first().issueFirstRep.severity, `is`(OperationOutcome.IssueSeverity.ERROR))
    }

    @Test
    fun `addFromHavingMetaProfile respects FAIL_FAST`() {
        var builderCalled = false
        val url = "http://hl7.org/fhir/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients", ErrorStrategy.FAIL_FAST)
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile(url)) { _ -> throw RuntimeException("first failure") }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile(url)) { _ -> builderCalled = true; OperationOutcome() }

        assertFalse(builderCalled)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addFromHavingMetaProfile searches across all parameter keys`() {
        val url = "http://hl7.org/fhir/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "group-a")
            .add("group-b") { patientWithProfile(url) }
            .addFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile(url)) { filtered ->
                OperationOutcome().apply { addIssue().diagnostics = "count=${filtered.size}" }
            }

        assertEquals("count=2", result.getResult().issueFirstRep.diagnostics)
    }

    // ── addAllFromFiltered (meta profile) ─────────────────────────────────────

    @Test
    fun `addAllFromHavingMetaProfile returns matching resources as list result`() {
        val url = "http://hl7.org/fhir/StructureDefinition/us-core-patient"
        val a = patientWithProfile(url)
        val b = patientWithProfile(url)
        val other = patientWithProfile("http://example.org/fhir/StructureDefinition/other")
        val result = OperationResult.of(listOf(a, b, other), "patients")
            .addAllFromFiltered(type = Patient::class, predicate = FhirFilter.hasMetaProfile(url)) { it }

        assertThat(result.getResult(), hasSize(2))
    }

    @Test
    fun `addAllFromHavingMetaProfile named overload stores under explicit key`() {
        val url = "http://hl7.org/fhir/StructureDefinition/us-core-patient"
        val result = OperationResult.of(patientWithProfile(url), "patients")
            .addAllFromFiltered("us-core-patients", Patient::class, FhirFilter.hasMetaProfile(url)) { it }

        assertTrue(result.containsKey("us-core-patients"))
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
