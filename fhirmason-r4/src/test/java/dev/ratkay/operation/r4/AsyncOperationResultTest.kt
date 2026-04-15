package dev.ratkay.operation.r4

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.hamcrest.Matchers.startsWith
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.Claim
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `exception in a task is captured as OperationOutcome instead of propagating`() = runBlocking {
        val result = AsyncOperationResult()
            .add("failing") { error("task exploded") }
            .run()

        assertTrue(result.hasErrors())
        assertFalse(result.containsKey("failing"))
        assertTrue(result.getFailedTasks().containsKey("failing"))
    }

    @Test
    fun `independent tasks continue running when one task fails`() = runBlocking {
        val result = AsyncOperationResult()
            .add("failing") { error("boom") }
            .add("succeeding") { Patient().apply { id = "p1" } }
            .run()

        assertTrue(result.containsKey("succeeding"))
        assertFalse(result.containsKey("failing"))
        assertTrue(result.hasErrors())
        assertEquals(1, result.getFailedTasks().size)
    }

    @Test
    fun `dependent task is skipped when its dependency fails`() = runBlocking {
        val result = AsyncOperationResult()
            .add("failing") { error("boom") }
            .addAfter("dependent", "failing") { Patient().apply { id = "d1" } }
            .run()

        assertFalse(result.containsKey("failing"))
        assertFalse(result.containsKey("dependent"))
        assertEquals(2, result.getFailedTasks().size)
        assertTrue(result.getFailedTasks().containsKey("failing"))
        assertTrue(result.getFailedTasks().containsKey("dependent"))
    }

    @Test
    fun `getFailedTasks - independent success tasks are not included`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .add("failing") { error("boom") }
            .addAfter("dependent", "failing") { Patient() }
            .run()

        assertThat(result.getFailedTasks().keys, containsInAnyOrder("failing", "dependent"))
        assertThat(result.getAll("patient"), hasSize(1))
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

    // ── Group 8: Type-safe single-dependency methods ────────────────────────

    @Test
    fun `addAfter with type - dependent task receives typed upstream result`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addAfter("coverage", "patient", Patient::class) { patient ->
                Coverage().apply { id = "cov-for-${patient.id}" }
            }
            .run()

        val coverage = result.getAll("coverage").first() as Coverage
        assertThat(coverage.id, `is`("cov-for-p1"))
    }

    @Test
    fun `addAfter with type - captures NoSuchElementException as failed task when upstream has no matching type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("data") { StringType("hello") }
            .addAfter("out", "data", Patient::class) { patient ->
                Basic().apply { id = patient.id }
            }
            .run()

        assertTrue(result.getFailedTasks().containsKey("out"))
        assertFalse(result.containsKey("out"))
    }

    @Test
    fun `addAfter with type - works in multi-level chain`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "P" } }
            .addAfter("coverage", "patient", Patient::class) { patient ->
                Coverage().apply { id = "cov-${patient.id}" }
            }
            .addAfter("claim", "coverage", Coverage::class) { coverage ->
                Claim().apply { id = "claim-${coverage.id}" }
            }
            .run()

        val claim = result.getAll("claim").first() as Claim
        assertThat(claim.id, `is`("claim-cov-P"))
    }

    @Test
    fun `addListAfter with type - returns list from typed single dependency`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addListAfter("observations", "patient", Patient::class) { patient ->
                listOf(
                    Observation().apply { id = "obs1-${patient.id}" },
                    Observation().apply { id = "obs2-${patient.id}" }
                )
            }
            .run()

        assertThat(result.count("observations"), `is`(2))
        assertThat((result.getAll("observations").first() as Observation).id, `is`("obs1-p1"))
    }

    // ── Group 9: Type-safe list-injection methods ───────────────────────────

    @Test
    fun `addAfterAll - receives typed list of all upstream values`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("observations") {
                listOf(
                    Observation().apply { id = "obs1" },
                    Observation().apply { id = "obs2" },
                    Observation().apply { id = "obs3" }
                )
            }
            .addAfterAll("summary", "observations", Observation::class) { observations ->
                Basic().apply { id = "count-${observations.size}" }
            }
            .run()

        val summary = result.getAll("summary").first() as Basic
        assertThat(summary.id, `is`("count-3"))
    }

    @Test
    fun `addAfterAll - filters by type when upstream has mixed types`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("mixed") {
                listOf(
                    Patient().apply { id = "p1" },
                    Observation().apply { id = "obs1" },
                    Patient().apply { id = "p2" }
                )
            }
            .addAfterAll("patientCount", "mixed", Patient::class) { patients ->
                Basic().apply { id = "patients-${patients.size}" }
            }
            .run()

        val summary = result.getAll("patientCount").first() as Basic
        assertThat(summary.id, `is`("patients-2"))
    }

    @Test
    fun `addAfterAll - receives empty list when no values match type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("data") { StringType("hello") }
            .addAfterAll("out", "data", Patient::class) { patients ->
                Basic().apply { id = "found-${patients.size}" }
            }
            .run()

        val out = result.getAll("out").first() as Basic
        assertThat(out.id, `is`("found-0"))
    }

    @Test
    fun `addListAfterAll - receives typed list and returns list`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("observations") {
                listOf(
                    Observation().apply { id = "obs1" },
                    Observation().apply { id = "obs2" }
                )
            }
            .addListAfterAll("derived", "observations", Observation::class) { observations ->
                observations.map { obs ->
                    Observation().apply { id = "derived-${obs.id}" }
                }
            }
            .run()

        assertThat(result.count("derived"), `is`(2))
        assertThat((result.getAll("derived").first() as Observation).id, `is`("derived-obs1"))
    }

    // ── Group 10: Backward compatibility ────────────────────────────────────

    @Test
    fun `original addAfter still works with map-based deps`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addAfter("coverage", "patient") { deps ->
                val patient = deps["patient"]!!.first() as Patient
                Coverage().apply { id = "cov-${patient.id}" }
            }
            .run()

        val coverage = result.getAll("coverage").first() as Coverage
        assertThat(coverage.id, `is`("cov-p1"))
    }

    @Test
    fun `original addListAfter still works with map-based deps`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addListAfter("observations", "patient") { deps ->
                val patient = deps["patient"]!!.first() as Patient
                listOf(
                    Observation().apply { id = "obs-${patient.id}" }
                )
            }
            .run()

        assertThat(result.count("observations"), `is`(1))
    }

    // ── Group: withExecutor ───────────────────────────────────────────────────

    @Test
    fun `withExecutor runs tasks on the specified thread`() = runBlocking {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "fhirmason-test-thread").also { it.isDaemon = true }
        }
        var threadName = ""
        try {
            val result = AsyncOperationResult()
                .withExecutor(executor)
                .add("patient") {
                    threadName = Thread.currentThread().name
                    Patient()
                }
                .run()
            assertThat(result.containsKey("patient"), `is`(true))
            assertThat(threadName, startsWith("fhirmason-test-thread"))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `withExecutor returns this for fluent chaining`() {
        val dag = AsyncOperationResult()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertThat(dag.withExecutor(executor), sameInstance(dag))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `withExecutor does not affect result correctness`() = runBlocking {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val result = AsyncOperationResult()
                .withExecutor(executor)
                .add("patient") { Patient() }
                .add("observation") { Observation() }
                .addAfter("report", "patient", "observation") { _ -> DiagnosticReport() }
                .run()
            assertThat(result.containsKey("patient"), `is`(true))
            assertThat(result.containsKey("observation"), `is`(true))
            assertThat(result.containsKey("report"), `is`(true))
        } finally {
            executor.shutdown()
        }
    }
}
