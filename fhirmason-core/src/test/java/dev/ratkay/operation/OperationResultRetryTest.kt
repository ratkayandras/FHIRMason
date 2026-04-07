package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationResultRetryTest {

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { setId("e1") }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `addWithRetry succeeds on first attempt`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) { encounter() }

        assertTrue(result.containsKey("enc"))
        assertFalse(result.hasErrors())
        assertThat(result.getResult(), instanceOf(Encounter::class.java))
    }

    @Test
    fun `addWithRetry stores value under given name`() {
        val enc = encounter()
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) { enc }

        val stored = result.getAll("enc").first()
        assertThat(stored, instanceOf(Encounter::class.java))
        assertEquals("e1", (stored as Encounter).idPart)
    }

    @Test
    fun `addWithRetry succeeds on second attempt after transient failure`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                encounter()
            }

        assertEquals(2, attempts)
        assertFalse(result.hasErrors())
        assertTrue(result.containsKey("enc"))
    }

    @Test
    fun `addWithRetry succeeds on last attempt`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                if (attempts < 3) throw RuntimeException("transient")
                encounter()
            }

        assertEquals(3, attempts)
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addWithRetry head type is the returned resource type`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 1, initialDelayMs = 0) { encounter() }

        assertThat(result.getResult(), instanceOf(Encounter::class.java))
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    @Test
    fun `addWithRetry follows Pattern A after all attempts exhausted`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 3, initialDelayMs = 0) {
                throw RuntimeException("always fails")
            }

        assertTrue(result.hasErrors())
        assertEquals(1, result.getOutcomes().size)
        assertEquals(
            OperationOutcome.IssueSeverity.ERROR,
            result.getOutcomes().first().issueFirstRep.severity
        )
    }

    @Test
    fun `addWithRetry skips head on failure - getResult throws`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 1, initialDelayMs = 0) {
                throw RuntimeException("boom")
            }

        assertThrows(IllegalStateException::class.java) { result.getResult() }
    }

    @Test
    fun `addWithRetry with maxAttempts 1 does not retry`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 1, initialDelayMs = 0) {
                attempts++
                throw RuntimeException("fail")
            }

        assertEquals(1, attempts)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry retries exactly maxAttempts times on persistent failure`() {
        var attempts = 0
        OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 4, initialDelayMs = 0) {
                attempts++
                throw RuntimeException("always fails")
            }

        assertEquals(4, attempts)
    }

    // ── retryOn predicate ─────────────────────────────────────────────────────

    @Test
    fun `addWithRetry does not retry when retryOn returns false`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 5, initialDelayMs = 0, retryOn = { false }) {
                attempts++
                throw RuntimeException("non-retryable")
            }

        assertEquals(1, attempts)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry retries only on matching exception type`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry(
                "enc",
                maxAttempts = 5,
                initialDelayMs = 0,
                retryOn = { it is IllegalStateException }
            ) {
                attempts++
                when (attempts) {
                    1 -> throw IllegalStateException("transient — retryable")
                    else -> throw RuntimeException("non-retryable")
                }
            }

        // attempt 1: IllegalStateException → retryOn=true → retry
        // attempt 2: RuntimeException → retryOn=false → stop
        assertEquals(2, attempts)
        assertTrue(result.hasErrors())
    }

    @Test
    fun `addWithRetry stops retrying when retryOn returns false mid-sequence`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetry(
                "enc",
                maxAttempts = 10,
                initialDelayMs = 0,
                retryOn = { e -> e.message == "retry me" }
            ) {
                attempts++
                if (attempts <= 2) throw RuntimeException("retry me")
                throw RuntimeException("stop here")
            }

        assertEquals(3, attempts)
        assertTrue(result.hasErrors())
    }

    // ── Pipeline integration ──────────────────────────────────────────────────

    @Test
    fun `addWithRetry respects FAIL_FAST shouldSkip`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .add("enc") { throw RuntimeException("initial failure") }
            .addWithRetry("enc2", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                encounter()
            }

        assertEquals(0, attempts)
        assertFalse(result.containsKey("enc2"))
    }

    @Test
    fun `addWithRetry works in ACCUMULATE mode after prior error`() {
        var attempts = 0
        val result = OperationResult.of(patient(), errorStrategy = ErrorStrategy.ACCUMULATE)
            .add("enc") { throw RuntimeException("non-fatal") }
            .addWithRetry("enc2", maxAttempts = 3, initialDelayMs = 0) {
                attempts++
                encounter()
            }

        assertTrue(attempts > 0)
        assertTrue(result.containsKey("enc2"))
    }

    @Test
    fun `addWithRetry can be chained`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 2, initialDelayMs = 0) { encounter() }
            .addWithRetry("enc2", maxAttempts = 2, initialDelayMs = 0) { encounter() }

        assertTrue(result.containsKey("enc"))
        assertTrue(result.containsKey("enc2"))
        assertFalse(result.hasErrors())
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `addWithRetry throws on maxAttempts less than 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationResult.of(patient())
                .addWithRetry("enc", maxAttempts = 0, initialDelayMs = 0) { encounter() }
        }
    }

    @Test
    fun `addWithRetry throws on negative initialDelayMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationResult.of(patient())
                .addWithRetry("enc", maxAttempts = 3, initialDelayMs = -1) { encounter() }
        }
    }

    @Test
    fun `addWithRetry outcome message comes from last exception`() {
        val result = OperationResult.of(patient())
            .addWithRetry("enc", maxAttempts = 2, initialDelayMs = 0) {
                throw RuntimeException("final failure message")
            }

        assertNotNull(result.getOutcomes().firstOrNull())
        val diagnostics = result.getOutcomes().first().issueFirstRep.diagnostics
        assertEquals("final failure message", diagnostics)
    }

    // ── addWithRetryUsing ─────────────────────────────────────────────────────

    @Test
    fun `addWithRetryUsing passes head value to builder`() {
        val result = OperationResult.of(patient())
            .addWithRetryUsing("enc", maxAttempts = 3, initialDelayMs = 0) { p ->
                Encounter().apply { subject.reference = "Patient/${(p as Patient).idPart}" }
            }

        assertFalse(result.hasErrors())
        val enc = result.getAll("enc").first() as Encounter
        assertEquals("Patient/p1", enc.subject.reference)
    }

    @Test
    fun `addWithRetryUsing retries and succeeds using head value`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetryUsing("enc", maxAttempts = 3, initialDelayMs = 0) { p ->
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                Encounter().apply { subject.reference = "Patient/${(p as Patient).idPart}" }
            }

        assertFalse(result.hasErrors())
        assertEquals(2, attempts)
        assertTrue(result.containsKey("enc"))
    }

    @Test
    fun `addWithRetryUsing records error after all attempts exhausted`() {
        val result = OperationResult.of(patient())
            .addWithRetryUsing("enc", maxAttempts = 2, initialDelayMs = 0) { _ ->
                throw RuntimeException("always fails")
            }

        assertTrue(result.hasErrors())
        assertFalse(result.containsKey("enc"))
    }

    @Test
    fun `addWithRetryUsing retryOn predicate stops early`() {
        var attempts = 0
        val result = OperationResult.of(patient())
            .addWithRetryUsing("enc", maxAttempts = 5, initialDelayMs = 0, retryOn = { false }) { _ ->
                attempts++
                throw RuntimeException("non-retryable")
            }

        assertEquals(1, attempts)
        assertTrue(result.hasErrors())
    }
}
