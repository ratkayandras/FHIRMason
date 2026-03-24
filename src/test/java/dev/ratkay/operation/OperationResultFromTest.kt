package dev.ratkay.operation

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class OperationResultFromTest {

    // ── fromParameters ────────────────────────────────────────────────────────

    @Test
    fun `fromParameters - resource parameter stored under parameter name`() {
        val patient = patient()
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient }
        }

        val result = OperationResult.fromParameters(fhirParams)

        assertTrue(result.containsKey("patient"))
        assertEquals(1, result.count("patient"))
        assertThat(result.getAll("patient").first(), sameInstance(patient))
    }

    @Test
    fun `fromParameters - primitive type parameter stored under parameter name`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "flag"; value = BooleanType(true) }
            addParameter().apply { name = "label"; value = StringType("hello") }
        }

        val result = OperationResult.fromParameters(fhirParams)

        assertTrue(result.containsKey("flag"))
        assertTrue(result.containsKey("label"))
        assertThat(result.getAll("flag").first(), instanceOf(BooleanType::class.java))
        assertThat(result.getAll("label").first(), instanceOf(StringType::class.java))
        assertEquals(true, (result.getAll("flag").first() as BooleanType).value)
        assertEquals("hello", (result.getAll("label").first() as StringType).value)
    }

    @Test
    fun `fromParameters - multiple parameters with same name accumulate under that key`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "item"; resource = patient() }
            addParameter().apply { name = "item"; resource = appointment() }
        }

        val result = OperationResult.fromParameters(fhirParams)

        assertEquals(2, result.count("item"))
    }

    @Test
    fun `fromParameters - mixed resource and primitive parameters in same document`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
            addParameter().apply { name = "count";   value = IntegerType(42) }
        }

        val result = OperationResult.fromParameters(fhirParams)

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("count"))
        assertThat(result.getAll("patient").first(), instanceOf(Patient::class.java))
        assertThat(result.getAll("count").first(), instanceOf(IntegerType::class.java))
        assertEquals(42, (result.getAll("count").first() as IntegerType).value)
    }

    @Test
    fun `fromParameters - nested parts flattened under composite parent-dot-child key`() {
        val fhirParams = Parameters().apply {
            addParameter().apply {
                name = "address"
                addPart().apply { name = "city";    value = StringType("Springfield") }
                addPart().apply { name = "country"; value = StringType("US") }
            }
        }

        val result = OperationResult.fromParameters(fhirParams)

        // Parent-only entry with parts produces no "address" key itself
        assertFalse(result.containsKey("address"))
        assertTrue(result.containsKey("address.city"))
        assertTrue(result.containsKey("address.country"))
        assertEquals("Springfield", (result.getAll("address.city").first() as StringType).value)
        assertEquals("US", (result.getAll("address.country").first() as StringType).value)
    }

    @Test
    fun `fromParameters - deeply nested parts use multi-level composite key`() {
        val fhirParams = Parameters().apply {
            addParameter().apply {
                name = "outer"
                addPart().apply {
                    name = "inner"
                    addPart().apply { name = "leaf"; value = StringType("deep") }
                }
            }
        }

        val result = OperationResult.fromParameters(fhirParams)

        assertTrue(result.containsKey("outer.inner.leaf"))
        assertEquals("deep", (result.getAll("outer.inner.leaf").first() as StringType).value)
    }

    @Test
    fun `fromParameters - empty Parameters produces empty result`() {
        val result = OperationResult.fromParameters(Parameters())

        assertTrue(result.isEmpty())
        assertTrue(result.isSuccessful())
    }

    // ── Round-trip: toParameters → fromParameters ─────────────────────────────

    @Test
    fun `round-trip toParameters then fromParameters preserves all keys and resource types`() {
        val original = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }

        val roundTripped = OperationResult.fromParameters(original.toParameters())

        // Same key set
        assertThat(roundTripped.getKeys(), containsInAnyOrder("patient", "appt"))
        // Same count per key
        assertEquals(original.count("patient"), roundTripped.count("patient"))
        assertEquals(original.count("appt"),    roundTripped.count("appt"))
        // Same FHIR types
        assertThat(roundTripped.getAll("patient").first(), instanceOf(Patient::class.java))
        assertThat(roundTripped.getAll("appt").first(), instanceOf(Appointment::class.java))
    }

    @Test
    fun `round-trip preserves primitive type parameters`() {
        val original = OperationResult.of(StringType("ping"), "msg")

        val roundTripped = OperationResult.fromParameters(original.toParameters())

        assertTrue(roundTripped.containsKey("msg"))
        assertThat(roundTripped.getAll("msg").first(), instanceOf(StringType::class.java))
        assertEquals("ping", (roundTripped.getAll("msg").first() as StringType).value)
    }

    @Test
    fun `round-trip with multiple values under same key preserves count`() {
        val original = OperationResult.of(listOf(patient(), patient(), patient()), "patient")

        val roundTripped = OperationResult.fromParameters(original.toParameters())

        assertEquals(3, roundTripped.count("patient"))
        assertThat(roundTripped.getByType<Patient>(), hasSize(3))
    }

    // ── fromParametersTyped ───────────────────────────────────────────────────

    @Test
    fun `fromParametersTyped - sets typed head to first value under primary key`() {
        val patient = patient()
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient }
            addParameter().apply { name = "count";   value = IntegerType(1) }
        }

        val result: OperationResult<Patient> =
            OperationResult.fromParametersTyped(fhirParams, "patient", Patient::class)

        // Typed assignment proves compiler sees OperationResult<Patient>
        val retrieved: Patient = result.getResult()
        assertThat(retrieved, sameInstance(patient))
    }

    @Test
    fun `fromParametersTyped reified overload - no KClass argument needed`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "appt"; resource = appointment() }
        }

        val result: OperationResult<Appointment> =
            OperationResult.fromParametersTyped(fhirParams, "appt")

        val appt: Appointment = result.getResult()
        assertThat(appt, instanceOf(Appointment::class.java))
    }

    @Test
    fun `fromParametersTyped - all other parameters are still accessible`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
            addParameter().apply { name = "appt";    resource = appointment() }
        }

        val result = OperationResult.fromParametersTyped<Patient>(fhirParams, "patient")

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
        assertEquals(2, result.totalCount())
    }

    @Test
    fun `fromParametersTyped - throws when primary key is absent`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "appt"; resource = appointment() }
        }

        assertThrows<IllegalArgumentException> {
            OperationResult.fromParametersTyped<Patient>(fhirParams, "patient")
        }
    }

    @Test
    fun `fromParametersTyped - throws when key exists but type does not match`() {
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = appointment() } // wrong type
        }

        assertThrows<IllegalArgumentException> {
            OperationResult.fromParametersTyped<Patient>(fhirParams, "patient")
        }
    }

    // ── fromBundle ────────────────────────────────────────────────────────────

    @Test
    fun `fromBundle - resources stored under fhirType lowercase key`() {
        val bundle = Bundle().apply {
            addEntry().resource = patient()
            addEntry().resource = appointment()
        }

        val result = OperationResult.fromBundle(bundle)

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appointment"))
        assertThat(result.getAll("patient").first(), instanceOf(Patient::class.java))
        assertThat(result.getAll("appointment").first(), instanceOf(Appointment::class.java))
    }

    @Test
    fun `fromBundle - multiple resources of the same type accumulate under one key`() {
        val bundle = Bundle().apply {
            addEntry().resource = patient()
            addEntry().resource = patient()
            addEntry().resource = patient()
        }

        val result = OperationResult.fromBundle(bundle)

        assertEquals(3, result.count("patient"))
        assertEquals(1, result.getKeys().size)
    }

    @Test
    fun `fromBundle - entries without a resource are silently skipped`() {
        val bundle = Bundle().apply {
            addEntry().resource = patient()
            addEntry() // no resource — e.g. a bare transaction-response entry
            addEntry().apply {
                // entry with only a request, no resource
                request = Bundle.BundleEntryRequestComponent().apply {
                    method = Bundle.HTTPVerb.GET
                    url = "Patient/123"
                }
            }
        }

        val result = OperationResult.fromBundle(bundle)

        assertEquals(1, result.totalCount())
        assertTrue(result.containsKey("patient"))
    }

    @Test
    fun `fromBundle - empty bundle produces empty result`() {
        val result = OperationResult.fromBundle(Bundle())

        assertTrue(result.isEmpty())
        assertTrue(result.isSuccessful())
    }

    @Test
    fun `fromBundle - mixed resource types each get their own key`() {
        val bundle = Bundle().apply {
            addEntry().resource = patient()
            addEntry().resource = appointment()
            addEntry().resource = operationOutcome()
            addEntry().resource = observation()
        }

        val result = OperationResult.fromBundle(bundle)

        assertThat(result.getKeys(), containsInAnyOrder("patient", "appointment", "operationoutcome", "observation"))
        assertEquals(4, result.totalCount())
    }

    // ── fromBundle with keyStrategy ───────────────────────────────────────────

    @Test
    fun `fromBundle custom keyStrategy - keys by fullUrl`() {
        val bundle = Bundle().apply {
            addEntry().apply {
                fullUrl = "urn:uuid:patient-1"
                resource = patient()
            }
            addEntry().apply {
                fullUrl = "urn:uuid:appt-1"
                resource = appointment()
            }
        }

        val result = OperationResult.fromBundle(bundle) { entry -> entry.fullUrl }

        assertTrue(result.containsKey("urn:uuid:patient-1"))
        assertTrue(result.containsKey("urn:uuid:appt-1"))
        assertThat(result.getAll("urn:uuid:patient-1").first(), instanceOf(Patient::class.java))
    }

    @Test
    fun `fromBundle custom keyStrategy - returning empty string skips the entry`() {
        val bundle = Bundle().apply {
            addEntry().apply { resource = patient() }
            addEntry().apply { resource = appointment() }
        }

        // Only keep Patient entries
        val result = OperationResult.fromBundle(bundle) { entry ->
            if (entry.resource is Patient) entry.resource.fhirType().lowercase() else ""
        }

        assertTrue(result.containsKey("patient"))
        assertFalse(result.containsKey("appointment"))
        assertEquals(1, result.totalCount())
    }

    @Test
    fun `fromBundle custom keyStrategy - all entries under a single constant key`() {
        val bundle = Bundle().apply {
            addEntry().resource = patient()
            addEntry().resource = appointment()
            addEntry().resource = observation()
        }

        val result = OperationResult.fromBundle(bundle) { "resource" }

        assertEquals(1, result.getKeys().size)
        assertEquals(3, result.count("resource"))
    }

    @Test
    fun `fromBundle custom keyStrategy - key derived from resource field`() {
        val bundle = Bundle().apply {
            addEntry().resource = Patient().apply { id = "p1" }
            addEntry().resource = Patient().apply { id = "p2" }
        }

        val result = OperationResult.fromBundle(bundle) { entry ->
            "${entry.resource.fhirType().lowercase()}/${entry.resource.idPart}"
        }

        assertTrue(result.containsKey("patient/p1"))
        assertTrue(result.containsKey("patient/p2"))
        assertEquals(2, result.totalCount())
    }

    // ── fromBundle round-trip ─────────────────────────────────────────────────

    @Test
    fun `round-trip toBundle then fromBundle preserves resource types and counts`() {
        val original = OperationResult.of(patient(), "patient")
            .add("appt") { appointment() }

        val bundle = original.toBundle(Bundle.BundleType.COLLECTION)
        val roundTripped = OperationResult.fromBundle(bundle)

        assertThat(roundTripped.getByType<Patient>(), hasSize(1))
        assertThat(roundTripped.getByType<Appointment>(), hasSize(1))
        assertEquals(2, roundTripped.totalCount())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun patient() = Patient().apply {
        addName().apply {
            family = "Doe"
            addGiven("John")
        }
    }

    private fun appointment() = Appointment().apply {
        status = Appointment.AppointmentStatus.BOOKED
    }

    private fun operationOutcome() = OperationOutcome().apply {
        addIssue().apply {
            severity = OperationOutcome.IssueSeverity.INFORMATION
            code = OperationOutcome.IssueType.INFORMATIONAL
        }
    }

    private fun observation() = Observation().apply {
        status = Observation.ObservationStatus.FINAL
    }
}
