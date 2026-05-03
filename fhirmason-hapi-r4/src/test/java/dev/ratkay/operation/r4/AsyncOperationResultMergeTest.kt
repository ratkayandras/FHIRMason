package dev.ratkay.operation.r4

import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Claim
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultMergeTest {

    // ── Group 1: Basic merge behaviour ───────────────────────────────────────

    @Test
    fun `merge - inner nodes appear in result`() = runBlocking {
        val result = AsyncOperationResult()
            .add("outer") { Patient().apply { id = "p1" } }
            .merge {
                AsyncOperationResult()
                    .add("inner") { Coverage().apply { id = "c1" } }
            }
            .execute()

        assertThat(result.containsKey("outer"), `is`(true))
        assertThat(result.containsKey("inner"), `is`(true))
    }

    @Test
    fun `merge - inner nodes execute and produce correct values`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("coverage") { Coverage().apply { id = "cov1" } }
            }
            .execute()

        assertThat(result.count("coverage"), `is`(1))
        assertThat((result.getAll("coverage").first() as Coverage).id, `is`("cov1"))
    }

    @Test
    fun `merge - addAfter after merge can reference merged inner key`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("patient") { Patient().apply { id = "p1" } }
            }
            .addAfter("encounter", "patient", Patient::class) { patient ->
                Encounter().apply { id = "enc-${patient.id}" }
            }
            .execute()

        val encounter = result.getAll("encounter").first() as Encounter
        assertThat(encounter.id, `is`("enc-p1"))
    }

    @Test
    fun `merge - outer and inner keys coexist and all produce results`() = runBlocking {
        val result = AsyncOperationResult()
            .add("outerA") { Patient().apply { id = "a" } }
            .add("outerB") { Coverage().apply { id = "b" } }
            .merge {
                AsyncOperationResult()
                    .add("innerX") { Appointment().apply { id = "x" } }
                    .add("innerY") { Practitioner().apply { id = "y" } }
            }
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("outerA", "outerB", "innerX", "innerY"))
        assertThat(result.totalCount(), `is`(4))
    }

    // ── Group 2: Inner DAG's internal dependencies survive merge ─────────────

    @Test
    fun `merge - inner dependent node resolves correctly after merge`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("base") { Patient().apply { id = "base" } }
                    .addAfter("derived", "base", Patient::class) { p ->
                        Coverage().apply { id = "derived-from-${p.id}" }
                    }
            }
            .execute()

        val derived = result.getAll("derived").first() as Coverage
        assertThat(derived.id, `is`("derived-from-base"))
    }

    @Test
    fun `merge - inner three-tier chain resolves in order after merge`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("a") { Patient().apply { id = "A" } }
                    .addAfter("b", "a", Patient::class) { a ->
                        Patient().apply { id = "${a.id}-B" }
                    }
                    .addAfter("c", "b", Patient::class) { b ->
                        Patient().apply { id = "${b.id}-C" }
                    }
            }
            .execute()

        assertThat((result.getAll("c").first() as Patient).id, `is`("A-B-C"))
    }

    // ── Group 3: Composing outer + inner ─────────────────────────────────────

    @Test
    fun `merge - addAfter after merge can depend on both outer and inner keys`() = runBlocking {
        val result = AsyncOperationResult()
            .add("outerPatient") { Patient().apply { id = "p1" } }
            .merge {
                AsyncOperationResult()
                    .add("innerCoverage") { Coverage().apply { id = "c1" } }
            }
            .addAfter("claim", "outerPatient", "innerCoverage") { deps ->
                val patient = deps["outerPatient"]!!.first() as Patient
                val coverage = deps["innerCoverage"]!!.first() as Coverage
                Claim().apply { id = "${patient.id}+${coverage.id}" }
            }
            .execute()

        val claim = result.getAll("claim").first() as Claim
        assertThat(claim.id, `is`("p1+c1"))
    }

    // ── Group 4: Error handling ───────────────────────────────────────────────

    @Test
    fun `merge - inner task failure is captured as failed task`() = runBlocking {
        val result = AsyncOperationResult()
            .add("outer") { Patient() }
            .merge {
                AsyncOperationResult()
                    .add("failing") { error("inner boom") }
            }
            .execute()

        assertThat(result.containsKey("outer"), `is`(true))
        assertFalse(result.containsKey("failing"))
        assertTrue(result.hasErrors())
        assertTrue(result.getFailedTasks().containsKey("failing"))
    }

    @Test
    fun `merge - inner failure does not affect independent outer tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("outer") { Patient().apply { id = "ok" } }
            .merge {
                AsyncOperationResult()
                    .add("failing") { error("boom") }
            }
            .execute()

        assertThat(result.containsKey("outer"), `is`(true))
        assertThat(result.getFailedTasks().size, `is`(1))
    }

    // ── Group 5: Duplicate key validation ────────────────────────────────────

    @Test
    fun `merge - duplicate key between outer and inner throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { Patient() }
                .merge {
                    AsyncOperationResult()
                        .add("patient") { Coverage() }
                }
        }
    }

    @Test
    fun `merge - duplicate key across two sequential merges throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .merge {
                    AsyncOperationResult().add("patient") { Patient() }
                }
                .merge {
                    AsyncOperationResult().add("patient") { Coverage() }
                }
        }
    }

    // ── Group 6: Edge cases ───────────────────────────────────────────────────

    @Test
    fun `merge - empty inner DAG is a no-op`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .merge { AsyncOperationResult() }
            .execute()

        assertThat(result.containsKey("patient"), `is`(true))
        assertThat(result.totalCount(), `is`(1))
    }

    @Test
    fun `merge - empty outer merged with non-empty inner works`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("only") { Patient().apply { id = "sole" } }
            }
            .execute()

        assertThat(result.containsKey("only"), `is`(true))
        assertThat((result.getAll("only").first() as Patient).id, `is`("sole"))
    }

    @Test
    fun `merge - multiple sequential merges accumulate all nodes`() = runBlocking {
        val result = AsyncOperationResult()
            .merge { AsyncOperationResult().add("a") { Patient() } }
            .merge { AsyncOperationResult().add("b") { Coverage() } }
            .merge { AsyncOperationResult().add("c") { Appointment() } }
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("a", "b", "c"))
        assertThat(result.totalCount(), `is`(3))
    }

    @Test
    fun `merge - addAfter chain after merge referencing inner key resolves correctly`() = runBlocking {
        val result = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .add("patient") { Patient().apply { id = "p1" } }
            }
            .addAfter("coverage", "patient", Patient::class) { p ->
                Coverage().apply { id = "cov-${p.id}" }
            }
            .addAfter("claim", "coverage", Coverage::class) { c ->
                Claim().apply { id = "claim-${c.id}" }
            }
            .execute()

        val claim = result.getAll("claim").first() as Claim
        assertThat(claim.id, `is`("claim-cov-p1"))
    }

    // ── Group 7: Instance overload ────────────────────────────────────────────

    @Test
    fun `merge instance overload - pre-built inner DAG merges correctly`() = runBlocking {
        val innerDag = AsyncOperationResult()
            .add("coverage") { Coverage().apply { id = "c1" } }

        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .merge(innerDag)
            .execute()

        assertThat(result.getKeys(), containsInAnyOrder("patient", "coverage"))
    }

    @Test
    fun `merge instance overload - duplicate key throws IllegalArgumentException`() {
        val innerDag = AsyncOperationResult().add("patient") { Coverage() }

        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { Patient() }
                .merge(innerDag)
        }
    }

    // ── Group 8: describe() integration ──────────────────────────────────────

    @Test
    fun `merge - describe shows merged nodes in correct tiers`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .merge {
                AsyncOperationResult()
                    .add("coverage") { Coverage() }
                    .addAfter("claim", "coverage") { _ -> Claim() }
            }
            .describe()

        assertThat(output, containsString("patient"))
        assertThat(output, containsString("coverage"))
        assertThat(output, containsString("claim"))
        assertThat(output, containsString("depends on [coverage]"))
    }

    @Test
    fun `merge - describe reflects cross-DAG dependency added after merge`() {
        val output = AsyncOperationResult()
            .add("patient") { Patient() }
            .merge {
                AsyncOperationResult().add("coverage") { Coverage() }
            }
            .addAfter("claim", "patient", "coverage") { _ -> Claim() }
            .describe()

        assertThat(output, containsString("claim"))
        assertThat(output, containsString("depends on [patient, coverage]"))
    }

    // ── Group 9: Timing / metrics ─────────────────────────────────────────────

    @Test
    fun `merge - timed outer DAG captures metrics for merged inner tasks`() {
        val dag = AsyncOperationResult()
            .timed()
            .merge {
                AsyncOperationResult()
                    .add("innerPatient") { Patient() }
            }

        dag.executeBlocking()

        assertTrue(dag.getMetrics().containsKey("innerPatient"))
        assertTrue(dag.getMetrics()["innerPatient"]!!.success)
    }

    @Test
    fun `merge - inner DAG timed setting does not enable metrics on outer`() {
        val dag = AsyncOperationResult()
            .merge {
                AsyncOperationResult()
                    .timed()
                    .add("innerTask") { Patient() }
            }

        dag.executeBlocking()

        assertTrue(dag.getMetrics().isEmpty())
    }
}
