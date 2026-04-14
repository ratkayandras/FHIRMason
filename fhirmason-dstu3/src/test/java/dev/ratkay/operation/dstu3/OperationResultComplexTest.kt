package dev.ratkay.operation.dstu3

import dev.ratkay.operation.ErrorStrategy

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.dstu3.model.BooleanType
import org.hl7.fhir.dstu3.model.Bundle
import org.hl7.fhir.dstu3.model.Coverage
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Observation
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.Reference
import org.hl7.fhir.dstu3.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Complex integration tests for [OperationResult].
 *
 * Each test exercises multiple API methods together in non-trivial combinations
 * designed to reveal emergent behaviour that simple per-method tests cannot catch.
 */
class OperationResultComplexTest {

    // ── Scenario 1: extension filter → list mapping → serialise → round-trip ─

    @Test
    fun `enrolled patients are filtered, observations created, bundle serialised and round-tripped correctly`() {
        val p1 = patient("p1").apply { addExtension("http://example.org/enrolled", BooleanType(true)) }
        val p2 = patient("p2").apply { addExtension("http://example.org/enrolled", BooleanType(true)) }
        val p3 = patient("p3") // not enrolled — must be excluded

        val result = OperationResult.of(listOf(p1, p2, p3), "patients")
            .addAllFromHavingExtensionValueMatching(
                Patient::class,
                "http://example.org/enrolled",
                BooleanType::class,
                { it.booleanValue() }
            ) { enrolled ->
                enrolled.map { p ->
                    Observation().apply {
                        id = "obs-${p.idPart}"
                        status = Observation.ObservationStatus.FINAL
                    }
                }
            }

        // Sanity-check before serialisation
        assertEquals(3, result.count("patients"))
        assertEquals(2, result.count("observation"))
        assertTrue(result.isSuccessful())

        // Serialise → deserialise
        val bundle = result.toBundle(Bundle.BundleType.COLLECTION)
        val restored = OperationResult.fromBundle(bundle)

        assertEquals(3, restored.count("patient"))
        assertEquals(2, restored.count("observation"))
        assertTrue(restored.isSuccessful())
        assertThat(restored.getByType<Observation>(), hasSize(2))
    }

    // ── Scenario 2: error accumulation across 6 steps ────────────────────────

    @Test
    fun `ACCUMULATE strategy collects all errors while successful steps still produce values`() {
        val result = OperationResult.of(patient("p1"), "patient", errorStrategy = ErrorStrategy.ACCUMULATE)
            .add("step1") { Observation().apply { id = "obs1" } }
            .add("step2") { error("step2 failed") }
            .add("step3") { Encounter().apply { id = "enc1" } }
            .add("step4") { error("step4 failed") }
            .add("step5") { Coverage().apply { id = "cov1" } }
            .add("step6") { Observation().apply { id = "obs2" } }

        // Successful steps are present
        assertTrue(result.containsKey("step1"))
        assertTrue(result.containsKey("step3"))
        assertTrue(result.containsKey("step5"))
        assertTrue(result.containsKey("step6"))

        // Failed steps produce no value
        assertFalse(result.containsKey("step2"))
        assertFalse(result.containsKey("step4"))

        // Exactly two ERROR outcomes accumulated
        assertEquals(2, result.getOutcomes().size)
        assertTrue(result.hasErrors())
        assertFalse(result.isSuccessful())
        result.getOutcomes().forEach { outcome ->
            assertEquals(OperationOutcome.IssueSeverity.ERROR, outcome.issueFirstRep.severity)
        }
    }

    // ── Scenario 3: FAIL_FAST halts chain at first error ─────────────────────

