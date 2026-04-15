package dev.ratkay.operation.dstu3

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultTimeoutTest {

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { setId("e1") }

    // ── addWithTimeout: completes in time ─────────────────────────────────────

    @Test
    fun `addWithTimeout task completing before deadline stores result`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithTimeout("patient", 500) { patient() }
            .run()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.hasErrors())
    }

    // ── addWithTimeout: exceeds deadline ──────────────────────────────────────

    @Test
    fun `addWithTimeout task exceeding deadline leaves key absent and records error`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithTimeout("patient", 20) { delay(200); patient() }
            .run()

        assertFalse(result.containsKey("patient"))
        assertTrue(result.hasErrors())
        assertTrue(result.getFailedTasks().containsKey("patient"))
    }

    @Test
    fun `addWithTimeout timeout outcome diagnostics mention timeout`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithTimeout("patient", 20) { delay(200); patient() }
            .run()

        val diagnostics = result.getOutcomes().first().issueFirstRep.diagnostics
        assertThat(diagnostics, containsString("Timed out"))
    }

    // ── addListWithTimeout ────────────────────────────────────────────────────

    @Test
    fun `addListWithTimeout task completing before deadline stores list`() = runBlocking {
        val result = AsyncOperationResult()
            .addListWithTimeout("patients", 500) { listOf(patient(), patient()) }
            .run()

        assertTrue(result.containsKey("patients"))
        assertEquals(2, result.count("patients"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListWithTimeout task exceeding deadline leaves key absent and records error`() = runBlocking {
        val result = AsyncOperationResult()
            .addListWithTimeout("patients", 20) { delay(200); listOf(patient()) }
            .run()

        assertFalse(result.containsKey("patients"))
        assertTrue(result.hasErrors())
    }

    // ── addAfterWithTimeout ───────────────────────────────────────────────────

    @Test
    fun `addAfterWithTimeout dep resolves and task completes in time`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithTimeout("encounter", "patient", timeoutMs = 500) { _ -> encounter() }
            .run()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addAfterWithTimeout dep resolves but task exceeds deadline`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithTimeout("encounter", "patient", timeoutMs = 20) { _ ->
                delay(200)
                encounter()
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertTrue(result.getFailedTasks().containsKey("encounter"))
    }

    @Test
    fun `addAfterWithTimeout dep fails — task is skipped without timeout firing`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { throw RuntimeException("dep failed") }
            .addAfterWithTimeout("encounter", "patient", timeoutMs = 20) { _ ->
                delay(200)
                encounter()
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertTrue(result.getFailedTasks().containsKey("patient"))
        assertTrue(result.getFailedTasks().containsKey("encounter"))
    }

    // ── addListAfterWithTimeout ───────────────────────────────────────────────

    @Test
    fun `addListAfterWithTimeout completes in time stores list`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithTimeout("encounters", "patient", timeoutMs = 500) { _ ->
                listOf(encounter(), encounter())
            }
            .run()

        assertTrue(result.containsKey("encounters"))
        assertEquals(2, result.count("encounters"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListAfterWithTimeout exceeding deadline records error`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithTimeout("encounters", "patient", timeoutMs = 20) { _ ->
                delay(200)
                listOf(encounter())
            }
            .run()

        assertFalse(result.containsKey("encounters"))
        assertTrue(result.hasErrors())
    }

    // ── Coexistence ───────────────────────────────────────────────────────────

    @Test
    fun `timeout task coexists with non-timeout tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addWithTimeout("encounter", 500) { encounter() }
            .run()

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `one timing-out task does not affect independent non-timeout tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addWithTimeout("encounter", 20) { delay(200); encounter() }
            .run()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
    }

    // ── Parameter validation ──────────────────────────────────────────────────

    @Test
    fun `addWithTimeout throws when timeoutMs is zero`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult().addWithTimeout("patient", 0) { patient() }
        }
    }

    @Test
    fun `addWithTimeout throws when timeoutMs is negative`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult().addWithTimeout("patient", -100) { patient() }
        }
    }

    @Test
    fun `addAfterWithTimeout throws when timeoutMs is zero`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { patient() }
                .addAfterWithTimeout("encounter", "patient", timeoutMs = 0) { _ -> encounter() }
        }
    }
}
