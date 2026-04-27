package dev.ratkay.operation.r4

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.hl7.fhir.r4.model.Appointment
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    @Test
    fun `round-trip fromParameters toParameters reconstructs nested parts - two-level`() {
        val original = Parameters().apply {
            addParameter().apply {
                name = "address"
                addPart().apply { name = "city";    value = StringType("Springfield") }
                addPart().apply { name = "country"; value = StringType("US") }
            }
        }

        val reconstructed = OperationResult.fromParameters(original).toParameters()

        // Must NOT emit flat "address.city" / "address.country" parameter names
        assertFalse(reconstructed.hasParameter("address.city"))
        assertFalse(reconstructed.hasParameter("address.country"))

        // Must emit a single top-level "address" parameter
        val addressParams = reconstructed.parameter.filter { it.name == "address" }
        assertThat(addressParams, hasSize(1))

        // "address" must carry its children as parts, not as a value/resource
        val addressParam = addressParams.first()
        assertFalse(addressParam.hasValue())
        assertFalse(addressParam.hasResource())
        assertThat(addressParam.part, hasSize(2))

        val cityPart    = addressParam.part.first { it.name == "city" }
        val countryPart = addressParam.part.first { it.name == "country" }
        assertEquals("Springfield", (cityPart.value    as StringType).value)
        assertEquals("US",          (countryPart.value as StringType).value)
    }

    @Test
    fun `round-trip fromParameters toParameters reconstructs deeply nested parts - three-level`() {
        val original = Parameters().apply {
            addParameter().apply {
                name = "outer"
                addPart().apply {
                    name = "inner"
                    addPart().apply { name = "leaf"; value = StringType("deep") }
                }
            }
        }

        val reconstructed = OperationResult.fromParameters(original).toParameters()

        assertFalse(reconstructed.hasParameter("outer.inner.leaf"))

        val outerParams = reconstructed.parameter.filter { it.name == "outer" }
        assertThat(outerParams, hasSize(1))

        val innerParts = outerParams.first().part.filter { it.name == "inner" }
        assertThat(innerParts, hasSize(1))

        val leafParts = innerParts.first().part.filter { it.name == "leaf" }
        assertThat(leafParts, hasSize(1))
        assertEquals("deep", (leafParts.first().value as StringType).value)
    }

    @Test
    fun `round-trip fromParameters toParameters handles mixed flat and nested parameters`() {
        val original = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
            addParameter().apply {
                name = "contact"
                addPart().apply { name = "phone"; value = StringType("555-1234") }
                addPart().apply { name = "email"; value = StringType("a@b.com") }
            }
            addParameter().apply { name = "flag"; value = BooleanType(true) }
        }

        val reconstructed = OperationResult.fromParameters(original).toParameters()

        // Flat params preserved as-is
        assertTrue(reconstructed.hasParameter("patient"))
        assertTrue(reconstructed.hasParameter("flag"))
        assertFalse(reconstructed.hasParameter("contact.phone"))
        assertFalse(reconstructed.hasParameter("contact.email"))

        // Nested param reconstructed
        val contactParams = reconstructed.parameter.filter { it.name == "contact" }
        assertThat(contactParams, hasSize(1))
        val parts = contactParams.first().part
        assertThat(parts.map { it.name }, containsInAnyOrder("phone", "email"))
    }

    @Test
    fun `round-trip fromParameters toParameters handles multiple values at same nested key`() {
        // Two separate "label" parts under "meta"
        val original = Parameters().apply {
            addParameter().apply {
                name = "meta"
                addPart().apply { name = "label"; value = StringType("alpha") }
                addPart().apply { name = "label"; value = StringType("beta") }
            }
        }

        val reconstructed = OperationResult.fromParameters(original).toParameters()

        val metaParams = reconstructed.parameter.filter { it.name == "meta" }
        assertThat(metaParams, hasSize(1))
        val labelParts = metaParams.first().part.filter { it.name == "label" }
        assertThat(labelParts, hasSize(2))
        val labelValues = labelParts.map { (it.value as StringType).value }
        assertThat(labelValues, containsInAnyOrder("alpha", "beta"))
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

    @Test
    fun `fromParametersTyped - Class overload sets typed head identical to KClass overload`() {
        val patient = patient()
        val fhirParams = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient }
        }

        val result = OperationResult.fromParametersTyped(fhirParams, "patient", Patient::class.java)

        assertSame(patient, result.getResult())
        assertTrue(result.containsKey("patient"))
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

    // ── fromBundleTyped ───────────────────────────────────────────────────────

    @Test
    fun `fromBundleTyped - sets typed head to first resource under primary key`() {
        val p = Patient().apply { id = "p1" }
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = p
        }

        val result = OperationResult.fromBundleTyped(bundle, "patient", Patient::class)

        assertInstanceOf(Patient::class.java, result.getResult())
        assertEquals("p1", result.getResult().idPart)
    }

    @Test
    fun `fromBundleTyped reified overload - no KClass argument needed`() {
        val p = patient()
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = p
        }

        val result = OperationResult.fromBundleTyped<Patient>(bundle, "patient")

        assertInstanceOf(Patient::class.java, result.getResult())
    }

    @Test
    fun `fromBundleTyped Class overload - Java-friendly`() {
        val p = patient()
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = p
        }

        val result = OperationResult.fromBundleTyped(bundle, "patient", Patient::class.java)

        assertInstanceOf(Patient::class.java, result.getResult())
    }

    @Test
    fun `fromBundleTyped - all other resources are still accessible`() {
        val p = patient()
        val a = appointment()
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = p
            addEntry().resource = a
        }

        val result = OperationResult.fromBundleTyped<Patient>(bundle, "patient")

        assertThat(result.getByType<Patient>(), hasSize(1))
        assertThat(result.getByType<Appointment>(), hasSize(1))
        assertEquals(2, result.totalCount())
    }

    @Test
    fun `fromBundleTyped - throws when primary key is absent`() {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = appointment()
        }

        assertThrows<IllegalArgumentException> {
            OperationResult.fromBundleTyped<Patient>(bundle, "patient")
        }
    }

    @Test
    fun `fromBundleTyped - throws when key exists but type does not match`() {
        val bundle = Bundle().apply {
            type = Bundle.BundleType.COLLECTION
            addEntry().resource = appointment()
        }

        assertThrows<IllegalArgumentException> {
            OperationResult.fromBundleTyped<Patient>(bundle, "appointment")
        }
    }

    // ── extractParam / extractParamList ──────────────────────────────────────

    @Test
    fun `extractParam sets first matching value as pipeline head`() {
        val params = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
        }
        val result = OperationResult.fromParameters(params)
            .extractParam("patient", Patient::class)

        assertNotNull(result.getResult())
        assertInstanceOf(Patient::class.java, result.getResult())
    }

    @Test
    fun `extractParam with reified overload requires no KClass`() {
        val params = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
        }
        val result = OperationResult.fromParameters(params)
            .extractParam<Patient>("patient")

        assertNotNull(result.getResult())
        assertInstanceOf(Patient::class.java, result.getResult())
    }

    @Test
    fun `extractParam throws when key does not exist`() {
        val params = Parameters()
        val or = OperationResult.fromParameters(params)

        assertThrows<IllegalArgumentException> {
            or.extractParam<Patient>("patient")
        }
    }

    @Test
    fun `extractParam throws when key exists but type does not match`() {
        val params = Parameters().apply {
            addParameter().apply { name = "patient"; resource = appointment() }
        }
        val or = OperationResult.fromParameters(params)

        assertThrows<IllegalArgumentException> {
            or.extractParam<Patient>("patient")
        }
    }

    @Test
    fun `extractParam preserves all parameters in the map`() {
        val params = Parameters().apply {
            addParameter().apply { name = "patient"; resource = patient() }
            addParameter().apply { name = "appt"; resource = appointment() }
        }
        val result = OperationResult.fromParameters(params)
            .extractParam<Patient>("patient")

        assertTrue(result.containsKey("patient"))
        assertTrue(result.containsKey("appt"))
        assertEquals(2, result.totalCount())
    }

    @Test
    fun `extractParamList sets typed list as pipeline head`() {
        val params = Parameters().apply {
            repeat(3) { addParameter().apply { name = "patients"; resource = patient() } }
        }
        val result = OperationResult.fromParameters(params)
            .extractParamList<Patient>("patients")

        val list = result.getResult()
        assertNotNull(list)
        assertEquals(3, list!!.size)
    }

    @Test
    fun `extractParamList returns empty list when key is absent`() {
        val params = Parameters()
        val result = OperationResult.fromParameters(params)
            .extractParamList<Patient>("patients")

        val list = result.getResult()
        assertNotNull(list)
        assertEquals(0, list!!.size)
    }

    @Test
    fun `extractParamList returns empty list when type does not match`() {
        val params = Parameters().apply {
            addParameter().apply { name = "patients"; resource = appointment() }
        }
        val result = OperationResult.fromParameters(params)
            .extractParamList<Patient>("patients")

        val list = result.getResult()
        assertNotNull(list)
        assertEquals(0, list!!.size)
    }

    @Test
    fun `extractParam enables downstream addUsing`() {
        val date = DateType("2024-01-15")
        val params = Parameters().apply {
            addParameter().apply { name = "date"; value = date }
        }
        val result = OperationResult.fromParameters(params)
            .extractParam<DateType>("date")
            .addUsing("label") { d -> StringType(d.valueAsString) }

        assertNotNull(result.getResult())
        assertEquals("2024-01-15", (result.getResult() as StringType).value)
        assertTrue(result.containsKey("label"))
    }

    @Test
    fun `extractParam on nested parameter key`() {
        val params = Parameters().apply {
            addParameter().apply {
                name = "address"
                addPart().apply { name = "city"; value = StringType("Springfield") }
            }
        }
        val result = OperationResult.fromParameters(params)
            .extractParam<StringType>("address.city")

        assertNotNull(result.getResult())
        assertEquals("Springfield", (result.getResult() as StringType).value)
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