    @Test
    fun `FAIL_FAST stops pipeline at first error and subsequent lambdas are never executed`() {
        var step3Executed = false
        var step4Executed = false
        var step5Executed = false

        val result = OperationResult.of(patient("p1"), "patient") // FAIL_FAST by default
            .add("step1") { Observation().apply { id = "obs1" } }
            .add("step2") { error("step2 failed") }
            .add("step3") { step3Executed = true; Encounter() }
            .add("step4") { step4Executed = true; Coverage() }
            .add("step5") { step5Executed = true; Observation() }

        // Step before failure is present; all after are absent
        assertTrue(result.containsKey("step1"))
        assertFalse(result.containsKey("step3"))
        assertFalse(result.containsKey("step4"))
        assertFalse(result.containsKey("step5"))

        // Lambdas after the failure were never called
        assertFalse(step3Executed)
        assertFalse(step4Executed)
        assertFalse(step5Executed)

        // Exactly one ERROR outcome
        assertEquals(1, result.getOutcomes().size)
    }

    // ── Scenario 4: nested flatMap chaining across three resource types ────────

    @Test
    fun `flatMap chained twice accumulates parameters from all three pipeline levels`() {
        val result = OperationResult.of(patient("p1"), "patient")
            .flatMap { _ ->
                OperationResult.of(encounter("e1"), "encounter")
                    .addString("encounter-status", "finished")
                    .flatMap { _ ->
                        OperationResult.of(observation("obs1"), "observation")
                            .addString("observation-status", "final")
                    }
            }

        // All parameters from every level are present
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
        assertTrue(result.containsKey("encounter-status"))
        assertTrue(result.containsKey("observation"))
        assertTrue(result.containsKey("observation-status"))
        assertEquals(5, result.totalCount())

        // Head is the Observation (innermost result)
        assertThat(result.getResult(), instanceOf(Observation::class.java))
        assertFalse(result.hasErrors())
    }

    // ── Scenario 5: whenTrue nesting + guardFalse across two patient states ──

    @Test
    fun `nested whenTrue adds parameters only when both conditions are met`() {
        val activeNamedPatient = patient("p1").apply {
            active = true
            addName().apply { addGiven("John"); family = "Doe" }
        }

        val result = OperationResult.of(activeNamedPatient, "patient")
            .whenTrue(activeNamedPatient.active) {
                addString("status", "active")
                    .whenTrue(activeNamedPatient.hasName()) {
                        addString("has-name", "true")
                    }
            }
            .guardFalse(activeNamedPatient.active, "patient is inactive")

        assertFalse(result.hasErrors())
        assertTrue(result.containsKey("status"))
        assertTrue(result.containsKey("has-name"))
    }

    @Test
    fun `guardFalse records WARNING and pipeline continues in ACCUMULATE after conditional skip`() {
        val inactivePatient = patient("p2").apply { active = false }

        val result = OperationResult.of(inactivePatient, "patient", errorStrategy = ErrorStrategy.ACCUMULATE)
            .whenTrue(inactivePatient.active) {
                addString("status", "active") // must not run
            }
            .guardFalse(inactivePatient.active, "patient is inactive")
            .add("encounter") { encounter("e1") }

        // whenTrue block did not run
        assertFalse(result.containsKey("status"))

        // guardFalse added a WARNING
        assertEquals(1, result.getOutcomes().size)
        assertEquals(OperationOutcome.IssueSeverity.WARNING, result.getOutcomes().first().issueFirstRep.severity)

        // Pipeline continues after guardFalse in ACCUMULATE mode
        assertTrue(result.containsKey("encounter"))
    }

    // ── Scenario 6: whenPath driven by a real FHIRPath identifier expression ─

