package dev.ratkay.operation.r4

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hl7.fhir.r4.model.Coverage
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the type-safe single-dependency overloads on [addAfterWithTimeout],
 * [addListAfterWithTimeout], [addAfterAllWithTimeout], [addListAfterAllWithTimeout],
 * [addAfterWithRetry], [addListAfterWithRetry], [addAfterAllWithRetry],
 * and [addListAfterAllWithRetry].
 *
 * Retry/timeout mechanics are covered by AsyncOperationResultRetryTest and
 * AsyncOperationResultTimeoutTest respectively. These tests focus on:
 * - correct type extraction (no manual cast or map lookup needed)
 * - typed list injection (addXxxAll variants)
 * - the block receives the correct typed value
 */
class AsyncOperationResultTypedDepOverloadsTest {

    private fun patient(id: String = "p1") = Patient().apply { setId(id) }
    private fun encounter() = Encounter().apply { setId("e1") }
    private fun coverage() = Coverage().apply { setId("c1") }

    // ── addAfterWithTimeout typed ─────────────────────────────────────────────

    @Test
    fun `addAfterWithTimeout typed — block receives typed dep value`() = runBlocking {
        var received: Patient? = null
        val result = AsyncOperationResult()
            .add("patient") { patient("typed") }
            .addAfterWithTimeout("encounter", "patient", Patient::class, timeoutMs = 500) { p ->
                received = p
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals("typed", received!!.idElement.idPart)
    }

    @Test
    fun `addAfterWithTimeout typed — times out and key absent`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithTimeout("encounter", "patient", Patient::class, timeoutMs = 20) { _ ->
                delay(200)
                encounter()
            }
            .execute()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
    }

    // ── addListAfterWithTimeout typed ─────────────────────────────────────────

