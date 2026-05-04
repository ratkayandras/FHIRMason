package dev.ratkay.operation.r4

import dev.ratkay.operation.StepMetrics

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncOperationResultDefaultTest {

    private fun patient(id: String = "p1") = Patient().apply { setId(id) }
    private fun encounter(id: String = "e1") = Encounter().apply { setId("e1") }

    // ── addWithDefault: success path ──────────────────────────────────────────

    @Test
    fun `addWithDefault returns block result when block succeeds`() = runBlocking {
        val blockPatient = patient("from-block")
        val defaultPatient = patient("default")

        val result = AsyncOperationResult()
            .addWithDefault("patient", defaultPatient) { blockPatient }
            .execute()

        assertTrue(result.containsKey("patient"))
        assertEquals("from-block", (result.getAll("patient").first() as Patient).idElement.idPart)
        assertFalse(result.hasErrors())
    }

    // ── addWithDefault: failure path ──────────────────────────────────────────

    @Test
    fun `addWithDefault stores default when block throws and key is present`() = runBlocking {
        val defaultPatient = patient("default")

        val result = AsyncOperationResult()
            .addWithDefault("patient", defaultPatient) { throw RuntimeException("transient") }
            .execute()

        assertTrue(result.containsKey("patient"))
        assertEquals("default", (result.getAll("patient").first() as Patient).idElement.idPart)
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addWithDefault stores default on BaseServerResponseException`() = runBlocking {
        val defaultPatient = patient("default")

        val result = AsyncOperationResult()
            .addWithDefault("patient", defaultPatient) { throw InternalErrorException("server error") }
            .execute()

        assertTrue(result.containsKey("patient"))
        assertEquals("default", (result.getAll("patient").first() as Patient).idElement.idPart)
        assertFalse(result.hasErrors())
    }

    // ── addListWithDefault: success path ──────────────────────────────────────

    @Test
    fun `addListWithDefault returns block list when block succeeds`() = runBlocking {
        val blockList = listOf(patient("a"), patient("b"))
        val defaultList = listOf(patient("default"))

        val result = AsyncOperationResult()
            .addListWithDefault("patients", defaultList) { blockList }
            .execute()

        assertTrue(result.containsKey("patients"))
        assertEquals(2, result.count("patients"))
        assertFalse(result.hasErrors())
    }

    // ── addListWithDefault: failure path ──────────────────────────────────────

    @Test
    fun `addListWithDefault stores default list when block throws`() = runBlocking {
        val defaultList = listOf(patient("default"))

        val result = AsyncOperationResult()
            .addListWithDefault("patients", defaultList) { throw RuntimeException("boom") }
            .execute()

        assertTrue(result.containsKey("patients"))
        assertEquals(1, result.count("patients"))
        assertEquals("default", (result.getAll("patients").first() as Patient).idElement.idPart)
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListWithDefault with empty default list — key absent since nothing accumulated`() = runBlocking {
        val result = AsyncOperationResult()
            .addListWithDefault("patients", emptyList()) { throw RuntimeException("boom") }
            .execute()

        // An empty list accumulates nothing, so the key is absent (consistent with addList behaviour)
        assertEquals(0, result.count("patients"))
        assertFalse(result.hasErrors())
    }

    // ── Downstream dependency contract ────────────────────────────────────────

    @Test
    fun `downstream addAfter receives default value when addWithDefault falls back`() = runBlocking {
        val defaultPatient = patient("default")

        val result = AsyncOperationResult()
            .addWithDefault("patient", defaultPatient) { throw RuntimeException("transient") }
            .addAfter("encounter", "patient") { _ -> encounter() }
            .execute()

        // addWithDefault key is present (default used), so downstream task runs
        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `downstream addAfter receives actual result when addWithDefault succeeds`() = runBlocking {
        val blockPatient = patient("real")
        val defaultPatient = patient("default")

        val result = AsyncOperationResult()
            .addWithDefault("patient", defaultPatient) { blockPatient }
            .addAfter("encounter", "patient", Patient::class.java) { p ->
                Encounter().apply { subject.reference = "Patient/${p.idElement.idPart}" }
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        val enc = result.getAll("encounter").first() as Encounter
        assertEquals("Patient/real", enc.subject.reference)
        assertFalse(result.hasErrors())
    }

    // ── Metrics integration ───────────────────────────────────────────────────

    @Test
    fun `timed — addWithDefault falling back records success=true in StepMetrics`() = runBlocking {
        val defaultPatient = patient("default")

        val dag = AsyncOperationResult()
            .timed()
            .addWithDefault("patient", defaultPatient) { throw RuntimeException("transient") }

        dag.execute()

        val metrics = dag.getMetrics()
        assertTrue(metrics.containsKey("patient"))
        assertTrue(metrics["patient"]!!.success)
    }

    @Test
    fun `timed — addWithDefault succeeding records success=true in StepMetrics`() = runBlocking {
        val dag = AsyncOperationResult()
            .timed()
            .addWithDefault("patient", patient("default")) { patient("real") }

        dag.execute()

        val metrics = dag.getMetrics()
        assertTrue(metrics.containsKey("patient"))
        assertTrue(metrics["patient"]!!.success)
    }

    // ── Type check ────────────────────────────────────────────────────────────

    @Test
    fun `addWithDefault result has correct FHIR type`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithDefault("patient", patient()) { throw RuntimeException() }
            .execute()

        assertThat(result.getAll("patient").first(), instanceOf(Patient::class.java))
    }
}
