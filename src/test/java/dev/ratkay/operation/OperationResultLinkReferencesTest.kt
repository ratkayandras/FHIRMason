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

    private fun organization(id: String = "org1") = Organization().apply { setId(id) }

    private fun claim(id: String = "c1") = Claim().apply {
        setId(id)
        status = Claim.ClaimStatus.ACTIVE
        use = Claim.Use.CLAIM
        type = CodeableConcept().addCoding(Coding().setSystem("http://terminology.hl7.org/CodeSystem/claim-type").setCode("oral"))
        created = java.util.Date()
        insurer = Reference("Organization/ins1")
        priority = CodeableConcept().addCoding(Coding().setCode("normal"))
    }

    private fun coverage(id: String = "cov1") = Coverage().apply {
        setId(id)
        status = Coverage.CoverageStatus.ACTIVE
    }

    private val UUID_ID = "550e8400-e29b-41d4-a716-446655440000"

    // ── linkReferences() — built-in rules ────────────────────────────────────

    @Test
    fun `linkReferences wires encounter subject to patient`() {
        val original = OperationResult.of(patient())
            .add { encounter() }

        val linked = original.linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertEquals("Patient/p1", enc.subject.reference)
    }

    @Test
    fun `linkReferences wires observation subject to patient`() {
        val linked = OperationResult.of(patient())
            .add { observation() }
            .linkReferences()

        val obs = linked.getByType<Observation>().single()
        assertEquals("Patient/p1", obs.subject.reference)
    }

    @Test
    fun `linkReferences wires encounter serviceProvider to organization`() {
        val linked = OperationResult.of(organization())
            .add { encounter() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertEquals("Organization/org1", enc.serviceProvider.reference)
    }

    @Test
    fun `linkReferences wires claim patient to patient`() {
        val linked = OperationResult.of(patient())
            .add { claim() }
            .linkReferences()

        val c = linked.getByType<Claim>().single()
        assertEquals("Patient/p1", c.patient.reference)
    }

    @Test
    fun `linkReferences wires coverage beneficiary to patient`() {
        val linked = OperationResult.of(patient())
            .add { coverage() }
            .linkReferences()

        val cov = linked.getByType<Coverage>().single()
        assertEquals("Patient/p1", cov.beneficiary.reference)
    }

    @Test
    fun `linkReferences applies multiple built-in rules in one call`() {
        val linked = OperationResult.of(patient())
            .add { encounter() }
            .add { observation() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        val obs = linked.getByType<Observation>().single()
        assertEquals("Patient/p1", enc.subject.reference)
        assertEquals("Patient/p1", obs.subject.reference)
    }

    @Test
    fun `linkReferences applies rule to all sources when one target exists`() {
        val linked = OperationResult.of(patient())
            .add { encounter("e1") }
            .add { encounter("e2") }
            .linkReferences()

        val encounters = linked.getByType<Encounter>()
        assertEquals(2, encounters.size)
        encounters.forEach { assertEquals("Patient/p1", it.subject.reference) }
    }

    @Test
    fun `linkReferences is a no-op when no matching target resource exists`() {
        // Only encounter, no patient — subject should stay unset
        val linked = OperationResult.of(encounter())
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertFalse(enc.hasSubject())
    }

    @Test
    fun `linkReferences skips rule silently when multiple targets exist`() {
        // Two patients → ambiguous → Encounter.subject stays unset
        val linked = OperationResult.of(listOf(patient("p1"), patient("p2")))
            .add { encounter() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertFalse(enc.hasSubject())
    }

    @Test
    fun `linkReferences skips target resources that have no id`() {
        val patientNoId = Patient() // hasId() == false
        val linked = OperationResult.of(patientNoId)
            .add { encounter() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertFalse(enc.hasSubject())
    }

    // ── UUID-based references ────────────────────────────────────────────────

    @Test
    fun `linkReferences uses urn-uuid reference when patient id is a UUID`() {
        val pat = patient(UUID_ID)
        val linked = OperationResult.of(pat)
            .add { encounter() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertEquals("urn:uuid:$UUID_ID", enc.subject.reference)
    }

    @Test
    fun `linkReferences uses ResourceType-id reference when patient id is not a UUID`() {
        val pat = patient("12345")
        val linked = OperationResult.of(pat)
            .add { encounter() }
            .linkReferences()

        val enc = linked.getByType<Encounter>().single()
        assertEquals("Patient/12345", enc.subject.reference)
    }

    // ── Immutability ─────────────────────────────────────────────────────────

    @Test
    fun `linkReferences does not mutate original accumulated resources`() {
        val enc = encounter()
        val original = OperationResult.of(patient()).add { enc }

        original.linkReferences()

        // enc was added to original; original.linkReferences() must not touch it
        assertFalse(enc.hasSubject(), "Original resource must not be mutated")
    }

    @Test
    fun `linkReferences returns a new OperationResult leaving the original intact`() {
        val original = OperationResult.of(patient()).add { encounter() }
        val linked = original.linkReferences()

        // Original resources still unlinked
        original.getByType<Encounter>().forEach { assertFalse(it.hasSubject()) }
        // Linked copy has the reference set
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
    fun `explicit rules does not mutate original resources`() {
        val enc = encounter()
        val original = OperationResult.of(patient()).add { enc }
        val rule = ReferenceLinkRule(
            sourceType = Encounter::class,
            targetType = Patient::class,
            setter = { e, p -> e.subject = Reference("Patient/${p.idPart}") }
        )

        original.linkReferences(rule)

        assertFalse(enc.hasSubject(), "Original encounter must not be mutated")
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

        val encounters = linked.getByType<Encounter>()
        assertEquals(2, encounters.size)
        encounters.forEach { assertEquals("Patient/p1", it.subject.reference) }
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
    fun `explicit rule is skipped when no target resource exists`() {
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

    // ── BUILT_IN_RULES companion accessibility ────────────────────────────────

    @Test
    fun `BUILT_IN_RULES is accessible and non-empty`() {
        assertTrue(OperationResult.BUILT_IN_RULES.isNotEmpty())
    }

    @Test
    fun `BUILT_IN_RULES can be passed to explicit linkReferences`() {
        val linked = OperationResult.of(patient())
            .add { encounter() }
            .linkReferences(*OperationResult.BUILT_IN_RULES.toTypedArray())

        assertEquals("Patient/p1", linked.getByType<Encounter>().single().subject.reference)
    }
}