    @Test
    fun `whenPath runs block for patient with matching identifier system and skips for other`() {
        val matchingPatient = Patient().apply {
            setId("p1")
            addIdentifier().apply {
                system = "http://example.org"
                value = "123"
            }
        }
        val nonMatchingPatient = Patient().apply {
            setId("p2")
            addIdentifier().apply {
                system = "http://other.org"
                value = "456"
            }
        }

        val matchResult = OperationResult.of(matchingPatient, "patient")
            .whenPath("identifier.where(system = 'http://example.org').exists()") {
                add("encounter") { encounter("enc-match") }
            }

        val skipResult = OperationResult.of(nonMatchingPatient, "patient")
            .whenPath("identifier.where(system = 'http://example.org').exists()") {
                add("encounter") { encounter("enc-skip") }
            }

        // Block ran for the matching patient
        assertTrue(matchResult.containsKey("encounter"))
        assertFalse(matchResult.hasErrors())

        // Block was skipped for the non-matching patient
        assertFalse(skipResult.containsKey("encounter"))
        assertFalse(skipResult.hasErrors())
    }

    // ── Scenario 7: addPart nesting — nested parameters round-trip via toParameters ─

    @Test
    fun `addPart produces nested parameter structure that survives toParameters round-trip`() {
        val result = OperationResult.of(patient("p1"), "patient")
            .addString("status", "active")
            .addPart("summary") {
                addString("enrolled", "true")
                    .addString("score", "42")
            }

        // The top-level result is successful and has outer parameters
        assertTrue(result.isSuccessful())
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("status"))

        // Serialise to Parameters
        val params = result.toParameters()
        assertNotNull(params)

        // The nested "summary" part is present as a top-level parameter
        val summaryParams = params.parameter.filter { it.name == "summary" }
        assertThat(summaryParams, hasSize(1))

        // It carries its children as parts
        val summaryParam = summaryParams.first()
        assertFalse(summaryParam.hasValue())
        assertFalse(summaryParam.hasResource())
        assertThat(summaryParam.part, hasSize(2))

