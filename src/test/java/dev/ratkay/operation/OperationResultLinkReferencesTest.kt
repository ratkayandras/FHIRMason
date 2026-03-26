package dev.ratkay.operation

import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OperationResultLinkReferencesTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun patient(id: String = "p1") = Patient().apply { setId(id) }

    private fun encounter(id: String = "e1") = Encounter().apply {
        setId(id)
        status = Encounter.EncounterStatus.FINISHED
    }

    private fun observation(id: String = "obs1") = Observation().apply {
        setId(id)
        status = Observation.ObservationStatus.FINAL
    }

    // ── linkReferences(vararg rules) ──────────────────────────────────────────

    @Test
    fun `linkReferences with explicit rule wires encounter subject to patient`() {
        val patient = patient()
        val enc = encounter()
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        OperationResult.of(patient)
            .add { enc }
            .linkReferences(rule)

        assertEquals("Patient/p1", enc.subject.reference)
    }

    @Test
    fun `linkReferences with explicit rule wires observation subject to patient`() {
        val patient = patient()
        val obs = observation()
        val rule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { o, p -> o.subject = Reference("Patient/${p.idPart}") }
        )

        OperationResult.of(patient)
            .add { obs }
            .linkReferences(rule)

        assertEquals("Patient/p1", obs.subject.reference)
    }

    @Test
    fun `linkReferences with multiple rules wires all references`() {
        val patient = patient()
        val enc = encounter()
        val obs = observation()

        val encounterPatientRule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )
        val observationPatientRule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { o, p -> o.subject = Reference("Patient/${p.idPart}") }
        )
        val observationEncounterRule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Encounter::class,
            setter = { o, e -> o.encounter = Reference("Encounter/${e.idPart}") }
        )

        OperationResult.of(patient)
            .add { enc }
            .add { obs }
            .linkReferences(encounterPatientRule, observationPatientRule, observationEncounterRule)

        assertEquals("Patient/p1", enc.subject.reference)
        assertEquals("Patient/p1", obs.subject.reference)
        assertEquals("Encounter/e1", obs.encounter.reference)
    }

    @Test
    fun `linkReferences with explicit rule does not self-link`() {
        val patient = patient()
        val rule = ReferenceLinkRule(
            sourceType = Patient::class,
            targetType = Patient::class,
            setter = { _, _ -> fail("Self-link must not occur") }
        )

        OperationResult.of(patient).linkReferences(rule)
        // No assertion needed — the setter should never have been called
    }

    @Test
    fun `linkReferences applies rule to all sources when there is exactly one target`() {
        val patient = patient("p1")
        val enc1 = encounter("e1")
        val enc2 = encounter("e2")

        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        OperationResult.of(patient)
            .add { enc1 }
            .add { enc2 }
            .linkReferences(rule)

        assertEquals("Patient/p1", enc1.subject.reference)
        assertEquals("Patient/p1", enc2.subject.reference)
    }

    @Test
    fun `linkReferences throws when multiple targets exist for a rule`() {
        val patient1 = patient("p1")
        val patient2 = patient("p2")
        val enc = encounter("e1")

        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        assertThrows<IllegalArgumentException> {
            OperationResult.of(listOf(patient1, patient2))
                .add { enc }
                .linkReferences(rule)
        }
    }

    @Test
    fun `linkReferences with explicit rule returns same OperationResult type`() {
        val patient = patient()
        val result = OperationResult.of(patient)
            .add { encounter() }
            .linkReferences(
                ReferenceLinkRule(
                    sourceType = Encounter::class,
                    targetType = Patient::class,
                    setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
                )
            )

        assertNotNull(result)
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
    }

    @Test
    fun `linkReferences with explicit rules does not trigger auto-wiring for unmatched types`() {
        val patient = patient()
        val enc = encounter()
        // Rule only wires Observation→Patient; Encounter.subject should remain unset
        val rule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { o, p -> o.subject = Reference("Patient/${p.idPart}") }
        )

        OperationResult.of(patient)
            .add { enc }
            .linkReferences(rule)

        assertFalse(enc.hasSubject())
    }

    // ── linkReferences() automatic ────────────────────────────────────────────

    @Test
    fun `auto linkReferences wires encounter subject to patient by type and id`() {
        val patient = patient("p1")
        val enc = encounter("e1")

        OperationResult.of(patient)
            .add { enc }
            .linkReferences()

        assertEquals("Patient/p1", enc.subject.reference)
    }

    @Test
    fun `auto linkReferences wires observation subject to patient by type and id`() {
        val patient = patient("p1")
        val obs = observation("obs1")

        OperationResult.of(patient)
            .add { obs }
            .linkReferences()

        assertEquals("Patient/p1", obs.subject.reference)
    }

    @Test
    fun `auto linkReferences does not touch already-set references`() {
        val patient = patient("p1")
        val enc = encounter("e1").apply {
            subject = Reference("Patient/existing")
        }

        OperationResult.of(patient)
            .add { enc }
            .linkReferences()

        // Original reference must be preserved
        assertEquals("Patient/existing", enc.subject.reference)
    }

    @Test
    fun `auto linkReferences skips resources without id`() {
        val patientWithoutId = Patient() // no id set
        val enc = encounter("e1")

        OperationResult.of(patientWithoutId)
            .add { enc }
            .linkReferences()

        // No patient in index because it has no id, so encounter.subject stays unset
        assertFalse(enc.hasSubject())
    }

    @Test
    fun `auto linkReferences skips ambiguous reference when multiple candidates exist`() {
        val patient1 = patient("p1")
        val patient2 = patient("p2")
        val enc = encounter("e1")

        OperationResult.of(listOf(patient1, patient2))
            .add { enc }
            .linkReferences()

        // Two Patient candidates → ambiguous → subject must remain unset
        assertFalse(enc.hasSubject())
    }

    @Test
    fun `auto linkReferences returns same OperationResult instance type`() {
        val patient = patient("p1")
        val result = OperationResult.of(patient)
            .add { encounter("e1") }
            .linkReferences()

        assertNotNull(result)
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
    }

    @Test
    fun `auto linkReferences on empty result is a no-op`() {
        val result = OperationResult.of(patient("p1"))
            .linkReferences()

        assertNotNull(result)
        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `auto linkReferences wires encounter to patient and observation to both`() {
        val patient = patient("p1")
        val enc = encounter("e1")
        val obs = observation("obs1")

        OperationResult.of(patient)
            .add { enc }
            .add { obs }
            .linkReferences()

        assertEquals("Patient/p1", enc.subject.reference)
        assertEquals("Patient/p1", obs.subject.reference)
        // Observation.encounter — exactly one Encounter in map → should be wired
        assertEquals("Encounter/e1", obs.encounter.reference)
    }
}
