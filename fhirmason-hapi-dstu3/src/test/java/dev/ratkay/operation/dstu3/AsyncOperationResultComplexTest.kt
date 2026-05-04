package dev.ratkay.operation.dstu3

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.dstu3.model.Basic
import org.hl7.fhir.dstu3.model.Bundle
import org.hl7.fhir.dstu3.model.Claim
import org.hl7.fhir.dstu3.model.Coverage
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Observation
import org.hl7.fhir.dstu3.model.OperationOutcome
import org.hl7.fhir.dstu3.model.Patient
import org.hl7.fhir.dstu3.model.Practitioner
import org.hl7.fhir.dstu3.model.Reference
import org.hl7.fhir.dstu3.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncOperationResultComplexTest {

    // ── Scenario 1: diamond DAG — A, B → C → D ───────────────────────────────

    @Test
    fun `diamond DAG A and B run in parallel then C waits for both and D waits for C`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { delay(20); Patient().apply { id = "A" } }
            .add("b") { delay(20); Practitioner().apply { id = "B" } }
            .addAfter("c", "a", "b") { deps ->
                val aId = (deps["a"]!!.first() as Patient).id
                val bId = (deps["b"]!!.first() as Practitioner).id
                Basic().apply { id = "$aId+$bId" }
            }
            .addAfter("d", "c", Basic::class.java) { c ->
                Encounter().apply { id = "D-from-${c.id}" }
            }
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("a", "b", "c", "d"))

        val c = result.getAll("c").first() as Basic
        assertThat(c.id, `is`("A+B"))

        val d = result.getAll("d").first() as Encounter
        assertThat(d.id, `is`("D-from-A+B"))

        assertFalse(result.hasErrors())
    }

    @Test
    fun `diamond DAG A and B execute concurrently — total wall time is less than A plus B`() = runBlocking {
        val start = System.currentTimeMillis()

        AsyncOperationResult()
            .add("a") { delay(80); Patient() }
            .add("b") { delay(80); Practitioner() }
            .addAfter("c", "a", "b") { Basic() }
            .execute()

        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 150, "Expected concurrent execution but elapsed was ${elapsed}ms")
    }

    // ── Scenario 2: wide fan-in — 5 independent tasks → 1 collector ──────────

    @Test
    fun `five independent tasks all feed into a single collector task`() = runBlocking {
        val result = AsyncOperationResult()
            .add("t1") { Patient().apply { id = "1" } }
            .add("t2") { Patient().apply { id = "2" } }
            .add("t3") { Patient().apply { id = "3" } }
            .add("t4") { Patient().apply { id = "4" } }
            .add("t5") { Patient().apply { id = "5" } }
            .addAfter("collector", "t1", "t2", "t3", "t4", "t5") { deps ->
                val ids = (1..5).map { i -> (deps["t$i"]!!.first() as Patient).id }
                Basic().apply { id = ids.joinToString(",") }
            }
            .execute()

        assertThat(result.totalCount(), `is`(6))
        val collector = result.getAll("collector").first() as Basic
        assertThat(collector.id, `is`("1,2,3,4,5"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `five independent tasks run concurrently — wall time is less than sum of all`() = runBlocking {
        val start = System.currentTimeMillis()

        AsyncOperationResult()
            .add("t1") { delay(60); Patient() }
            .add("t2") { delay(60); Patient() }
            .add("t3") { delay(60); Patient() }
            .add("t4") { delay(60); Patient() }
            .add("t5") { delay(60); Patient() }
            .addAfter("collector", "t1", "t2", "t3", "t4", "t5") { Basic() }
            .execute()

        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 220, "Expected concurrent execution but elapsed was ${elapsed}ms")
    }

    // ── Scenario 3: five-level linear cascade A → B → C → D → E ─────────────

    @Test
    fun `five-level linear chain passes data through correctly at every level`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { Patient().apply { id = "A" } }
            .addAfter("b", "a", Patient::class.java) { a ->
                Patient().apply { id = "${a.id}-B" }
            }
            .addAfter("c", "b", Patient::class.java) { b ->
                Patient().apply { id = "${b.id}-C" }
            }
            .addAfter("d", "c", Patient::class.java) { c ->
                Patient().apply { id = "${c.id}-D" }
            }
            .addAfter("e", "d", Patient::class.java) { d ->
                StringType("${d.id}-E")
            }
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("a", "b", "c", "d", "e"))

        val e = result.getAll("e").first() as StringType
        assertThat(e.value, `is`("A-B-C-D-E"))
        assertFalse(result.hasErrors())
    }

    // ── Scenario 4: failure at tier 2 cascades to tiers 3 and 4 ─────────────

    @Test
    fun `failure at tier-2 skips all downstream dependents while independent task succeeds`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { Patient().apply { id = "ok" } }
            .add("b") { error("tier-2 boom") }
            .addAfter("c", "b") { Basic() }
            .addAfter("d", "c") { Basic() }
            .execute()

        assertTrue(result.containsKey("a"))

        assertFalse(result.containsKey("b"))
        assertFalse(result.containsKey("c"))
        assertFalse(result.containsKey("d"))

        assertThat(result.getFailedTasks().keys, containsInAnyOrder("b", "c", "d"))
        assertTrue(result.hasErrors())
    }

    // ── Scenario 5: partial failure — one branch fails, sibling branch succeeds

    @Test
    fun `partial failure — failing branch does not affect independent sibling branch`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { error("a fails") }
            .add("b") { Patient().apply { id = "B" } }
            .addAfter("c", "a", Patient::class.java) { Patient().apply { id = "C" } }
            .addAfter("d", "b", Patient::class.java) { p ->
                Encounter().apply { id = "D-${p.id}" }
            }
            .execute()

        assertTrue(result.containsKey("b"))
        assertTrue(result.containsKey("d"))
        val d = result.getAll("d").first() as Encounter
        assertThat(d.id, `is`("D-B"))

        assertFalse(result.containsKey("a"))
        assertFalse(result.containsKey("c"))
        assertThat(result.getFailedTasks().keys, containsInAnyOrder("a", "c"))
    }

    // ── Scenario 6: DAG merge with cross-DAG dependency ──────────────────────

    @Test
    fun `outer task and inner merged task used together in a post-merge dependent task`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .merge {
                AsyncOperationResult()
                    .add("coverage") { Coverage().apply { id = "c1" } }
            }
            .addAfter("claim", "patient", "coverage") { deps ->
                val patId = (deps["patient"]!!.first() as Patient).id
                val covId = (deps["coverage"]!!.first() as Coverage).id
                Claim().apply { id = "$patId+$covId" }
            }
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("patient", "coverage", "claim"))

        val claim = result.getAll("claim").first() as Claim
        assertThat(claim.id, `is`("p1+c1"))
        assertFalse(result.hasErrors())
    }

    // ── Scenario 7: retry in DAG — retried task feeds a dependent ────────────

    @Test
    fun `retried async task eventually succeeds and its dependent task receives the correct value`() = runBlocking {
        var attempts = 0

        val result = AsyncOperationResult()
            .addWithRetry("patient", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                if (attempts < 3) error("transient failure attempt $attempts")
                Patient().apply { id = "retried-p1" }
            }
            .addAfter("encounter", "patient", Patient::class.java) { p ->
                Encounter().apply { id = "enc-${p.id}" }
            }
            .execute()

        assertEquals(3, attempts)
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))

        val enc = result.getAll("encounter").first() as Encounter
        assertThat(enc.id, `is`("enc-retried-p1"))
        assertFalse(result.hasErrors())
    }

    // ── Scenario 8: type-safe addAfter chained three levels deep ─────────────

    @Test
    fun `type-safe addAfter chained three levels receives correctly typed resource at each level`() = runBlocking {
        var patientReceived: Patient? = null
        var encounterReceived: Encounter? = null
        var observationReceived: Observation? = null

        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "P" } }
            .addAfter("encounter", "patient", Patient::class.java) { p ->
                patientReceived = p
                Encounter().apply { id = "E-from-${p.id}" }
            }
            .addAfter("observation", "encounter", Encounter::class.java) { e ->
                encounterReceived = e
                Observation().apply { id = "O-from-${e.id}" }
            }
            .addAfter("outcome", "observation", Observation::class.java) { obs ->
                observationReceived = obs
                OperationOutcome().apply {
                    addIssue().diagnostics = "processed-${obs.id}"
                }
            }
            .execute()

        assertEquals("P", patientReceived!!.id)
        assertEquals("E-from-P", encounterReceived!!.id)
        assertEquals("O-from-E-from-P", observationReceived!!.id)

        val oo = result.getAll("outcome").first() as OperationOutcome
        assertThat(oo.issueFirstRep.diagnostics, `is`("processed-O-from-E-from-P"))

        assertFalse(result.hasErrors())
    }

    // ── Scenario 9: post-processing async result with OperationResult pipeline ─

    @Test
    fun `async DAG result is post-processed with sync OperationResult pipeline to produce a Bundle`() = runBlocking {
        val asyncResult = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1"; active = true } }
            .addList("observations") {
                listOf(
                    Observation().apply { id = "obs1"; status = Observation.ObservationStatus.FINAL },
                    Observation().apply { id = "obs2"; status = Observation.ObservationStatus.FINAL }
                )
            }
            .execute()

        val encounterRule = ReferenceLinkRule(
            sourceType = Observation::class,
            targetType = Patient::class,
            setter = { obs, p -> obs.subject = Reference("Patient/${p.idPart}") }
        )

        val bundle = asyncResult
            .linkReferences(encounterRule)
            .toBundle(Bundle.BundleType.COLLECTION)

        assertThat(bundle.entry, hasSize(3))

        val observations = bundle.entry
            .map { it.resource }
            .filterIsInstance<Observation>()
        assertThat(observations, hasSize(2))
        observations.forEach { obs ->
            assertEquals("Patient/p1", obs.subject.reference)
        }
    }

    // ── Scenario 10: metrics — three parallel tasks, total ≈ max(individual) ─

    @Test
    fun `timed DAG with three parallel tasks reports per-task metrics and total near max not sum`() {
        val dag = AsyncOperationResult()
            .timed()
            .add("t1") { Thread.sleep(60); Patient().apply { id = "1" } }
            .add("t2") { Thread.sleep(60); Patient().apply { id = "2" } }
            .add("t3") { Thread.sleep(60); Patient().apply { id = "3" } }

        dag.executeBlocking()

        val metrics = dag.getMetrics()
        assertEquals(3, metrics.size)
        assertTrue(metrics.containsKey("t1"))
        assertTrue(metrics.containsKey("t2"))
        assertTrue(metrics.containsKey("t3"))

        assertTrue(metrics["t1"]!!.success)
        assertTrue(metrics["t2"]!!.success)
        assertTrue(metrics["t3"]!!.success)

        assertTrue(metrics["t1"]!!.durationMs >= 50)
        assertTrue(metrics["t2"]!!.durationMs >= 50)
        assertTrue(metrics["t3"]!!.durationMs >= 50)

        assertTrue(dag.getTotalDuration() < 220, "Expected parallel execution but total was ${dag.getTotalDuration()}ms")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("unused")
    private fun patient(id: String) = Patient().apply { setId(id) }
}