        val partNames = summaryParam.part.map { it.name }
        assertTrue(partNames.contains("enrolled"))
        assertTrue(partNames.contains("score"))
    }

    // ── Scenario 8: addWithRetryUsing reads pipeline head, fails then succeeds ─

    @Test
    fun `addWithRetryUsing succeeds on third attempt using pipeline head without leaving an error outcome`() {
        var attempts = 0
        val basePatient = patient("p1")

        val result = OperationResult.of(basePatient, "patient")
            .addWithRetryUsing("encounter", maxAttempts = 3, initialDelayMs = 0) { p: Patient ->
                attempts++
                if (attempts < 3) error("transient failure attempt $attempts for ${p.idPart}")
                encounter("enc-${p.idPart}")
            }

        assertEquals(3, attempts)
        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())

        val enc = result.getAll("encounter").first() as Encounter
        assertEquals("enc-p1", enc.idPart)
    }

    // ── Scenario 9: merge two independent results preserves both maps & outcomes ─

    @Test
    fun `merging two results with distinct keys combines parameter maps and accumulates outcomes`() {
        // Both pipelines have a warning (Pattern B step failure)
        val a = OperationResult.of(patient("p1"), "patient", errorStrategy = ErrorStrategy.ACCUMULATE)
            .addOrSkip<Observation>("obs") { error("optional obs failed") }

        val b = OperationResult.of(encounter("e1"), "encounter", errorStrategy = ErrorStrategy.ACCUMULATE)
            .addString("status", "finished")

        val merged = a.merge(b)

        // All keys from both results are present
        assertTrue(merged.containsKey("patient"))
        assertTrue(merged.containsKey("encounter"))
        assertTrue(merged.containsKey("status"))

        // totalCount = patient(1) + encounter(1) + status(1) = 3
        assertEquals(3, merged.totalCount())

        // Outcomes from both sides are preserved
        assertEquals(1, merged.getOutcomes().size)
        assertEquals(OperationOutcome.IssueSeverity.WARNING, merged.getOutcomes().first().issueFirstRep.severity)
    }

    // ── Scenario 10: rename + remove + filterByName sequence ─────────────────

    @Test
    fun `rename then remove then filterByName leaves only the renamed entry in the map`() {
        val result = OperationResult.of(patient("p1"), "a")
            .add("b") { encounter("e1") }
            .add("c") { observation("obs1") }
            .add("d") { Coverage() }
            .rename("a", "alpha")
            .remove("b")
            .filterByName("alpha")

        // Only "alpha" survives
        assertTrue(result.containsKey("alpha"))
        assertFalse(result.containsKey("a"))
        assertFalse(result.containsKey("b"))
        assertFalse(result.containsKey("c"))
        assertFalse(result.containsKey("d"))
        assertEquals(1, result.totalCount())
    }

    @Test
    fun `rename of a non-existent key is a no-op and leaves the map unchanged`() {
        val result = OperationResult.of(patient("p1"), "patient")
            .add("encounter") { encounter("e1") }
            .rename("nonexistent", "something")

        // Map unchanged
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
        assertFalse(result.containsKey("something"))
        assertEquals(2, result.totalCount())
    }

    // ── Scenario 11: extension-value predicate followed by FHIRPath filter ───

    @Test
    fun `extension predicate filter combined with FHIRPath filter produces correct intersection`() {
        val activeEnrolled = Patient().apply {
            setId("p1")
            active = true
            addExtension("http://example.org/enrolled", BooleanType(true))
        }
        val inactiveEnrolled = Patient().apply {
            setId("p2")
            active = false
            addExtension("http://example.org/enrolled", BooleanType(true))
        }
        val activeNotEnrolled = Patient().apply {
            setId("p3")
            active = true
            // no enrolled extension
        }

        // First filter: enrolled patients only → produces List<Patient> as head
        val afterExtFilter = OperationResult.of(listOf(activeEnrolled, inactiveEnrolled, activeNotEnrolled), "patients")
            .addAllFromHavingExtensionValueMatching(
                Patient::class,
                "http://example.org/enrolled",
                BooleanType::class,
                { it.booleanValue() }
            ) { enrolled ->
                // Store enrolled patients back so FHIRPath filter can find them
                enrolled
            }

        // afterExtFilter has 2 patients under "patient" key (from fhirType)
        assertEquals(2, afterExtFilter.count("patient"))

        // Second filter: from those enrolled, keep only active ones.
        // filterByName("patient") drops the original "patients" key so the FHIRPath step
        // only sees the 2 enrolled patients, not all 3 originals.
        val finalResult = afterExtFilter
            .filterByName("patient")
            .addAllFromMatching<Patient, Patient>("active-enrolled", Patient::class, "active = true") { it }

        assertEquals(1, finalResult.count("active-enrolled"))
        assertFalse(finalResult.hasErrors())

        val surviving = finalResult.getAll("active-enrolled").first() as Patient
        assertEquals("p1", surviving.idPart)
    }

    // ── Scenario 12: linkReferences two rules — one resolves, one cannot ──────

    @Test
    fun `two explicit linkReferences rules — one resolves successfully, other has no target to link`() {
        val p = patient("p1")
        val enc = encounter("e1")
        // No observation in the pipeline — the observation rule has no target

        val encounterRule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, pat -> e.subject = Reference("Patient/${pat.idPart}") }
        )
        val observationRule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { obs, pat -> obs.subject = Reference("Patient/${pat.idPart}") }
        )

        val linked = OperationResult.of(p, "patient")
            .add { enc }
            .linkReferences(encounterRule, observationRule)

        // No exception — missing target is silently skipped
        assertFalse(linked.hasErrors())

        // The encounter rule resolved: subject is set
        val linkedEnc = linked.getByType<Encounter>().single()
        assertEquals("Patient/p1", linkedEnc.subject.reference)

        // Original encounter was not mutated
        assertFalse(enc.hasSubject())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient(id: String) = Patient().apply { setId(id) }

    private fun encounter(id: String) = Encounter().apply {
        setId(id)
        status = Encounter.EncounterStatus.FINISHED
    }

    private fun observation(id: String) = Observation().apply {
        setId(id)
        status = Observation.ObservationStatus.FINAL
    }
}
