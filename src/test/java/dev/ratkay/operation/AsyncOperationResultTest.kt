package dev.ratkay.operation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultTest {

    // ── Group 1: Basic accumulation ───────────────────────────────────────────

    @Test
    fun `add - single root task accumulates under given key`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .run()

        assertThat(result.containsKey("patient"), `is`(true))
        assertThat(result.count("patient"), `is`(1))
        assertThat(result.getAll("patient").first(), instanceOf(Patient::class.java))
    }

    @Test
    fun `addList - list-returning task accumulates all entries under key`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("appointments") {
                listOf(
                    Appointment().apply { id = "a1" },
                    Appointment().apply { id = "a2" }
                )
            }
            .run()

        assertThat(result.count("appointments"), `is`(2))
    }

    @Test
    fun `multiple independent tasks accumulate under their own keys`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .add("practitioner") { Practitioner().apply { id = "pr1" } }
            .run()

        assertThat(result.getKeys(), containsInAnyOrder("patient", "practitioner"))
        assertThat(result.totalCount(), `is`(2))
    }

    // ── Group 2: Dependency resolution ───────────────────────────────────────

    @Test
    fun `addAfter - dependent task receives upstream result`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addAfter("coverage", "patient") { deps ->
                val patient = deps["patient"]!!.first() as Patient
                Coverage().apply { id = "cov-for-${patient.id}" }
            }
            .run()

        val coverage = result.getAll("coverage").first() as Coverage
        assertThat(coverage.id, `is`("cov-for-p1"))
    }

    @Test
    fun `addAfter - task with multiple deps receives all of them`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .add("appointment") { Appointment().apply { id = "a1" } }
            .addAfter("summary", "patient", "appointment") { deps ->
                val patientId = (deps["patient"]!!.first() as Patient).id
                val apptId = (deps["appointment"]!!.first() as Appointment).id
                Basic().apply { id = "$patientId+$apptId" }
            }
            .run()

        val summary = result.getAll("summary").first() as Basic
        assertThat(summary.id, `is`("p1+a1"))
    }

    @Test
    fun `addAfter - two-level chain A to B to C resolves in order`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { Patient().apply { id = "A" } }
            .addAfter("b", "a") { deps ->
                val aId = (deps["a"]!!.first() as Patient).id
                Patient().apply { id = "$aId-B" }
            }
            .addAfter("c", "b") { deps ->
                val bId = (deps["b"]!!.first() as Patient).id
                Patient().apply { id = "$bId-C" }
            }
            .run()

        assertThat((result.getAll("c").first() as Patient).id, `is`("A-B-C"))
    }

    @Test
    fun `addListAfter - dependent list task receives upstream results`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addListAfter("observations", "patient") { deps ->
                val patientId = (deps["patient"]!!.first() as Patient).id
                listOf(
                    Observation().apply { id = "obs1-$patientId" },
                    Observation().apply { id = "obs2-$patientId" }
                )
            }
            .run()

        assertThat(result.count("observations"), `is`(2))
        assertThat((result.getAll("observations").first() as Observation).id, `is`("obs1-p1"))
    }

    // ── Group 3: Parallelism ──────────────────────────────────────────────────

    @Test
    fun `independent tasks run concurrently and all produce results`() = runBlocking {
        val result = AsyncOperationResult()
            .add("slow") { delay(50); Patient().apply { id = "slow" } }
            .add("fast") { delay(10); Patient().apply { id = "fast" } }
            .run()

        assertThat(result.getKeys(), containsInAnyOrder("slow", "fast"))
        assertThat((result.getAll("slow").first() as Patient).id, `is`("slow"))
        assertThat((result.getAll("fast").first() as Patient).id, `is`("fast"))
    }

    // ── Group 4: Error handling ───────────────────────────────────────────────

    @Test
    fun `exception in a task propagates from run()`() {
        assertThrows<IllegalStateException> {
            runBlocking {
                AsyncOperationResult()
                    .add("failing") { error("task exploded") }
                    .run()
            }
        }
    }

    // ── Group 5: Validation ───────────────────────────────────────────────────

    @Test
    fun `duplicate key registration throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { Patient() }
                .add("patient") { Patient() }
        }
    }

    @Test
    fun `unregistered dependency throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .addAfter("coverage", "patient") { Patient() }
        }
    }

    @Test
    fun `cyclic dependency throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("a") { Patient() }
                .addAfter("b", "a") { Patient() }
                .addAfter("a", "b") { Patient() }
        }
    }

    // ── Group 6: Integration with OperationResult ─────────────────────────────

    @Test
    fun `result supports getByType`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .add("practitioner") { Practitioner().apply { id = "pr1" } }
            .run()

        val patients = result.getByType(Patient::class)
        assertThat(patients, hasSize(1))
        assertThat(patients.first().id, `is`("p1"))
    }

    @Test
    fun `result supports totalCount`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient() }
            .addList("appointments") { listOf(Appointment(), Appointment()) }
            .run()

        assertThat(result.totalCount(), `is`(3))
    }

    @Test
    fun `result supports toParameters`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .run()

        val params = result.toParameters()
        assertThat(params.parameter, hasSize(1))
        assertThat(params.parameter.first().name, `is`("patient"))
    }

    // ── Group 7: runBlocking variant ──────────────────────────────────────────

    @Test
    fun `runBlocking produces same result as suspend run`() {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .runBlocking()

        assertThat(result.containsKey("patient"), `is`(true))
        assertThat((result.getAll("patient").first() as Patient).id, `is`("p1"))
    }
}
