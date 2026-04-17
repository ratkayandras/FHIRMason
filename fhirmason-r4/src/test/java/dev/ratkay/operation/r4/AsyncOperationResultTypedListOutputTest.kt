package dev.ratkay.operation.r4

import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AsyncOperationResultTypedListOutputTest {

    @Test
    fun `addList - R inferred as concrete type from lambda`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") {
                listOf(Patient().apply { id = "p1" }, Patient().apply { id = "p2" })
            }
            .run()

        assertThat(result.count("patients"), `is`(2))
        val patients = result.getAll("patients")
        assertEquals("p1", (patients[0] as Patient).id)
    }

    @Test
    fun `addListAfter vararg - R inferred as concrete type from lambda`() = runBlocking {
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
        assertEquals("obs1-p1", (result.getAll("observations").first() as Observation).id)
    }

    @Test
    fun `addListAfterAll - both T and R inferred as concrete types`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") { listOf(Patient().apply { id = "p1" }, Patient().apply { id = "p2" }) }
            .addListAfterAll("observations", "patients", Patient::class) { patients ->
                patients.map { p -> Observation().apply { id = "obs-${p.id}" } }
            }
            .run()

        assertThat(result.count("observations"), `is`(2))
    }

    @Test
    fun `addListWithTimeout - R inferred as concrete type`() = runBlocking {
        val result = AsyncOperationResult()
            .addListWithTimeout("appointments", 5000L) {
                listOf(Appointment().apply { id = "a1" }, Appointment().apply { id = "a2" })
            }
            .run()

        assertThat(result.count("appointments"), `is`(2))
    }

    @Test
    fun `addListAfterWithTimeout vararg - R inferred as concrete type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addListAfterWithTimeout("observations", "patient", timeoutMs = 5000L) { deps ->
                val pid = (deps["patient"]!!.first() as Patient).id
                listOf(Observation().apply { id = "obs-$pid" })
            }
            .run()

        assertThat(result.count("observations"), `is`(1))
    }

    @Test
    fun `addListAfterAllWithTimeout - both T and R inferred as concrete types`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") { listOf(Patient().apply { id = "p1" }) }
            .addListAfterAllWithTimeout("observations", "patients", Patient::class, 5000L) { patients ->
                patients.map { p -> Observation().apply { id = "obs-${p.id}" } }
            }
            .run()

        assertThat(result.count("observations"), `is`(1))
    }

    @Test
    fun `addListAfterWithRetry vararg - R inferred as concrete type`() = runBlocking {
        val result = AsyncOperationResult()
            .add("patient") { Patient().apply { id = "p1" } }
            .addListAfterWithRetry("observations", "patient", maxAttempts = 1) { deps ->
                val pid = (deps["patient"]!!.first() as Patient).id
                listOf(Observation().apply { id = "obs-$pid" })
            }
            .run()

        assertThat(result.count("observations"), `is`(1))
    }

    @Test
    fun `addListAfterAllWithRetry - both T and R inferred as concrete types`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("patients") { listOf(Patient().apply { id = "p1" }, Patient().apply { id = "p2" }) }
            .addListAfterAllWithRetry("observations", "patients", Patient::class, maxAttempts = 1) { patients ->
                patients.map { p -> Observation().apply { id = "obs-${p.id}" } }
            }
            .run()

        assertThat(result.count("observations"), `is`(2))
    }

    @Test
    fun `addListIf true condition - R inferred as concrete type`() = runBlocking {
        val result = AsyncOperationResult()
            .addListIf(true, "patients") {
                listOf(Patient().apply { id = "p1" })
            }
            .run()

        assertThat(result.count("patients"), `is`(1))
    }

    @Test
    fun `addListIf false condition - block not executed`() = runBlocking {
        val result = AsyncOperationResult()
            .addListIf(false, "patients") {
                listOf(Patient().apply { id = "p1" })
            }
            .run()

        assertThat(result.containsKey("patients"), `is`(false))
    }

    @Test
    fun `addList with heterogeneous list - R inferred as Base regression guard`() = runBlocking {
        val result = AsyncOperationResult()
            .addList("resources") {
                @Suppress("UNCHECKED_CAST")
                listOf<Base>(Patient().apply { id = "p1" }, Observation().apply { id = "o1" })
            }
            .run()

        assertThat(result.count("resources"), `is`(2))
    }
}