    @Test
    fun `addListAfterWithTimeout typed — block receives typed dep value and returns list`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient("p") }
            .addListAfterWithTimeout("encounters", "patient", Patient::class, timeoutMs = 500) { p ->
                listOf(encounter(), Encounter().apply { subject.reference = "Patient/${p.idElement.idPart}" })
            }
            .execute()

        assertTrue(result.containsKey("encounters"))
        assertEquals(2, result.count("encounters"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListAfterWithTimeout typed — times out and key absent`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithTimeout("encounters", "patient", Patient::class, timeoutMs = 20) { _ ->
                delay(200)
                listOf(encounter())
            }
            .execute()

        assertFalse(result.containsKey("encounters"))
        assertTrue(result.hasErrors())
    }

    // ── addAfterAllWithTimeout typed ──────────────────────────────────────────

    @Test
    fun `addAfterAllWithTimeout typed — block receives full typed list`() = runBlocking {
        var receivedList: List<Patient>? = null
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("a"), patient("b")) }
            .addAfterAllWithTimeout("encounter", "patients", Patient::class, timeoutMs = 500) { patients ->
                receivedList = patients
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals(2, receivedList!!.size)
        assertEquals("a", receivedList!![0].idElement.idPart)
        assertEquals("b", receivedList!![1].idElement.idPart)
    }

    @Test
    fun `addAfterAllWithTimeout typed — empty list when type does not match`() = runBlocking {
        var receivedSize = -1
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("a"), patient("b")) }
            .addAfterAllWithTimeout("encounter", "patients", Coverage::class, timeoutMs = 500) { coverages ->
                receivedSize = coverages.size
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertEquals(0, receivedSize)
    }

    // ── addListAfterAllWithTimeout typed ──────────────────────────────────────

    @Test
    fun `addListAfterAllWithTimeout typed — block receives typed list and returns list`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("a"), patient("b"), patient("c")) }
            .addListAfterAllWithTimeout("encounters", "patients", Patient::class, timeoutMs = 500) { patients ->
                patients.map { Encounter().apply { subject.reference = "Patient/${it.idElement.idPart}" } }
            }
            .execute()

        assertTrue(result.containsKey("encounters"))
        assertEquals(3, result.count("encounters"))
        assertFalse(result.hasErrors())
    }

    // ── addAfterWithRetry typed ───────────────────────────────────────────────

    @Test
    fun `addAfterWithRetry typed — block receives typed dep and succeeds`() = runBlocking {
        var received: Patient? = null
        val result = AsyncOperationResult()
            .add("patient") { patient("typed") }
            .addAfterWithRetry("encounter", "patient", Patient::class, maxAttempts = 3, initialDelayMs = 0) { p ->
                received = p
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals("typed", received!!.idElement.idPart)
    }

    @Test
    fun `addAfterWithRetry typed — retries and succeeds on second attempt`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", Patient::class, maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals(2, attempts)
    }

    @Test
    fun `addAfterWithRetry typed — all attempts exhausted records error`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", Patient::class, maxAttempts = 2, initialDelayMs = 0) { _ ->
                throw RuntimeException("always fails")
            }
            .execute()

        assertFalse(result.containsKey("encounter"))
        assertTrue(result.hasErrors())
        assertTrue(result.getFailedTasks().containsKey("encounter"))
    }

    // ── addListAfterWithRetry typed ───────────────────────────────────────────

    @Test
    fun `addListAfterWithRetry typed — block receives typed dep and returns list`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient("p") }
            .addListAfterWithRetry("encounters", "patient", Patient::class, maxAttempts = 3, initialDelayMs = 0) { p ->
                listOf(encounter(), Encounter().apply { subject.reference = "Patient/${p.idElement.idPart}" })
            }
            .execute()

        assertTrue(result.containsKey("encounters"))
        assertEquals(2, result.count("encounters"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListAfterWithRetry typed — retries on failure`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addListAfterWithRetry("encounters", "patient", Patient::class, maxAttempts = 3, initialDelayMs = 0) { _ ->
                attempts++
                if (attempts < 3) throw RuntimeException("transient")
                listOf(encounter())
            }
            .execute()

        assertTrue(result.containsKey("encounters"))
        assertEquals(3, attempts)
        assertFalse(result.hasErrors())
    }

    // ── addAfterAllWithRetry typed ────────────────────────────────────────────

    @Test
    fun `addAfterAllWithRetry typed — block receives full typed list`() = runBlocking {
        var receivedList: List<Patient>? = null
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("x"), patient("y")) }
            .addAfterAllWithRetry("encounter", "patients", Patient::class, maxAttempts = 3, initialDelayMs = 0) { patients ->
                receivedList = patients
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
        assertEquals(listOf("x", "y"), receivedList!!.map { it.idElement.idPart })
    }

    @Test
    fun `addAfterAllWithRetry typed — retries with same typed list each attempt`() = runBlocking {
        var attempts = 0
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("a"), patient("b")) }
            .addAfterAllWithRetry("encounter", "patients", Patient::class, maxAttempts = 3, initialDelayMs = 0) { patients ->
                attempts++
                if (attempts < 2) throw RuntimeException("transient")
                assertEquals(2, patients.size)
                encounter()
            }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertEquals(2, attempts)
    }

    // ── addListAfterAllWithRetry typed ────────────────────────────────────────

    @Test
    fun `addListAfterAllWithRetry typed — maps typed list to result list`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") { listOf(patient("a"), patient("b")) }
            .addListAfterAllWithRetry("encounters", "patients", Patient::class, maxAttempts = 3, initialDelayMs = 0) { patients ->
                patients.map { Encounter().apply { subject.reference = "Patient/${it.idElement.idPart}" } }
            }
            .execute()

        assertTrue(result.containsKey("encounters"))
        assertEquals(2, result.count("encounters"))
        val refs = result.getAll("encounters").map { (it as Encounter).subject.reference }
        assertEquals(listOf("Patient/a", "Patient/b"), refs)
        assertFalse(result.hasErrors())
    }

    // ── Result type sanity ────────────────────────────────────────────────────

    @Test
    fun `addAfterWithTimeout typed result has correct FHIR type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithTimeout("encounter", "patient", Patient::class, timeoutMs = 500) { _ -> encounter() }
            .execute()

        assertThat(result.getAll("encounter").first(), instanceOf(Encounter::class.java))
    }

    @Test
    fun `addAfterWithRetry typed result has correct FHIR type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { patient() }
            .addAfterWithRetry("encounter", "patient", Patient::class, maxAttempts = 1, initialDelayMs = 0) { _ -> encounter() }
            .execute()

        assertThat(result.getAll("encounter").first(), instanceOf(Encounter::class.java))
    }
}
