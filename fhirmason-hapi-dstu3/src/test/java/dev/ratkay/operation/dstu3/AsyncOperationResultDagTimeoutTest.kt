package dev.ratkay.operation.dstu3

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultDagTimeoutTest {

    private fun patient(id: String = "p1") = Patient().apply { setId(id) }

    // ── Completes within deadline ─────────────────────────────────────────────

    @Test
    fun `DAG completing before timeout returns all task results`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(500)
            .add("patient") { patient() }
            .execute()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.hasErrors())
    }

    // ── Exceeds deadline ──────────────────────────────────────────────────────

    @Test
    fun `DAG exceeding timeout when no tasks complete returns TIMEOUT error and empty result`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(20)
            .add("patient") { delay(200); patient() }
            .execute()

        assertFalse(result.containsKey("patient"))
        assertTrue(result.hasErrors())
        assertTrue(result.getKeys().isEmpty())
    }

    @Test
    fun `DAG exceeding timeout returns partial results for tasks that completed before deadline`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(300)
            .add("fast") { patient("fast") }
            .add("slow") { delay(500); patient("slow") }
            .execute()

        assertTrue(result.containsKey("fast"))
        assertFalse(result.containsKey("slow"))
        assertTrue(result.hasErrors())
        assertEquals(1, result.getOutcomes().size)
        assertThat(result.getOutcomes().first().issueFirstRep.diagnostics, containsString("exceeded timeout"))
    }

    @Test
    fun `DAG timeout outcome diagnostics mention exceeded timeout`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(20)
            .add("patient") { delay(200); patient() }
            .execute()

        val diagnostics = result.getOutcomes().first().issueFirstRep.diagnostics
        assertThat(diagnostics, containsString("exceeded timeout"))
        assertThat(diagnostics, containsString("20ms"))
    }

    @Test
    fun `DAG timeout outcome has exactly one OperationOutcome`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(20)
            .add("a") { delay(200); patient("a") }
            .add("b") { delay(200); patient("b") }
            .execute()

        assertEquals(1, result.getOutcomes().size)
    }

    // ── executeBlocking respects DAG timeout ─────────────────────────────────

    @Test
    fun `executeBlocking respects DAG timeout`() {
        val result = AsyncOperationResult()
            .timeout(20)
            .add("patient") { delay(200); patient() }
            .executeBlocking()

        assertFalse(result.containsKey("patient"))
        assertTrue(result.hasErrors())
    }

    // ── Fluency and chaining ──────────────────────────────────────────────────

    @Test
    fun `timeout is fluent and returns this`() = runBlocking {
        val dag = AsyncOperationResult()
        val returned = dag.timeout(500)

        assertTrue(returned === dag)
    }

    @Test
    fun `timeout can be chained with other methods`() = runBlocking {
        val result = AsyncOperationResult()
            .add("a") { patient("a") }
            .timeout(500)
            .add("b") { patient("b") }
            .execute()

        assertTrue(result.containsKey("a"))
        assertTrue(result.containsKey("b"))
        assertFalse(result.hasErrors())
    }

    // ── getTotalDuration after timeout ────────────────────────────────────────

    @Test
    fun `getTotalDuration is 0 after DAG timeout`() = runBlocking {
        val dag = AsyncOperationResult()
            .timeout(20)
            .add("patient") { delay(200); patient() }

        dag.execute()

        assertEquals(0L, dag.getTotalDuration())
    }

    // ── Per-task timeout and DAG timeout coexist ──────────────────────────────

    @Test
    fun `per-task timeout and DAG timeout can both be set — DAG timeout is outer bound`() = runBlocking {
        val result = AsyncOperationResult()
            .timeout(20)
            .addWithTimeout("patient", 200) { delay(150); patient() }
            .execute()

        assertFalse(result.containsKey("patient"))
        assertTrue(result.hasErrors())
        assertEquals(1, result.getOutcomes().size)
        assertThat(result.getOutcomes().first().issueFirstRep.diagnostics, containsString("exceeded timeout"))
    }

    // ── Parameter validation ──────────────────────────────────────────────────

    @Test
    fun `timeout throws when durationMs is zero`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult().timeout(0)
        }
    }

    @Test
    fun `timeout throws when durationMs is negative`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult().timeout(-100)
        }
    }
}
