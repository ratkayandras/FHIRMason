package dev.ratkay.operation.dstu3

import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Observation
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.Reference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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

    // ── linkReferences() — automatic introspection ────────────────────────────

    @Test
    fun `auto linkReferences wires encounter subject to patient`() {
        val linked = OperationResult.of(patient())
            .add { encounter() }
            .linkReferences()

        assertEquals("Patient/p1", linked.getByType<Encounter>().single().subject.reference)
    }

    @Test
    fun `auto linkReferences wires observation subject to patient`() {
        val linked = OperationResult.of(patient())
            .add { observation() }
            .linkReferences()

        assertEquals("Patient/p1", linked.getByType<Observation>().single().subject.reference)
    }

    @Test
    fun `auto linkReferences wires encounter and observation subject in one call`() {
        val linked = OperationResult.of(patient())
            .add { encounter() }
            .add { observation() }
            .linkReferences()

        assertEquals("Patient/p1", linked.getByType<Encounter>().single().subject.reference)
        assertEquals("Patient/p1", linked.getByType<Observation>().single().subject.reference)
    }

    @Test
    fun `auto linkReferences wires observation encounter reference`() {
        val linked = OperationResult.of(patient())
            .add { encounter() }
            .add { observation() }
            .linkReferences()

        assertEquals("Encounter/e1", linked.getByType<Observation>().single().context.reference)
    }

    @Test
    fun `auto linkReferences applies rule to all sources when one target exists`() {
        val linked = OperationResult.of(patient())
            .add { encounter("e1") }
            .add { encounter("e2") }
            .linkReferences()

        linked.getByType<Encounter>().forEach {
            assertEquals("Patient/p1", it.subject.reference)
        }
    }

    @Test
    fun `auto linkReferences does not touch already-set references`() {
        val enc = encounter().apply { subject = Reference("Patient/existing") }
        val linked = OperationResult.of(patient())
            .add { enc }
            .linkReferences()

        assertEquals("Patient/existing", linked.getByType<Encounter>().single().subject.reference)
    }

    @Test
    fun `auto linkReferences skips when no matching target exists`() {
        val linked = OperationResult.of(encounter()).linkReferences()

        assertFalse(linked.getByType<Encounter>().single().hasSubject())
    }

    @Test
    fun `auto linkReferences skips ambiguous reference when multiple candidates exist`() {
        val linked = OperationResult.of(listOf(patient("p1"), patient("p2")))
            .add { encounter() }
            .linkReferences()

        assertFalse(linked.getByType<Encounter>().single().hasSubject())
    }

    @Test
    fun `auto linkReferences skips resources without id`() {
        val linked = OperationResult.of(Patient()) // no id
            .add { encounter() }
            .linkReferences()

        assertFalse(linked.getByType<Encounter>().single().hasSubject())
    }

    // ── Immutability ─────────────────────────────────────────────────────────

    @Test
    fun `auto linkReferences does not mutate original resources`() {
        val enc = encounter()
        val original = OperationResult.of(patient()).add { enc }

        original.linkReferences()

        assertFalse(enc.hasSubject(), "Original resource must not be mutated")
    }

    @Test
    fun `auto linkReferences returns a new result leaving the original intact`() {
        val original = OperationResult.of(patient()).add { encounter() }
        val linked = original.linkReferences()

        original.getByType<Encounter>().forEach { assertFalse(it.hasSubject()) }
        linked.getByType<Encounter>().forEach { assertTrue(it.hasSubject()) }
    }

    // ── linkReferences(vararg rules) — explicit rules ─────────────────────────

    @Test
    fun `explicit rule wires encounter subject to patient`() {
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        val linked = OperationResult.of(patient())
            .add { encounter() }
            .linkReferences(rule)

        assertEquals("Patient/p1", linked.getByType<Encounter>().single().subject.reference)
    }

    @Test
    fun `explicit rule does not mutate original resources`() {
        val enc = encounter()
        val original = OperationResult.of(patient()).add { enc }
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        original.linkReferences(rule)

        assertFalse(enc.hasSubject(), "Original resource must not be mutated")
    }

    @Test
    fun `explicit rule applies to all sources when exactly one target exists`() {
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        val linked = OperationResult.of(patient())
            .add { encounter("e1") }
            .add { encounter("e2") }
            .linkReferences(rule)

        linked.getByType<Encounter>().forEach {
            assertEquals("Patient/p1", it.subject.reference)
        }
    }

    @Test
    fun `explicit rule throws when multiple targets exist`() {
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        assertThrows<IllegalArgumentException> {
            OperationResult.of(listOf(patient("p1"), patient("p2")))
                .add { encounter() }
                .linkReferences(rule)
        }
    }

    @Test
    fun `explicit rule is skipped when no target exists`() {
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        val linked = OperationResult.of(encounter()).linkReferences(rule)

        assertFalse(linked.getByType<Encounter>().single().hasSubject())
    }

    @Test
    fun `explicit rule does not self-link when source and target types match`() {
        val rule = ReferenceLinkRule(
            sourceType = Patient::class,
            targetType = Patient::class,
            setter = { _, _ -> fail("Self-link must not occur") }
        )

        OperationResult.of(patient()).linkReferences(rule)
    }

    @Test
    fun `multiple explicit rules are applied in one call`() {
        val encounterRule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )
        val observationRule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { o, p -> o.subject = Reference("Patient/${p.idPart}") }
        )

        val linked = OperationResult.of(patient())
            .add { encounter() }
            .add { observation() }
            .linkReferences(encounterRule, observationRule)

        assertEquals("Patient/p1", linked.getByType<Encounter>().single().subject.reference)
        assertEquals("Patient/p1", linked.getByType<Observation>().single().subject.reference)
    }
}
