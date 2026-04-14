package dev.ratkay.operation

import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultRetryTest {

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { setId("e1") }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `addWithRetry succeeds on first attempt`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) { encounter() }
            .run()

        assertTrue(result.containsKey("enc"))
        assertFalse(result.hasErrors())
        assertThat(result.getAll("enc").first(), instanceOf(Encounter::class.java))
    }

    @Test
    fun `addWithRetry succeeds on second attempt after transient failure`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                encounter()
            }
            .run()

        assertTrue(result.containsKey("enc"))
        assertFalse(result.hasErrors())
        assertEquals(2, attempts)
    }

    @Test
    fun `addWithRetry succeeds on last attempt`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                if (attempts < 3) throw RuntimeException("transient")
                encounter()
            }
            .run()

        assertTrue(result.containsKey("enc"))
        assertFalse(result.hasErrors())
        assertEquals(3, attempts)
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    @Test
    fun `addWithRetry records error outcome after all attempts exhausted`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 2, initialDelayMs = 0) {
                throw RuntimeException("always fails")
            }
            .run()

        assertFalse(result.containsKey("enc"))
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry with maxAttempts=1 does not retry`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 1, initialDelayMs = 0) {
                attempts++
                throw RuntimeException("fail")
            }
            .run()

        assertEquals(1, attempts)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry makes exactly maxAttempts calls on repeated failure`() = runBlocking {
        var attempts = 0
        AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 4, initialDelayMs = 0) {
                attempts++
                throw RuntimeException("fail")
            }
            .run()

        assertEquals(4, attempts)
    }

    // ── retryOn predicate ─────────────────────────────────────────────────────

    @Test
    fun `addWithRetry stops immediately when retryOn returns false`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 5, initialDelayMs = 0, retryOn = { false }) {
                attempts++
                throw RuntimeException("non-retryable")
            }
            .run()

        assertEquals(1, attempts)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry retries only for matching exception type`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addWithRetry(
                "enc",
                maxAttempts = 5,
                initialDelayMs = 0,
                retryOn = { e -> e is IllegalStateException }
            ) {
                attempts++
                if (attempts == 1) throw IllegalStateException("retryable")
                throw RuntimeException("non-retryable")
            }
            .run()

        assertEquals(2, attempts)
        assertTrue(result.hasErrors())
    }

    // ── Parameter validation ──────────────────────────────────────────────────

    @Test
    fun `addWithRetry throws when maxAttempts is less than 1`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .addWithRetry("enc", maxAttempts = 0, initialDelayMs = 0) { encounter() }
        }
    }

    @Test
    fun `addWithRetry throws when initialDelayMs is negative`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .addWithRetry("enc", maxAttempts = 3, initialDelayMs = -1) { encounter() }
        }
    }

    // ── Integration with other tasks ──────────────────────────────────────────

    @Test
    fun `addWithRetry coexists with regular add tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) { encounter() }
            .run()

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("enc"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addWithRetry error outcome diagnostics come from last exception`() = runBlocking {
        val result = AsyncOperationResult()
            .addWithRetry("enc", maxAttempts = 2, initialDelayMs = 0) {
                throw RuntimeException("final failure message")
            }
            .run()

        assertNotNull(result.getOutcomes().firstOrNull())
        val diagnostics = result.getOutcomes().first().issueFirstRep.diagnostics
        assertEquals("final failure message", diagnostics)
    }
}
