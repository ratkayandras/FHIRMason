package dev.ratkay.operation.r4

import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AsyncOperationResultConditionalTest {

    private fun patient() = Patient().apply { setId("p1") }
    private fun encounter() = Encounter().apply { setId("e1") }

    // ── addIf ─────────────────────────────────────────────────────────────────

    @Test
    fun `addIf true registers and runs the task`() = runBlocking {
        val result = AsyncOperationResult()
            .addIf(true, "patient") { patient() }
            .execute()

        assertTrue(result.containsKey("patient"))
        assertFalse(result.hasErrors())
        assertEquals(1, result.count("patient"))
    }

    @Test
    fun `addIf false does not register the task and key is absent`() = runBlocking {
        val result = AsyncOperationResult()
            .addIf(false, "patient") { patient() }
            .execute()

        assertFalse(result.containsKey("patient"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addIf false has no effect on other registered tasks`() = runBlocking {
        val result = AsyncOperationResult()
            .add("encounter") { encounter() }
            .addIf(false, "patient") { patient() }
            .execute()

        assertTrue(result.containsKey("encounter"))
        assertFalse(result.containsKey("patient"))
        assertFalse(result.hasErrors())
    }

    // ── addListIf ─────────────────────────────────────────────────────────────

    @Test
    fun `addListIf true registers and runs the list task`() = runBlocking {
        val result = AsyncOperationResult()
            .addListIf(true, "patients") { listOf(patient(), patient()) }
            .execute()

        assertTrue(result.containsKey("patients"))
        assertEquals(2, result.count("patients"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addListIf false does not register the task and key is absent`() = runBlocking {
        val result = AsyncOperationResult()
            .addListIf(false, "patients") { listOf(patient(), patient()) }
            .execute()

        assertFalse(result.containsKey("patients"))
        assertFalse(result.hasErrors())
    }

    // ── Chaining behaviour ────────────────────────────────────────────────────

    @Test
    fun `addIf false then addIf true — only the true task is registered`() = runBlocking {
        val result = AsyncOperationResult()
            .addIf(false, "patient") { patient() }
            .addIf(true, "encounter") { encounter() }
            .execute()

        assertFalse(result.containsKey("patient"))
        assertTrue(result.containsKey("encounter"))
        assertFalse(result.hasErrors())
    }

    @Test
    fun `addIf true with duplicate key still throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .add("patient") { patient() }
                .addIf(true, "patient") { patient() }
        }
    }

    @Test
    fun `addIf false with key that would be duplicate does not throw`() {
        // The condition is false so nothing is registered — no duplicate check fires
        AsyncOperationResult()
            .add("patient") { patient() }
            .addIf(false, "patient") { patient() }
        // No exception — test passes by reaching here
    }

    // ── Dependency contract ───────────────────────────────────────────────────

    @Test
    fun `key skipped by addIf false cannot be referenced as a dependency`() {
        // The key was never registered, so requireKeysExist throws
        assertThrows<IllegalArgumentException> {
            AsyncOperationResult()
                .addIf(false, "patient") { patient() }
                .addAfter("encounter", "patient") { encounter() }
        }
    }
}
