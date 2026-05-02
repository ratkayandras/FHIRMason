package dev.ratkay.operation.dstu3

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.dstu3.model.Encounter
import org.hl7.fhir.dstu3.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultDependentRetryTest {

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { setId("e1") }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `addAfterWithRetry succeeds on first attempt`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                encounter()
            }
            .run()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertThat(result.getAll("encounter").first(), instanceOf(Encounter::class.java))
    }

    @Test
    fun `addAfterWithRetry succeeds on second attempt after transient failure`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                encounter()
            }
            .run()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals(2, attempts)
    }

    @Test
    fun `addAfterWithRetry succeeds on last attempt`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                if (attempts < 3) throw RuntimeException("transient")
                encounter()
            }
            .run()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals(3, attempts)
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    @Test
    fun `addAfterWithRetry exhausts all attempts and records error`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                throw RuntimeException("always fails")
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertEquals(3, attempts)
        assertTrue(result.getFailedTasks().containsKey("encounter"))
    }

    @Test
    fun `addAfterWithRetry with maxAttempts=1 does not retry`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 1, initialDelayMs = 0) { _ ->
                attempts++
                throw RuntimeException("fails")
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertEquals(1, attempts)
    }

    @Test
    fun `addAfterWithRetry retryOn predicate stops retrying immediately when false`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry(
                "encounter", "patient",
                maxAttempts = 5,
                initialDelayMs = 0,
                retryOn = { false }
            ) { _ ->
                attempts++
                throw RuntimeException("non-retryable")
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertEquals(1, attempts)
    }

    @Test
    fun `addAfterWithRetry retryOn predicate filters by exception type`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry(
                "encounter", "patient",
                maxAttempts = 5,
                initialDelayMs = 0,
                retryOn = { e -> e is IllegalStateException }
            ) { _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("transient — retryable")
                throw InternalErrorException("permanent — not retryable")
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertEquals(2, attempts)
    }

    // ── Parameter validation ──────────────────────────────────────────────────

    @Test
    fun `addAfterWithRetry throws when maxAttempts is less than 1`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { patient() }
                .addAfterWithRetry("encounter", "patient", maxAttempts = 0, initialDelayMs = 0) { _ ->
                    encounter()
                }
        }
    }

    @Test
    fun `addAfterWithRetry throws when initialDelayMs is negative`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { patient() }
                .addAfterWithRetry("encounter", "patient", maxAttempts = 3, initialDelayMs = -1) { _ ->
                    encounter()
                }
        }
    }

    // ── Dependency failure contract ───────────────────────────────────────────

    @Test
    fun `addAfterWithRetry does not run retry loop when dependency fails`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { throw RuntimeException("dep failed") }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 5, initialDelayMs = 0) { _ ->
                attempts++
                encounter()
            }
            .run()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertEquals(0, attempts)
        assertTrue(result.getFailedTasks().containsKey("patient"))
        assertTrue(result.getFailedTasks().containsKey("encounter"))
    }

    // ── addListAfterWithRetry ─────────────────────────────────────────────────

    @Test
    fun `addListAfterWithRetry succeeds and stores list result`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithRetry("encounters", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                listOf(encounter(), encounter())
            }
            .run()

        assertTrue(result.containsKey("encounters"))
        assertEquals(2, result.count("encounters"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListAfterWithRetry retries on failure`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithRetry("encounters", "patient", maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                listOf(encounter())
            }
            .run()

        assertTrue(result.containsKey("encounters"))
        assertFalse(result.hasErrors())
        assertEquals(2, attempts)
    }

    // ── Integration ───────────────────────────────────────────────────────────

    @Test
    fun `addAfterWithRetry coexists with add and addAfter tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .add("coverage") { Patient().apply { setId("coverage1") } }
            .addAfterWithRetry("encounter", "patient", maxAttempts = 2, initialDelayMs = 0) { _ ->
                encounter()
            }
            .addAfter("summary", "encounter", "coverage") { _ -> Patient().apply { setId("summary") } }
            .run()

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("coverage"))
        assertTrue(result.containsKey("encounter"))
        assertTrue(result.containsKey("summary"))
        assertFalse(result.hasErrors())
    }
}
